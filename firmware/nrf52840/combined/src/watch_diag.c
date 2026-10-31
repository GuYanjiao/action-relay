/* Read-only S4 companion discovery. No AuthKey, pairing, writes or shutter control. */
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/sys/byteorder.h>
#include "radio_scan.h"
#include "watch_diag.h"
#include "s4_link.h"
#include "miwear_gatt.h"
#include "miwear_runtime.h"

LOG_MODULE_REGISTER(watch_diag, LOG_LEVEL_INF);
static struct bt_conn *watch_peer;
static struct bt_gatt_discover_params discovery;
static bool discovering;
static bool initialized;
static atomic_t scan_reports;
struct advert { bool xiaomi, heart; char name[40]; };
static bool field(struct bt_data *data, void *context)
{
    struct advert *advert = context;
    if (data->type == BT_DATA_NAME_COMPLETE || data->type == BT_DATA_NAME_SHORTENED) {
        size_t n = MIN(data->data_len, sizeof(advert->name) - 1);
        memcpy(advert->name, data->data, n);
        advert->name[n] = 0;
    }
    if (data->type == BT_DATA_UUID16_ALL || data->type == BT_DATA_UUID16_SOME)
        for (size_t i = 0; i + 1 < data->data_len; i += 2) {
            uint16_t uuid = sys_get_le16(data->data + i);
            if (uuid == 0xfe95) advert->xiaomi = true;
            if (uuid == 0x180d) advert->heart = true;
        }
    if (data->type == BT_DATA_SVC_DATA16 && data->data_len >= 2) {
        uint16_t uuid = sys_get_le16(data->data);
        if (uuid == 0xfe95) advert->xiaomi = true;
        if (uuid == 0x180d) advert->heart = true;
    }
    return true;
}
static void advertisement(const bt_addr_le_t *address, int8_t rssi, uint8_t type,
                           struct net_buf_simple *data)
{
    ARG_UNUSED(type); /* Native logging fakes do not consume format arguments. */
    atomic_inc(&scan_reports);
    struct advert advert = {0};
    bt_data_parse(data, field, &advert);
    if (!advert.xiaomi && !advert.heart && !strstr(advert.name, "Watch S4")) return;
    char text[BT_ADDR_LE_STR_LEN];
    bt_addr_le_to_str(address, text, sizeof text);
    LOG_INF("Candidate %s RSSI=%d name=%s FE95=%d HR=%d type=0x%02x", text, rssi, advert.name, advert.xiaomi, advert.heart, type);
}
static void create_failed(int error) { LOG_WRN("Watch diagnostic connect failed: %d", error); }
static void connected(struct bt_conn *conn, uint8_t error)
{
    if (conn != watch_peer) return;
    radio_scan_connected(RADIO_WATCH);
    if (error) {
        LOG_WRN("Watch diagnostic HCI error: %u", error);
        bt_conn_unref(watch_peer); watch_peer = NULL;
        return;
    }
    int ownership = s4_link_adopt(conn, S4_CLIENT_COMPANION);
    if (ownership && ownership != -EALREADY) {
        LOG_WRN("Selected watch conflicts with existing S4 link: %d", ownership);
        bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
        return;
    }
    LOG_INF("Watch diagnostic connected; run watch services then watch chars");
}
static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    if (conn != watch_peer) return;
    LOG_INF("Watch diagnostic disconnected: %u", reason);
    (void)s4_link_release(conn, S4_CLIENT_COMPANION);
    bt_conn_unref(watch_peer); watch_peer = NULL;
    discovering = false;
}
BT_CONN_CB_DEFINE(watch_diagnostic_callbacks) = {.connected = connected, .disconnected = disconnected};
static uint8_t discovered(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                           struct bt_gatt_discover_params *params)
{
    ARG_UNUSED(conn);
    if (!attr) {
        discovering = false;
        LOG_INF("Watch diagnostic discovery complete");
        return BT_GATT_ITER_STOP;
    }
    char uuid[BT_UUID_STR_LEN];
    if (params->type == BT_GATT_DISCOVER_PRIMARY) {
        const struct bt_gatt_service_val *value = attr->user_data;
        bt_uuid_to_str(value->uuid, uuid, sizeof uuid);
        LOG_INF("Service %s start=0x%04x end=0x%04x", uuid, attr->handle, value->end_handle);
    } else {
        const struct bt_gatt_chrc *value = attr->user_data;
        bt_uuid_to_str(value->uuid, uuid, sizeof uuid);
        LOG_INF("Characteristic %s value=0x%04x properties=0x%02x", uuid,
                value->value_handle, value->properties);
    }
    return BT_GATT_ITER_CONTINUE;
}
static int discover(uint8_t type)
{
    if (!watch_peer) return -ENOTCONN;
    if (discovering) return -EBUSY;
    memset(&discovery, 0, sizeof discovery);
    discovery.start_handle = BT_ATT_FIRST_ATTRIBUTE_HANDLE;
    discovery.end_handle = BT_ATT_LAST_ATTRIBUTE_HANDLE;
    discovery.type = type; discovery.func = discovered;
    discovering = true;
    int error = bt_gatt_discover(watch_peer, &discovery);
    if (error) discovering = false;
    return error;
}
void watch_diag_init(void)
{ miwear_runtime_init(); radio_scan_register(RADIO_WATCH, advertisement); initialized = true; }
bool watch_diag_command(char *line)
{
    char *args[5], *cursor = line;
    size_t count = 0;
    while (*cursor) {
        while (*cursor == ' ' || *cursor == '\t') ++cursor;
        if (!*cursor) break;
        if (count == ARRAY_SIZE(args)) return true;
        args[count++] = cursor;
        while (*cursor && *cursor != ' ' && *cursor != '\t') ++cursor;
        if (*cursor) *cursor++ = 0;
    }
    if (!count || strcmp(args[0], "watch")) return false;
    if (!initialized) { LOG_WRN("BLE diagnostics are not initialized"); return true; }
    int error = -EINVAL;
    if (count == 2 && !strcmp(args[1], "scan")) {
        atomic_set(&scan_reports, 0);
        radio_scan_request(RADIO_WATCH, true); error = 0;
    } else if (count == 2 && !strcmp(args[1], "status")) {
        LOG_INF("Watch selected=%u discovering=%u channel_busy=%u", watch_peer != NULL, discovering, miwear_gatt_busy()); error = 0;
    } else if (count == 2 && !strcmp(args[1], "stop")) {
        radio_scan_request(RADIO_WATCH, false); error = 0;
        LOG_INF("Watch diagnostic advertisements received: %ld", (long)atomic_get(&scan_reports));
    } else if (count == 4 && !strcmp(args[1], "connect")) {
        bt_addr_le_t address;
        error = bt_addr_from_str(args[2], &address.a);
        if (!strcmp(args[3], "public")) address.type = BT_ADDR_LE_PUBLIC;
        else if (!strcmp(args[3], "random")) address.type = BT_ADDR_LE_RANDOM;
        else error = -EINVAL;
        if (!error) {
            radio_scan_request(RADIO_WATCH, false);
            if (watch_peer) error = -EALREADY;
            else {
                watch_peer = s4_link_acquire(S4_CLIENT_COMPANION, &address);
                error = watch_peer ? 0 : radio_scan_connect(RADIO_WATCH, &address, &watch_peer, create_failed);
            }
        }
    } else if (count == 2 && !strcmp(args[1], "attach")) {
        if (watch_peer) error = -EALREADY;
        else {
            watch_peer = s4_link_acquire(S4_CLIENT_COMPANION, NULL);
            error = watch_peer ? 0 : -ENOTCONN;
        }
    } else if (count == 2 && !strcmp(args[1], "channel")) error = !watch_peer ? -ENOTCONN : discovering ? -EBUSY : miwear_gatt_open(watch_peer);
    else if (count == 2 && !strcmp(args[1], "unchannel")) { miwear_gatt_close(); error = 0; }
    else if (count == 3 && !strcmp(args[1], "auth")) error = miwear_runtime_auth(args[2]);
    else if (count == 2 && !strcmp(args[1], "services")) error = miwear_gatt_busy() ? -EBUSY : discover(BT_GATT_DISCOVER_PRIMARY);
    else if (count == 2 && !strcmp(args[1], "chars")) error = miwear_gatt_busy() ? -EBUSY : discover(BT_GATT_DISCOVER_CHARACTERISTIC);
    else if (count == 2 && !strcmp(args[1], "disconnect")) {
        if (!watch_peer) error = -ENOTCONN;
        else if (discovering || miwear_gatt_busy()) error = -EBUSY;
        else if (s4_link_has_other(watch_peer, S4_CLIENT_COMPANION)) {
            (void)s4_link_release(watch_peer, S4_CLIENT_COMPANION);
            bt_conn_unref(watch_peer); watch_peer = NULL; error = 0;
        } else if (!s4_link_prepare_disconnect(watch_peer, S4_CLIENT_COMPANION)) error = -EBUSY;
        else {
            error = bt_conn_disconnect(watch_peer, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
            if (error) s4_link_cancel_disconnect(watch_peer);
        }
    }
    LOG_INF("Watch diagnostic command result: %d", error);
    return true;
}
