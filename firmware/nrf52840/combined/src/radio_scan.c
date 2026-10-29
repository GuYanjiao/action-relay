/* Shared scanning and bounded connection initiation; no device authentication. */
#include <errno.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include "radio_scan.h"

LOG_MODULE_REGISTER(radio_scan, LOG_LEVEL_INF);
static atomic_t wanted;
static atomic_t initiating = ATOMIC_INIT(-1);
static struct k_spinlock pending_lock;
static struct {
    bt_addr_le_t address;
    struct bt_conn **connection;
    void (*failed)(int);
    bool valid;
} pending;
static radio_advert_fn callbacks[RADIO_CLIENT_COUNT];
static bool running;
static void reconcile(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(scan_update, reconcile);

static bool valid_client(enum radio_client client)
{ return client >= RADIO_HEART && client < RADIO_CLIENT_COUNT; }

static void received(const struct bt_le_scan_recv_info *info, struct net_buf_simple *buffer)
{
    atomic_val_t clients = atomic_get(&wanted);
    for (int i = 0; i < RADIO_CLIENT_COUNT; ++i) {
        if (!(clients & BIT(i)) || !callbacks[i]) continue;
        /* bt_data_parse consumes the buffer; every owner sees the same advertisement. */
        struct net_buf_simple_state saved;
        net_buf_simple_save(buffer, &saved);
        callbacks[i](info->addr, info->rssi, info->adv_type, buffer);
        net_buf_simple_restore(buffer, &saved);
    }
}
static struct bt_le_scan_cb scan_callbacks = {.recv = received};

static void reconcile(struct k_work *work)
{
    ARG_UNUSED(work);
    bool pause = atomic_get(&initiating) >= 0;
    bool needed = atomic_get(&wanted) != 0 && !pause;
    if (running && !needed) {
        int error = bt_le_scan_stop();
        if (error && error != -EALREADY) {
            LOG_WRN("Scan stop failed: %d", error);
            k_work_reschedule(&scan_update, K_MSEC(100));
            return;
        }
        running = false;
    }
    if (pause) {
        bt_addr_le_t address;
        struct bt_conn **connection = NULL;
        void (*failed)(int) = NULL;
        k_spinlock_key_t key = k_spin_lock(&pending_lock);
        if (pending.valid) {
            address = pending.address;
            connection = pending.connection;
            failed = pending.failed;
            pending.valid = false;
        }
        k_spin_unlock(&pending_lock, key);
        if (connection) {
            int error = bt_conn_le_create(&address, BT_CONN_LE_CREATE_CONN,
                                          BT_LE_CONN_PARAM_DEFAULT, connection);
            if (error) {
                atomic_set(&initiating, -1);
                if (failed) failed(error);
                k_work_reschedule(&scan_update, K_NO_WAIT);
            }
        }
        return;
    }
    if (!running && needed) {
        const struct bt_le_scan_param params = {
            .type = BT_LE_SCAN_TYPE_ACTIVE,
            .interval = BT_GAP_SCAN_FAST_INTERVAL,
            .window = BT_GAP_SCAN_FAST_WINDOW / 2,
        };
        int error = bt_le_scan_start(&params, NULL);
        if (!error) running = true;
        else {
            LOG_WRN("Shared scan start failed: %d", error);
            k_work_reschedule(&scan_update, K_MSEC(250));
        }
    }
    /* A request may have changed while the controller operation was in flight. */
    if ((atomic_get(&wanted) != 0 && atomic_get(&initiating) < 0) != running)
        k_work_reschedule(&scan_update, K_NO_WAIT);
}

void radio_scan_init(void)
{ bt_le_scan_cb_register(&scan_callbacks); }
void radio_scan_register(enum radio_client client, radio_advert_fn callback)
{ if (valid_client(client)) callbacks[client] = callback; }
void radio_scan_request(enum radio_client client, bool enabled)
{
    if (!valid_client(client)) return;
    if (enabled) atomic_set_bit(&wanted, client);
    else atomic_clear_bit(&wanted, client);
    k_work_reschedule(&scan_update, K_NO_WAIT);
}
int radio_scan_connect(enum radio_client client, const bt_addr_le_t *address,
                       struct bt_conn **connection, void (*failed)(int))
{
    if (!valid_client(client) || !address || !connection || !failed) return -EINVAL;
    if (!atomic_cas(&initiating, -1, client)) return -EBUSY;
    k_spinlock_key_t key = k_spin_lock(&pending_lock);
    pending.address = *address;
    pending.connection = connection;
    pending.failed = failed;
    pending.valid = true;
    k_spin_unlock(&pending_lock, key);
    k_work_reschedule(&scan_update, K_NO_WAIT);
    return 0;
}
void radio_scan_connected(enum radio_client client)
{
    if (valid_client(client) && atomic_cas(&initiating, client, -1))
        k_work_reschedule(&scan_update, K_NO_WAIT);
}
