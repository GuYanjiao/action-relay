/* BLE discovery flow adapted from Zephyr central_hr:
 * Copyright (c) 2015-2016 Intel Corporation
 * SPDX-License-Identifier: Apache-2.0
 */
#include <string.h>
#include <errno.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/drivers/clock_control.h>
#include <zephyr/drivers/clock_control/nrf_clock_control.h>
#include <zephyr/sys/reboot.h>
#include <nrfx.h>
#include <ant_interface.h>
#include <ant_parameters.h>
#include <ant_key_manager.h>
#include <ant_channel_config.h>
#include <ant_profiles/hrm/ant_hrm.h>
#include "hr_measurement.h"
#include "activity.h"
#include "radio_scan.h"

LOG_MODULE_REGISTER(relay, LOG_LEVEL_INF);
#define STALE_MS 5000
static struct bt_conn *peer;
static atomic_t ble_allowed;
static struct activity activity;
static struct bt_uuid_16 uuid = BT_UUID_INIT_16(0);
static struct bt_gatt_discover_params discovery;
static struct bt_gatt_subscribe_params subscription;
static struct k_spinlock sample_lock;
static struct {
    uint8_t bpm, beats;
    uint16_t beat_time;
    uint32_t fraction;
    int64_t received, advanced;
    bool valid, rr;
} sample;
static ant_hrm_profile_t hrm;
K_MSGQ_DEFINE(ant_events, sizeof(ant_evt_t), 32, 4);
static atomic_t ant_overflow;
static atomic_t scan_reports;
static atomic_t ble_connected;
static bool ant_open, ant_closing;
static void usb_bootloader_check(void)
{
#if defined(CONFIG_BOARD_PROMICRO_NRF52840)
    const struct device *console = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
    static bool rx_enabled;
    if (!rx_enabled && device_is_ready(console)) {
        uart_irq_rx_enable(console);
        rx_enabled = true;
    }
    unsigned char command;
    if (uart_poll_in(console, &command) == 0 && command == 'B') {
        /* Adafruit bootloader's documented UF2 reset request. */
        NRF_POWER->GPREGRET = 0x57;
        sys_reboot(SYS_REBOOT_COLD);
    }
#endif
}
static void profile_event(ant_hrm_profile_t *profile, ant_hrm_evt_t event);
HRM_SENS_PROFILE_CONFIG_DEF(relay, true, ANT_HRM_PAGE_0, profile_event);
/* Evaluation identity: device number 4242; manufacturer 255 = development. */
HRM_SENS_CHANNEL_CONFIG_DEF(relay, 0, 1, 4242, 0);

static void timeout_disconnect(struct bt_conn *conn, void *context)
{
    ARG_UNUSED(context);
    if (conn == peer) bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
}
static void receive_timeout(struct k_work *work)
{
    ARG_UNUSED(work);
    LOG_WRN("No valid heart rate notification for 15 seconds; reconnecting");
    bt_conn_foreach(BT_CONN_TYPE_LE, timeout_disconnect, NULL);
}
K_WORK_DELAYABLE_DEFINE(receive_watchdog, receive_timeout);

static void subscribed(struct bt_conn *conn, uint8_t err,
                       struct bt_gatt_subscribe_params *params)
{
    ARG_UNUSED(params);
    if (err) {
        LOG_WRN("Heart rate notification subscription rejected: %u", err);
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
    } else LOG_INF("Heart rate notifications enabled");
}

