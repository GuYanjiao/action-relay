/* FE95 GATT adapter. No AuthKey, binding changes, logs of app payload or crypto.
 * All GATT operations/write fragmentation run on a private serial work queue.
 * Parameter objects survive cancellation until their terminal callbacks. */
#include <errno.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include "miwear_gatt.h"
#include "../../protocol/miwear_l1.h"
LOG_MODULE_REGISTER(miwear_gatt, LOG_LEVEL_INF);
enum stage { IDLE, SERVICE, CHARACTERISTICS, CCC, FLAG, SUBSCRIBE, ONLINE };
static enum stage stage;
static struct k_spinlock lock;
static struct bt_conn *connection;
static bool initialized, pending, done, stopping, cancelled, subscribed, unsubscribing;
static bool online;
static uint32_t transport_generation;
static int failure;
static uint32_t deadline;
static uint16_t first, last, flag_handle, rx_handle, tx_handle, ccc_handle, ccc_end;
static uint8_t flag_bytes[16];
static size_t flag_length;
static struct bt_gatt_discover_params discovery;
static struct bt_gatt_read_params read_params;
static struct bt_gatt_subscribe_params subscription;
static const struct bt_uuid_16 service_uuid = BT_UUID_INIT_16(0xfe95);
static const struct bt_uuid_16 flag_uuid = BT_UUID_INIT_16(0x0050);
static const struct bt_uuid_16 rx_uuid = BT_UUID_INIT_16(0x005e);
static const struct bt_uuid_16 tx_uuid = BT_UUID_INIT_16(0x005f);
static struct miwear_gatt_callbacks callbacks;
static struct k_work_q queue;
K_THREAD_STACK_DEFINE(transport_stack, 4096);
static struct k_work_delayable worker;
struct tx_frame { size_t length, offset; uint8_t bytes[MIWEAR_L1_HEADER + MIWEAR_L1_MAX_PAYLOAD]; };
static struct tx_frame tx[4];
static unsigned tx_head, tx_count;
static uint32_t tx_deadline;
static void kick(int delay) { k_work_reschedule_for_queue(&queue, &worker, K_MSEC(delay)); }
static void finish_operation(int error)
{
    k_spinlock_key_t key = k_spin_lock(&lock);
    if (error && !failure) failure = error;
    done = true;
    k_spin_unlock(&lock, key);
    kick(0);
}
static uint8_t discovered(struct bt_conn *conn, const struct bt_gatt_attr *attr,
    struct bt_gatt_discover_params *params)
{
    ARG_UNUSED(conn); ARG_UNUSED(params);
    if (!attr) { finish_operation(0); return BT_GATT_ITER_STOP; }
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool stop = stopping;
    if (!stop && stage == SERVICE) {
        const struct bt_gatt_service_val *value = attr->user_data;
        first = attr->handle + 1; last = value->end_handle; ccc_end = last; stop = true;
    } else if (!stop && stage == CHARACTERISTICS) {
        const struct bt_gatt_chrc *value = attr->user_data;
        if (rx_handle && attr->handle > rx_handle && attr->handle - 1 < ccc_end) ccc_end = attr->handle - 1;
        if (!bt_uuid_cmp(value->uuid, &flag_uuid.uuid) && (value->properties & BT_GATT_CHRC_READ)) flag_handle = value->value_handle;
        if (!bt_uuid_cmp(value->uuid, &rx_uuid.uuid) && (value->properties & BT_GATT_CHRC_NOTIFY)) rx_handle = value->value_handle;
        if (!bt_uuid_cmp(value->uuid, &tx_uuid.uuid) && (value->properties & BT_GATT_CHRC_WRITE_WITHOUT_RESP)) tx_handle = value->value_handle;
    } else if (!stop && stage == CCC) { ccc_handle = attr->handle; stop = true; }
    k_spin_unlock(&lock, key);
    if (stop) { finish_operation(0); return BT_GATT_ITER_STOP; }
    return BT_GATT_ITER_CONTINUE;
}
static uint8_t read_flag(struct bt_conn *conn, uint8_t error, struct bt_gatt_read_params *params,
    const void *data, uint16_t length)
{
    ARG_UNUSED(conn); ARG_UNUSED(params);
    if (error || !data) { finish_operation(error ? -EIO : 0); return BT_GATT_ITER_STOP; }
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool overflow = length > sizeof flag_bytes - flag_length;
    if (!overflow) { memcpy(flag_bytes + flag_length, data, length); flag_length += length; }
    k_spin_unlock(&lock, key);
    if (overflow) { finish_operation(-EOVERFLOW); return BT_GATT_ITER_STOP; }
    return BT_GATT_ITER_CONTINUE;
}
static uint8_t notified(struct bt_conn *conn, struct bt_gatt_subscribe_params *params,
    const void *data, uint16_t length)
{
    ARG_UNUSED(conn); ARG_UNUSED(params);
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool deliver = online && !stopping;
    if (!data) { subscribed = false; if (!stopping && !failure) failure = -ENOTCONN; }
    k_spin_unlock(&lock, key);
    if (!data) { kick(0); return BT_GATT_ITER_STOP; }
    if (deliver && callbacks.received) callbacks.received(data, length);
    return BT_GATT_ITER_CONTINUE;
}
static void subscription_done(struct bt_conn *conn, uint8_t error, struct bt_gatt_subscribe_params *params)
{ ARG_UNUSED(conn); ARG_UNUSED(params); finish_operation(error ? -EIO : 0); }
static void start_operation(void)
{
    int error = -EINVAL;
    if (stage == SERVICE || stage == CHARACTERISTICS || stage == CCC) {
        memset(&discovery, 0, sizeof discovery);
        discovery.func = discovered;
        discovery.type = stage == SERVICE ? BT_GATT_DISCOVER_PRIMARY : stage == CHARACTERISTICS ? BT_GATT_DISCOVER_CHARACTERISTIC : BT_GATT_DISCOVER_DESCRIPTOR;
        discovery.start_handle = stage == SERVICE ? BT_ATT_FIRST_ATTRIBUTE_HANDLE : stage == CCC ? rx_handle + 1 : first;
        discovery.end_handle = stage == SERVICE ? BT_ATT_LAST_ATTRIBUTE_HANDLE : stage == CCC ? ccc_end : last;
        discovery.uuid = stage == SERVICE ? &service_uuid.uuid : stage == CCC ? BT_UUID_GATT_CCC : NULL;
        error = bt_gatt_discover(connection, &discovery);
    } else if (stage == FLAG) {
        memset(&read_params, 0, sizeof read_params);
        read_params.func = read_flag; read_params.handle_count = 1;
        read_params.single.handle = flag_handle;
        error = bt_gatt_read(connection, &read_params);
    } else if (stage == SUBSCRIBE) {
        memset(&subscription, 0, sizeof subscription);
        subscription.value_handle = rx_handle; subscription.ccc_handle = ccc_handle;
        subscription.value = BT_GATT_CCC_NOTIFY; subscription.notify = notified;
        subscription.subscribe = subscription_done;
        atomic_set_bit(subscription.flags, BT_GATT_SUBSCRIBE_FLAG_VOLATILE);
        k_spinlock_key_t key = k_spin_lock(&lock); subscribed = true; k_spin_unlock(&lock, key);
        error = bt_gatt_subscribe(connection, &subscription);
        if (error) { key = k_spin_lock(&lock); subscribed = false; k_spin_unlock(&lock, key); }
    }
    if (error) finish_operation(error);
}
static void pump(struct k_work *work)
{
    ARG_UNUSED(work);
    uint32_t now = k_uptime_get_32();
    k_spinlock_key_t key = k_spin_lock(&lock);
    if (!connection) { k_spin_unlock(&lock, key); return; }
    if (done) { done = false; pending = false;
        if (!stopping && !failure) {
            if (stage == SERVICE && (!first || first > last)) failure = -ENOENT;
            else if (stage == CHARACTERISTICS && (!flag_handle || !rx_handle || !tx_handle || rx_handle >= ccc_end)) failure = -ENOENT;
            else if (stage == CCC && !ccc_handle) failure = -ENOENT;
            else if (stage == FLAG && !flag_length) failure = -EPROTO;
            else stage++;
        }
    }
    if (!stopping && ((pending && (int32_t)(now - deadline) >= 0) || failure)) {
        stopping = true; online = false; if (!failure) failure = -ETIMEDOUT;
    }
    bool close = stopping;
    bool cancel = close && pending && !cancelled;
    if (cancel) cancelled = true;
    bool unsubscribe = close && !pending && subscribed && !unsubscribing;
    if (unsubscribe) unsubscribing = true;
    bool release = close && !pending && !subscribed;
    bool start = !close && !pending && stage != ONLINE;
    if (start) { pending = true; deadline = now + 15000U; }
    bool ready = !close && stage == ONLINE && !online;
    if (ready) online = true;
    struct bt_conn *conn = connection;
    enum stage current = stage;
    int reason = failure;
    k_spin_unlock(&lock, key);
    if (cancel) bt_gatt_cancel(conn, current == FLAG ? (void *)&read_params : current == SUBSCRIBE ? (void *)&subscription : (void *)&discovery);
    if (unsubscribe) {
        int error = bt_gatt_unsubscribe(conn, &subscription);
        if (error) { key = k_spin_lock(&lock); unsubscribing = false; k_spin_unlock(&lock, key); }
    }
    if (release) {
        key = k_spin_lock(&lock);
        connection = NULL; stage = IDLE; online = false; tx_count = 0;
        memset(tx, 0, sizeof tx);
        k_spin_unlock(&lock, key);
        bt_conn_unref(conn);
        if (callbacks.closed) callbacks.closed(reason);
        LOG_INF("FE95 channel closed: %d", reason);
        return;
    }
    if (start) start_operation();
    if (ready) {
        LOG_INF("FE95 channel ready; flag length=%u, MTU=%u", (unsigned)flag_length, bt_gatt_get_mtu(conn));
        if (callbacks.ready) callbacks.ready(transport_generation);
    }
    /* Bounded fragmentation. Controller backpressure is retried without reordering. */
    for (unsigned i = 0; i < 4; ++i) {
        key = k_spin_lock(&lock);
        bool send = online && !stopping && tx_count;
        struct tx_frame *frame = &tx[tx_head];
        size_t length = send ? MIN(frame->length - frame->offset, (size_t)(bt_gatt_get_mtu(conn) - 3)) : 0;
        size_t offset = frame->offset;
        bool expired = send && (int32_t)(now - tx_deadline) >= 0;
        k_spin_unlock(&lock, key);
        if (!send) break;
        int error = expired ? -ETIMEDOUT : bt_gatt_write_without_response(conn, tx_handle, frame->bytes + offset, length, false);
        key = k_spin_lock(&lock);
        if (error && error != -ENOMEM && error != -EAGAIN) { failure = error; stopping = true; online = false; }
        if (!error) {
            frame->offset += length;
            if (frame->offset == frame->length) { memset(frame, 0, sizeof *frame); tx_head = (tx_head + 1) % ARRAY_SIZE(tx); --tx_count; tx_deadline = now + 4000U; }
        }
        k_spin_unlock(&lock, key);
        if (error) break;
    }
    kick(10);
}
void miwear_gatt_init(const struct miwear_gatt_callbacks *cb)
{
    if (initialized) return;
    if (cb) callbacks = *cb;
    k_work_queue_start(&queue, transport_stack, K_THREAD_STACK_SIZEOF(transport_stack), 8, NULL);
    k_work_init_delayable(&worker, pump); initialized = true;
}
int miwear_gatt_open(struct bt_conn *conn)
{
    if (!initialized || !conn) return -EINVAL;
    struct bt_conn_info info;
    if (bt_conn_get_info(conn, &info) || info.state != BT_CONN_STATE_CONNECTED) return -ENOTCONN;
    k_spinlock_key_t key = k_spin_lock(&lock);
    if (connection) { k_spin_unlock(&lock, key); return -EBUSY; }
    connection = bt_conn_ref(conn); stage = SERVICE; ++transport_generation;
    pending = done = stopping = cancelled = subscribed = unsubscribing = online = false;
    failure = 0; first = last = flag_handle = rx_handle = tx_handle = ccc_handle = 0;
    ccc_end = UINT16_MAX; flag_length = 0; tx_head = tx_count = 0;
    k_spin_unlock(&lock, key); kick(0); return 0;
}
void miwear_gatt_close(void)
{
    k_spinlock_key_t key = k_spin_lock(&lock); stopping = true; online = false; k_spin_unlock(&lock, key); kick(0);
}
bool miwear_gatt_busy(void)
{ k_spinlock_key_t key = k_spin_lock(&lock); bool busy = connection != NULL; k_spin_unlock(&lock, key); return busy; }
int miwear_gatt_send_for(uint32_t expected_generation, const uint8_t *bytes, size_t length)
{
    if (!bytes || !length || length > sizeof tx[0].bytes) return -EINVAL;
    k_spinlock_key_t key = k_spin_lock(&lock);
    int error = !online || stopping || expected_generation != transport_generation ? -ENOTCONN : tx_count == ARRAY_SIZE(tx) ? -EAGAIN : 0;
    if (!error) {
        struct tx_frame *frame = &tx[(tx_head + tx_count) % ARRAY_SIZE(tx)];
        memcpy(frame->bytes, bytes, length); frame->length = length; frame->offset = 0;
        if (!tx_count) tx_deadline = k_uptime_get_32() + 4000U;
        ++tx_count;
    }
    k_spin_unlock(&lock, key); if (!error) kick(0); return error;
}
int miwear_gatt_send(const uint8_t *bytes, size_t length)
{
    k_spinlock_key_t key = k_spin_lock(&lock); uint32_t current = transport_generation; k_spin_unlock(&lock, key);
    return miwear_gatt_send_for(current, bytes, length);
}
static void disconnected(struct bt_conn *conn, uint8_t reason)
{ ARG_UNUSED(reason); k_spinlock_key_t key = k_spin_lock(&lock); if (conn == connection) { failure = -ENOTCONN; stopping = true; online = false; } k_spin_unlock(&lock, key); kick(0); }
BT_CONN_CB_DEFINE(miwear_transport_callbacks) = {.disconnected = disconnected};
