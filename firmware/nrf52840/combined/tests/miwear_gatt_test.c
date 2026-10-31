/* Deterministic GATT callback ordering/backpressure tests, not radio proof. */
#include <assert.h>
#include <string.h>
#include "../src/miwear_gatt.c"
static struct bt_conn watch = {.refs = 1, .state = BT_CONN_STATE_CONNECTED};
static uint32_t clock_ms;
static int discoveries, reads, subscriptions, unsubscribes, cancels, opened, closed_count, received_count, write_error;
static uint8_t sent[8192]; static size_t sent_length;
uint32_t k_uptime_get_32(void) { return clock_ms; }
int k_work_reschedule_for_queue(struct k_work_q *q, struct k_work_delayable *w, int delay)
{ (void)q; (void)w; (void)delay; return 0; }
struct bt_conn *bt_conn_ref(struct bt_conn *conn) { assert(conn == &watch && conn->refs); ++conn->refs; return conn; }
void bt_conn_unref(struct bt_conn *conn) { assert(conn == &watch && conn->refs); --conn->refs; }
int bt_conn_get_info(const struct bt_conn *conn, struct bt_conn_info *info) { info->state = conn->state; return 0; }
int bt_gatt_discover(struct bt_conn *conn, struct bt_gatt_discover_params *p)
{ assert(conn == &watch && p->func); ++discoveries; return 0; }
int bt_gatt_read(struct bt_conn *conn, struct bt_gatt_read_params *p)
{ assert(conn == &watch && p->single.handle == 0x14 && p->handle_count == 1); ++reads; return 0; }
int bt_gatt_subscribe(struct bt_conn *conn, struct bt_gatt_subscribe_params *p)
{ assert(conn == &watch && p->value_handle == 0x16 && p->ccc_handle == 0x17); ++subscriptions; return 0; }
int bt_gatt_unsubscribe(struct bt_conn *conn, struct bt_gatt_subscribe_params *p)
{ assert(conn == &watch && p == &subscription); ++unsubscribes; return 0; }
void bt_gatt_cancel(struct bt_conn *conn, void *p) { assert(conn == &watch && p); ++cancels; }
uint16_t bt_gatt_get_mtu(struct bt_conn *conn) { assert(conn == &watch); return 23; }
int bt_gatt_write_without_response(struct bt_conn *conn, uint16_t handle, const void *data, uint16_t length, bool sign)
{
    assert(conn == &watch && handle == 0x19 && length <= 20 && !sign);
    if (write_error) return write_error;
    assert(sent_length + length <= sizeof sent); memcpy(sent + sent_length, data, length); sent_length += length; return 0;
}
static void ready(uint32_t generation) { assert(generation); ++opened; }
static void closed(int error) { (void)error; ++closed_count; }
static void received(const uint8_t *bytes, size_t length) { assert(length == 3 && bytes[0] == 1); ++received_count; }
static void step(void) { pump(&worker.work); }
static void characteristic(uint16_t uuid, uint16_t handle, uint16_t value_handle, uint8_t properties)
{
    struct bt_uuid value = {.value = uuid};
    struct bt_gatt_chrc chrc = {.uuid = &value, .value_handle = value_handle, .properties = properties};
    struct bt_gatt_attr attr = {.handle = handle, .user_data = &chrc};
    assert(discovery.func(&watch, &attr, &discovery) == BT_GATT_ITER_CONTINUE);
}
static void bring_online(void)
{
    assert(!miwear_gatt_open(&watch)); step(); assert(stage == SERVICE);
    struct bt_gatt_service_val service = {.end_handle = 0x1a};
    struct bt_gatt_attr attr = {.handle = 0x12, .user_data = &service};
    assert(discovery.func(&watch, &attr, &discovery) == BT_GATT_ITER_STOP); step();
    assert(stage == CHARACTERISTICS && discovery.start_handle == 0x13 && discovery.end_handle == 0x1a);
    characteristic(0x50, 0x13, 0x14, BT_GATT_CHRC_READ);
    characteristic(0x5e, 0x15, 0x16, BT_GATT_CHRC_NOTIFY);
    characteristic(0x5f, 0x18, 0x19, BT_GATT_CHRC_WRITE_WITHOUT_RESP);
    discovery.func(&watch, NULL, &discovery); step();
    assert(stage == CCC && discovery.start_handle == 0x17 && discovery.end_handle == 0x17);
    attr.handle = 0x17; attr.user_data = NULL;
    discovery.func(&watch, &attr, &discovery); step(); assert(stage == FLAG);
    const uint8_t flag[] = {1,2};
    read_params.func(&watch, 0, &read_params, flag, sizeof flag);
    read_params.func(&watch, 0, &read_params, NULL, 0); step(); assert(stage == SUBSCRIBE);
    assert(!online); subscription.subscribe(&watch, 0, &subscription); step(); assert(online);
}
int main(void)
{
    const struct miwear_gatt_callbacks cb = {.ready = ready, .closed = closed, .received = received};
    miwear_gatt_init(&cb);
    assert(!miwear_gatt_busy()); bring_online(); assert(opened == 1 && watch.refs == 2);
    assert(miwear_gatt_open(&watch) == -EBUSY);
    const uint8_t stale[] = {1};
    assert(miwear_gatt_send_for(transport_generation - 1, stale, sizeof stale) == -ENOTCONN);
    uint8_t bytes[100]; for (size_t i = 0; i < sizeof bytes; ++i) bytes[i] = (uint8_t)i;
    for (int i = 0; i < 4; ++i) assert(!miwear_gatt_send(bytes, sizeof bytes));
    assert(miwear_gatt_send(bytes, sizeof bytes) == -EAGAIN);
    write_error = -ENOMEM; step(); assert(sent_length == 0 && tx_count == 4);
    write_error = 0; for (int i = 0; i < 6; ++i) step();
    assert(sent_length == 400 && !tx_count);
    for (int i = 0; i < 4; ++i) assert(!memcmp(sent + i * sizeof bytes, bytes, sizeof bytes));
    const uint8_t notice[] = {1,2,3}; subscription.notify(&watch, &subscription, notice, sizeof notice); assert(received_count == 1);
    miwear_gatt_close(); step(); assert(unsubscribes == 1 && miwear_gatt_busy());
    assert(miwear_gatt_send(bytes, sizeof bytes) == -ENOTCONN);
    step(); assert(unsubscribes == 1 && watch.refs == 2); // wait for NULL-data terminal callback
    subscription.notify(&watch, &subscription, NULL, 0); step(); assert(!miwear_gatt_busy() && watch.refs == 1);
    assert(closed_count == 1);
    assert(!miwear_gatt_open(&watch)); step(); miwear_gatt_close(); step();
    assert(cancels == 1 && miwear_gatt_busy()); step(); assert(cancels == 1);
    discovery.func(&watch, NULL, &discovery); step(); assert(!miwear_gatt_busy() && watch.refs == 1);
    // A missing required characteristic stops before read/subscribe.
    int old_reads = reads, old_subscriptions = subscriptions;
    assert(!miwear_gatt_open(&watch)); step();
    struct bt_gatt_service_val service = {.end_handle = 0x1a};
    struct bt_gatt_attr attr = {.handle = 0x12, .user_data = &service};
    discovery.func(&watch, &attr, &discovery); step(); discovery.func(&watch, NULL, &discovery); step();
    assert(!miwear_gatt_busy() && reads == old_reads && subscriptions == old_subscriptions);
    // Timeout cancels, but cannot recycle parameters before the cancellation callback.
    assert(!miwear_gatt_open(&watch)); step(); clock_ms = 15000; step(); assert(miwear_gatt_busy());
    assert(cancels == 2); discovery.func(&watch, NULL, &discovery); step(); assert(!miwear_gatt_busy());
    assert(watch.refs == 1);
    return 0;
}