static void invalidate(void)
{
    k_spinlock_key_t key = k_spin_lock(&sample_lock);
    sample.valid = false;
    k_spin_unlock(&sample_lock, key);
}
static void profile_event(ant_hrm_profile_t *profile, ant_hrm_evt_t event)
{
    ARG_UNUSED(event);
    int64_t now = k_uptime_get();
    k_spinlock_key_t key = k_spin_lock(&sample_lock);
    bool fresh = sample.valid && now - sample.received < STALE_MS;
    if (fresh && !sample.rr) {
        /* No RR data: advance an estimated beat clock, never claim real HRV. */
        uint64_t units = sample.fraction + (uint64_t)(now - sample.advanced) * sample.bpm;
        uint32_t beats = units / 60000;
        sample.fraction = units % 60000;
        sample.beats += beats;
        sample.beat_time += beats * (61440U / sample.bpm);
        sample.advanced = now;
    }
    profile->HRM_PROFILE_computed_heart_rate = fresh ? sample.bpm : 0;
    profile->HRM_PROFILE_beat_count = sample.beats;
    profile->HRM_PROFILE_beat_time = sample.beat_time;
    profile->HRM_PROFILE_operating_time = now / 2000;
    k_spin_unlock(&sample_lock, key);
}
static uint8_t notified(struct bt_conn *conn, struct bt_gatt_subscribe_params *params,
                        const void *data, uint16_t length)
{
    if (!atomic_get(&ble_allowed)) return BT_GATT_ITER_STOP;
    if (!data) {
        params->value_handle = 0;
        invalidate();
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
        return BT_GATT_ITER_STOP;
    }
    struct hr_measurement value;
    if (!hr_parse(data, length, &value)) {
        LOG_WRN("Malformed or unsupported heart rate measurement (%u bytes)", length);
        return BT_GATT_ITER_CONTINUE;
    }
    int64_t now = k_uptime_get();
    k_spinlock_key_t key = k_spin_lock(&sample_lock);
    bool reset = !sample.valid || now - sample.received >= STALE_MS;
    sample.bpm = value.bpm;
    sample.valid = value.bpm != 0 && !value.contact_lost;
    sample.received = now;
    sample.rr = value.rr_count != 0;
    if (reset) {
        sample.advanced = now;
        sample.fraction = 0;
        sample.beat_time = (uint16_t)(now * 1024 / 1000 - value.rr_ticks);
    }
    if (sample.valid && sample.rr) {
        sample.beats += value.rr_count;
        sample.beat_time += value.rr_ticks;
        sample.advanced = now;
        sample.fraction = 0;
    }
    k_spin_unlock(&sample_lock, key);
    k_work_reschedule(&receive_watchdog, K_SECONDS(15));
    LOG_INF("BLE HR %u bpm, RR intervals %u, contact %s", value.bpm,
            value.rr_count, value.contact_lost ? "lost" : "ok/unknown");
    return BT_GATT_ITER_CONTINUE;
}
static uint8_t discovered(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                          struct bt_gatt_discover_params *params)
{
    int err;
    if (!atomic_get(&ble_allowed)) return BT_GATT_ITER_STOP;
    if (!attr) {
        LOG_WRN("Required heart rate attribute missing");
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
        return BT_GATT_ITER_STOP;
    }
    if (params->type == BT_GATT_DISCOVER_PRIMARY) {
        const struct bt_gatt_service_val *service = attr->user_data;
        discovery.end_handle = service->end_handle;
        uuid.val = BT_UUID_HRS_MEASUREMENT_VAL;
        discovery.type = BT_GATT_DISCOVER_CHARACTERISTIC;
        discovery.start_handle = attr->handle + 1;
    } else if (params->type == BT_GATT_DISCOVER_CHARACTERISTIC) {
        const struct bt_gatt_chrc *chrc = attr->user_data;
        if (!(chrc->properties & BT_GATT_CHRC_NOTIFY)) {
            bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
            return BT_GATT_ITER_STOP;
        }
        subscription.value_handle = chrc->value_handle;
        uuid.val = BT_UUID_GATT_CCC_VAL;
        discovery.type = BT_GATT_DISCOVER_DESCRIPTOR;
        discovery.start_handle = chrc->value_handle + 1;
    } else {
        subscription.notify = notified;
        subscription.subscribe = subscribed;
        subscription.value = BT_GATT_CCC_NOTIFY;
        subscription.ccc_handle = attr->handle;
        err = bt_gatt_subscribe(conn, &subscription);
        if (err && err != -EALREADY) {
            LOG_ERR("Subscribe failed: %d", err);
            bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
        } else LOG_INF("Heart rate notifications requested");
        return BT_GATT_ITER_STOP;
    }
    err = bt_gatt_discover(conn, &discovery);
    if (err) {
        LOG_ERR("Discovery failed: %d", err);
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
    }
    return BT_GATT_ITER_STOP;
}
static void scan_work_handler(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(scan_work, scan_work_handler);
static void connection_create_failed(int err)
{
    LOG_WRN("Heart rate connection creation failed: %d", err);
    if (atomic_get(&ble_allowed)) k_work_reschedule(&scan_work, K_SECONDS(1));
}
static bool advertised(struct bt_data *data, void *context)
{
    bool *found = context;
    if (data->type == BT_DATA_UUID16_ALL || data->type == BT_DATA_UUID16_SOME) {
        for (size_t i = 0; i + 1 < data->data_len; i += 2) {
            if (sys_get_le16(data->data + i) == BT_UUID_HRS_VAL) *found = true;
        }
    }
    return true;
}
static void scan_result(const bt_addr_le_t *address, int8_t rssi, uint8_t type,
                        struct net_buf_simple *ad)
{
    ARG_UNUSED(rssi);
    atomic_inc(&scan_reports);
    if (!atomic_get(&ble_allowed) || peer || (type != BT_GAP_ADV_TYPE_ADV_IND && type != BT_GAP_ADV_TYPE_SCAN_RSP)) return;
    bool found = false;
    bt_data_parse(ad, advertised, &found);
    if (!found || !atomic_get(&ble_allowed)) return;
    radio_scan_request(RADIO_HEART, false);
    int err = radio_scan_connect(RADIO_HEART, address, &peer, connection_create_failed);
    if (err) {
        LOG_WRN("Connection creation failed: %d", err);
        k_work_reschedule(&scan_work, K_SECONDS(1));
    }
}
static void scan_work_handler(struct k_work *work)
{
    ARG_UNUSED(work);
    if (!atomic_get(&ble_allowed) || peer) return;
    radio_scan_request(RADIO_HEART, true);
    LOG_INF("Requested shared scan for BLE Heart Rate Service 180D");
}
static void connected(struct bt_conn *conn, uint8_t err)
{
    /* Other BLE links must not enter the heart-rate discovery state machine. */
    if (conn != peer) return;
    radio_scan_connected(RADIO_HEART);
    if (err) {
        if (peer) bt_conn_unref(peer);
        peer = NULL;
        k_work_reschedule(&scan_work, K_SECONDS(1));
        return;
    }
    if (!atomic_get(&ble_allowed)) {
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
        return;
    }
    LOG_INF("BLE connected; discovering heart rate service");
    atomic_set(&ble_connected, 1);
    k_work_reschedule(&receive_watchdog, K_SECONDS(15));
    memset(&subscription, 0, sizeof(subscription));
    memset(&discovery, 0, sizeof(discovery));
    uuid.val = BT_UUID_HRS_VAL;
    discovery.uuid = &uuid.uuid;
    discovery.func = discovered;
    discovery.type = BT_GATT_DISCOVER_PRIMARY;
    discovery.start_handle = BT_ATT_FIRST_ATTRIBUTE_HANDLE;
    discovery.end_handle = BT_ATT_LAST_ATTRIBUTE_HANDLE;
    if (bt_gatt_discover(conn, &discovery))
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
}
static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    if (conn != peer) return;
    LOG_INF("BLE disconnected: %u", reason);
    atomic_clear(&ble_connected);
    k_work_cancel_delayable(&receive_watchdog);
    invalidate();
    if (peer) bt_conn_unref(peer);
    peer = NULL;
    k_work_reschedule(&scan_work, K_SECONDS(1));
}
BT_CONN_CB_DEFINE(connection_callbacks) = {
    .connected = connected, .disconnected = disconnected,
};
static const char *activity_name(enum activity_state state)
{
    return state == ACTIVITY_STANDBY ? "standby" :
           state == ACTIVITY_SEARCHING ? "searching" : "forwarding";
}
static void ble_stop(void)
{
    atomic_clear(&ble_allowed);
    struct k_work_sync sync;
    k_work_cancel_delayable_sync(&scan_work, &sync);
    k_work_cancel_delayable(&receive_watchdog);
    radio_scan_request(RADIO_HEART, false);
    bt_conn_foreach(BT_CONN_TYPE_LE, timeout_disconnect, NULL);
    invalidate();
}
struct cadence_receiver {
    uint8_t channel, type;
    uint16_t period;
    bool open, closing;
    struct cadence_tracker tracker;
};
static struct cadence_receiver cadence_rx[] = {
    {.channel = 1, .type = 122, .period = 8102},
    {.channel = 2, .type = 121, .period = 8086},
};
static uint16_t cadence_id = CONFIG_CADENCE_DEVICE_NUMBER;
static uint8_t cadence_type;
static unsigned next_receiver;
static int64_t next_listen, listen_deadline;
static int cadence_setup(void)
{
    for (unsigned i = 0; i < ARRAY_SIZE(cadence_rx); ++i) {
        struct cadence_receiver *rx = &cadence_rx[i];
        const ant_channel_config_t cfg = {
            .channel_number = rx->channel, .channel_type = CHANNEL_TYPE_SLAVE,
            .device_type = rx->type, .device_number = cadence_id,
            .channel_period = rx->period, .rf_freq = 57, .network_number = 0,
        };
        int err = ant_channel_init(&cfg);
        /* High-priority search must not interrupt heart-rate transmission. */
        if (!err) err = ant_channel_rx_search_timeout_set(rx->channel, 0);
        if (!err) err = ant_channel_low_priority_rx_search_timeout_set(rx->channel, 2);
        if (err) return err;
    }
    return 0;
}
static bool cadence_event(ant_evt_t *event)
{
    for (unsigned i = 0; i < ARRAY_SIZE(cadence_rx); ++i) {
        struct cadence_receiver *rx = &cadence_rx[i];
        if (event->channel != rx->channel) continue;
        if (event->event == EVENT_CHANNEL_CLOSED) {
            rx->open = rx->closing = false;
            rx->tracker.initialized = false;
        } else if (event->event == EVENT_RX_FAIL_GO_TO_SEARCH) {
            rx->tracker.initialized = false;
        } else if (event->event == EVENT_RX && !rx->closing &&
                   event->message.ANT_MESSAGE_ucSize >= 9 &&
                   (event->message.ANT_MESSAGE_ucMesgID == MESG_BROADCAST_DATA_ID ||
                    event->message.ANT_MESSAGE_ucMesgID == MESG_ACKNOWLEDGED_DATA_ID)) {
            uint16_t id;
            uint8_t type, transmission;
            int err = ant_channel_id_get(rx->channel, &id, &type, &transmission);
            if (err || type != rx->type || (cadence_id && id != cadence_id) ||
                (cadence_type && type != cadence_type)) return false;
            bool moving = cadence_motion(&rx->tracker, type,
                event->message.ANT_MESSAGE_aucPayload, 8, k_uptime_get());
            if (moving && !cadence_type) {
                cadence_id = id;
                cadence_type = type;
                LOG_INF("Cadence sensor learned: id=%u type=%u (until reboot)", id, type);
            }
            return moving;
        }
        break;
    }
    return false;
}
static void cadence_poll(int64_t now, bool active)
{
    bool cooldown = !active && now < activity.retry_after;
    bool busy = false;
    for (unsigned i = 0; i < ARRAY_SIZE(cadence_rx); ++i) {
        struct cadence_receiver *rx = &cadence_rx[i];
        if (!active && (now >= listen_deadline || cooldown) && rx->open && !rx->closing) {
            int err = ant_channel_close(rx->channel);
            if (!err) rx->closing = true;
            else LOG_WRN("Cadence close failed: %d", err);
        }
        busy |= rx->open || rx->closing;
    }
    if (busy || cooldown || now < next_listen) return;
    struct cadence_receiver *rx = &cadence_rx[next_receiver];
    if (cadence_type) {
        rx = &cadence_rx[cadence_type == 122 ? 0 : 1];
    } else next_receiver = (next_receiver + 1) % ARRAY_SIZE(cadence_rx);
    int err = ant_channel_id_set(rx->channel, cadence_id, rx->type, 0);
    if (!err) err = ant_channel_open(rx->channel);
    next_listen = now + CADENCE_POLL_MS;
    if (err) LOG_WRN("Cadence search start failed: %d", err);
    else {
        rx->tracker.initialized = false;
        rx->open = true;
        listen_deadline = now + CADENCE_LISTEN_MS;
        LOG_INF("Cadence search: type=%u id=%u window=%u ms", rx->type, cadence_id, CADENCE_LISTEN_MS);
    }
}
static void ant_callback(ant_evt_t *event)
{
    if (k_msgq_put(&ant_events, event, K_NO_WAIT)) atomic_set(&ant_overflow, 1);
}
static void startup_error(const char *stage, int err)
{
    /* Keep the failure visible even if USB is opened after startup. */
    while (true) {
        usb_bootloader_check();
        LOG_ERR("%s initialization failed: %d", stage, err);
        k_sleep(K_SECONDS(5));
    }
}
int main(void)
{
    int err = ant_init();
    if (!err) err = ant_cb_register(ant_callback);
    if (!err) err = ant_plus_key_set(0);
    if (!err) err = ant_hrm_sens_init(&hrm, HRM_SENS_CHANNEL_CONFIG(relay),
                                     HRM_SENS_PROFILE_CONFIG(relay));
    if (err) startup_error("ANT", err);
#if defined(CONFIG_CLOCK_CONTROL_NRF_K32SRC_SYNTH)
    /* LFSYNTH must run from the crystal, not from the HF RC oscillator. */
    static struct onoff_client hf_client;
    sys_notify_init_spinwait(&hf_client.notify);
    int clock_err = onoff_request(z_nrf_clock_control_get_onoff(CLOCK_CONTROL_NRF_SUBSYS_HF),
                                  &hf_client);
    if (clock_err < 0) startup_error("HFXO request", clock_err);
    /* This MPSL/SYNTH configuration needs an explicit HFXO start. Keep the
       managed request held so the crystal remains available for LFSYNTH. */
    NRF_CLOCK->TASKS_HFCLKSTART = 1;
    int64_t deadline = k_uptime_get() + 1000;
    while ((NRF_CLOCK->HFCLKSTAT & 1) == 0) {
        if (k_uptime_get() >= deadline) startup_error("HFXO startup timeout", -ETIMEDOUT);
        k_sleep(K_MSEC(1));
    }
#endif
    hrm.HRM_PROFILE_manuf_id = 255;
    hrm.HRM_PROFILE_serial_num = 4242;
    hrm.HRM_PROFILE_hw_version = 1;
    hrm.HRM_PROFILE_sw_version = 1;
    hrm.HRM_PROFILE_model_num = 1;
    err = bt_enable(NULL);
    if (err) startup_error("BLE", err);
    radio_scan_init();
    radio_scan_register(RADIO_HEART, scan_result);
    err = cadence_setup();
    if (err) startup_error("Cadence RX", err);
    LOG_INF("Standby: waiting for crank motion; BLE search budget %u seconds", ACTIVITY_SEARCH_MS / 1000);
    int64_t next_status = 0;
    while (true) {
        usb_bootloader_check();
        bool motion = false;
        ant_evt_t event;
        if (!k_msgq_get(&ant_events, &event, K_MSEC(250))) {
            if (event.channel == 0) {
                if (event.event == EVENT_CHANNEL_CLOSED) {
                    ant_open = false;
                    ant_closing = false;
                }
                profile_event(&hrm, ANT_HRM_PAGE_0_UPDATED);
                ant_hrm_sens_evt_handler(&event, &hrm);
            } else motion = cadence_event(&event);
        }
        if (atomic_get(&ant_overflow)) { LOG_ERR("ANT event overflow"); k_oops(); }
        int64_t now = k_uptime_get();
        k_spinlock_key_t key = k_spin_lock(&sample_lock);
        bool fresh = sample.valid && now - sample.received < STALE_MS;
        int64_t hr_received = sample.received;
        k_spin_unlock(&sample_lock, key);
        enum activity_state before = activity.state;
        activity_step(&activity, now, motion, fresh, hr_received);
        if (activity.state != before) {
            LOG_INF("Activity: %s -> %s", activity_name(before), activity_name(activity.state));
            if (activity.state == ACTIVITY_SEARCHING) {
                invalidate();
                atomic_set(&ble_allowed, 1);
                k_work_reschedule(&scan_work, K_NO_WAIT);
            } else if (activity.state == ACTIVITY_STANDBY) {
                ble_stop();
            }
        }
        fresh = fresh && activity.state == ACTIVITY_FORWARDING;
        cadence_poll(now, activity.state != ACTIVITY_STANDBY);
        if (now >= next_status) {
            LOG_INF("Status: mode=%s BLE=%s scan_reports=%ld fresh_HR=%u ANT=%u cadence_id=%u",
                    activity_name(activity.state),
                    atomic_get(&ble_connected) ? "connected" :
                    atomic_get(&ble_allowed) ? "scanning" : "off",
                    (long)atomic_get(&scan_reports), fresh, ant_open, cadence_id);
            next_status = now + (activity.state == ACTIVITY_STANDBY ? 30000 : 5000);
        }
        if (fresh && !ant_open && !ant_closing) {
            profile_event(&hrm, ANT_HRM_PAGE_0_UPDATED);
            err = ant_hrm_sens_open(&hrm);
            if (!err) { ant_open = true; LOG_INF("ANT+ HR started, device 4242"); }
            else { LOG_ERR("ANT open failed: %d", err); k_sleep(K_SECONDS(1)); }
        } else if (!fresh && ant_open && !ant_closing) {
            err = ant_channel_close(0);
            if (!err) { ant_closing = true; LOG_INF("ANT+ paused: no fresh heart rate"); }
            else { LOG_ERR("ANT close failed: %d", err); k_sleep(K_SECONDS(1)); }
        }
    }
}
