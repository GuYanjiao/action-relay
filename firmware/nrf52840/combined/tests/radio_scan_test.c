/* Exercise the real coordinator with controller/workqueue fakes in cloud CI.
 * This checks scheduling decisions, not BLE timing, radio coexistence or thread races. */
#include <assert.h>
#include <errno.h>
#include "../src/radio_scan.c"
static struct k_work_delayable *queued;
static struct bt_le_scan_cb *registered;
static int starts, stops, creates, create_error, start_error, failures;
static int heart_ads, camera_ads;
static struct bt_conn fake_connection = {.id = 1};
int k_work_reschedule(struct k_work_delayable *work, int delay)
{ (void)delay; queued = work; return 0; }
void bt_le_scan_cb_register(struct bt_le_scan_cb *callback) { registered = callback; }
int bt_le_scan_start(const struct bt_le_scan_param *params, void *callback)
{ assert(params->type == BT_LE_SCAN_TYPE_ACTIVE && !callback); starts++; return start_error; }
int bt_le_scan_stop(void) { stops++; return 0; }
int bt_conn_le_create(const bt_addr_le_t *address, const void *create,
                      const void *params, struct bt_conn **out)
{
    (void)address; (void)create; (void)params;
    creates++;
    if (!create_error) *out = &fake_connection;
    return create_error;
}
static void drain(void)
{
    for (int i = 0; queued; ++i) {
        assert(i < 10);
        struct k_work_delayable *work = queued; queued = NULL;
        work->work.handler(&work->work);
    }
}
static void failed(int error) { assert(error == -ECONNREFUSED); failures++; }
static void heart_ad(const bt_addr_le_t *a, int8_t rssi, uint8_t type, struct net_buf_simple *b)
{ (void)a; (void)rssi; (void)type; assert(b->len == 3); heart_ads++; b->len = 0; }
static void camera_ad(const bt_addr_le_t *a, int8_t rssi, uint8_t type, struct net_buf_simple *b)
{ (void)a; (void)rssi; (void)type; assert(b->len == 3); camera_ads++; b->len = 0; }
int main(void)
{
    bt_addr_le_t address = {0}; struct bt_conn *heart = NULL, *camera = NULL;
    radio_scan_init(); assert(registered);
    radio_scan_register(RADIO_HEART, heart_ad);
    radio_scan_register(RADIO_CAMERA, camera_ad);
    radio_scan_request(RADIO_HEART, true);
    radio_scan_request(RADIO_CAMERA, true);
    drain(); assert(starts == 1 && stops == 0);
    radio_scan_request(RADIO_HEART, false);
    drain(); assert(stops == 0); /* heart standby must preserve camera scanning */
    uint8_t bytes[3] = {0}; struct net_buf_simple buffer = {bytes, 3};
    struct bt_le_scan_recv_info info = {.addr = &address};
    registered->recv(&info, &buffer);
    assert(heart_ads == 0 && camera_ads == 1 && buffer.len == 3);
    radio_scan_request(RADIO_HEART, true); drain();
    registered->recv(&info, &buffer);
    assert(heart_ads == 1 && camera_ads == 2 && buffer.len == 3);
    radio_scan_request(RADIO_HEART, false);
    assert(radio_scan_connect(RADIO_HEART, &address, &heart, failed) == 0);
    assert(radio_scan_connect(RADIO_CAMERA, &address, &camera, failed) == -EBUSY);
    drain(); assert(stops == 1 && creates == 1 && heart && !camera);
    radio_scan_connected(RADIO_CAMERA); drain(); assert(starts == 1);
    radio_scan_connected(RADIO_HEART); drain(); assert(starts == 2);
    create_error = -ECONNREFUSED;
    assert(radio_scan_connect(RADIO_CAMERA, &address, &camera, failed) == 0);
    drain(); assert(failures == 1 && creates == 2 && starts == 3 && !camera);
    radio_scan_request(RADIO_CAMERA, false); drain(); assert(stops == 3);
    radio_scan_request(RADIO_CAMERA, false); drain(); assert(stops == 3);
    assert(radio_scan_connect(RADIO_CLIENT_COUNT, &address, &camera, failed) == -EINVAL);
    assert(radio_scan_connect(RADIO_CAMERA, NULL, &camera, failed) == -EINVAL);
    /* A transient controller scan error queues a retry rather than losing demand. */
    start_error = -EIO;
    radio_scan_request(RADIO_WATCH, true);
    struct k_work_delayable *work = queued; queued = NULL;
    work->work.handler(&work->work); assert(queued && !running);
    start_error = 0; drain(); assert(running);
    radio_scan_request(RADIO_WATCH, false); drain(); assert(!running);
    return 0;
}
