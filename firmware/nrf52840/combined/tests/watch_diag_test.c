/* Tests command boundaries and owner isolation, not live FE95 compatibility. */
#include <assert.h>
#include "../src/watch_diag.c"
static int scan_requests, connection_requests, released, disconnects, discoveries, finished;
static bool scan_wanted;
static struct bt_conn owned = {.id = 1, .state = BT_CONN_STATE_CONNECTED}, foreign = {.id = 2};
static struct bt_gatt_discover_params *last_discovery;
void radio_scan_register(enum radio_client client, radio_advert_fn callback)
{ assert(client == RADIO_WATCH && callback); }
void radio_scan_request(enum radio_client client, bool wanted)
{ assert(client == RADIO_WATCH); scan_requests++; scan_wanted = wanted; }
int radio_scan_connect(enum radio_client client, const bt_addr_le_t *address,
                       struct bt_conn **out, void (*failed)(int))
{ assert(client == RADIO_WATCH && address->type == BT_ADDR_LE_RANDOM && failed); assert(!owned.refs); owned.refs = 1; owned.address = *address; connection_requests++; *out = &owned; return 0; }
void radio_scan_connected(enum radio_client client) { assert(client == RADIO_WATCH); finished++; }
int bt_addr_from_str(const char *text, void *address)
{ (void)address; return strcmp(text, "AA:BB:CC:DD:EE:FF") ? -EINVAL : 0; }
int bt_addr_le_to_str(const bt_addr_le_t *address, char *text, size_t size)
{ (void)address; if (size) text[0] = 0; return 0; }
void bt_data_parse(struct net_buf_simple *b, bool (*callback)(struct bt_data *, void *), void *context)
{ (void)b; (void)callback; (void)context; }
int bt_uuid_to_str(const struct bt_uuid *uuid, char *text, size_t size)
{ (void)uuid; if (size) text[0] = 0; return 0; }
struct bt_conn *bt_conn_ref(struct bt_conn *conn) { assert(conn == &owned && conn->refs); ++conn->refs; return conn; }
void bt_conn_unref(struct bt_conn *conn) { assert(conn == &owned && conn->refs); --conn->refs; released++; }
int bt_conn_get_info(const struct bt_conn *conn, struct bt_conn_info *info) { info->state = conn->state; return 0; }
const bt_addr_le_t *bt_conn_get_dst(const struct bt_conn *conn) { return &conn->address; }
int bt_addr_le_cmp(const bt_addr_le_t *a, const bt_addr_le_t *b) { return memcmp(a, b, sizeof *a); }
int bt_conn_disconnect(struct bt_conn *conn, uint8_t reason)
{ assert(conn == &owned && reason == BT_HCI_ERR_REMOTE_USER_TERM_CONN); disconnects++; return 0; }
int bt_gatt_discover(struct bt_conn *conn, struct bt_gatt_discover_params *params)
{ assert(conn == &owned); discoveries++; last_discovery = params; return 0; }
static bool command(const char *text)
{ char line[128]; assert(strlen(text) < sizeof line); strcpy(line, text); return watch_diag_command(line); }
int main(void)
{
    uint8_t service_bytes[] = {0x95, 0xfe, 0x00};
    struct bt_data service = {.type = BT_DATA_SVC_DATA16, .data_len = 3, .data = service_bytes};
    struct advert advert = {0};
    field(&service, &advert); assert(advert.xiaomi);
    advert.xiaomi = false; service.data_len = 1;
    field(&service, &advert); assert(!advert.xiaomi && !advert.heart);
    service.data_len = 3; service_bytes[0] = 0x0d; service_bytes[1] = 0x18;
    field(&service, &advert); assert(!advert.xiaomi && advert.heart);
    assert(command("watch scan") && scan_requests == 0);
    watch_diag_init();
    assert(!command("other scan"));
    assert(command("watch scan") && scan_requests == 1 && scan_wanted);
    assert(command("watch connect invalid random") && connection_requests == 0);
    assert(command("watch connect AA:BB:CC:DD:EE:FF invalid") && connection_requests == 0);
    assert(command("watch connect AA:BB:CC:DD:EE:FF random") && connection_requests == 1 && !scan_wanted);
    assert(command("watch connect AA:BB:CC:DD:EE:FF random") && connection_requests == 1);
    watch_diagnostic_callbacks.connected(&foreign, 0); assert(finished == 0);
    watch_diagnostic_callbacks.connected(&owned, 0); assert(finished == 1);
    assert(command("watch services") && discoveries == 1);
    assert(command("watch chars") && discoveries == 1); /* no discovery overwrite */
    last_discovery->func(&owned, NULL, last_discovery);
    assert(command("watch chars") && discoveries == 2);
    assert(command("watch disconnect") && disconnects == 0); /* discovery still borrows its parameters */
    last_discovery->func(&owned, NULL, last_discovery);
    watch_diagnostic_callbacks.disconnected(&foreign, 0); assert(owned.refs == 2);
    assert(command("watch disconnect") && disconnects == 1);
    watch_diagnostic_callbacks.disconnected(&owned, 0); assert(owned.refs == 0);
    assert(command("watch chars") && discoveries == 2);
    assert(command("watch connect AA:BB:CC:DD:EE:FF random") && connection_requests == 2);
    watch_diagnostic_callbacks.connected(&owned, 0x3e); assert(owned.refs == 0 && finished == 2);
    assert(command("watch stop") && !scan_wanted);
    /* Reuse the HRS connection, and detach without a physical disconnect. */
    owned.refs = 1;
    assert(!s4_link_adopt(&owned, S4_CLIENT_HEART));
    assert(command("watch attach") && watch_peer == &owned && owned.refs == 3);
    assert(connection_requests == 2);
    assert(command("watch services"));
    assert(command("watch disconnect") && watch_peer == &owned && disconnects == 1); // busy discovery
    last_discovery->func(&owned, NULL, last_discovery);
    assert(command("watch disconnect") && watch_peer == NULL && disconnects == 1 && owned.refs == 2);
    assert(!s4_link_release(&owned, S4_CLIENT_HEART)); bt_conn_unref(&owned);
    assert(owned.refs == 0);
    return 0;
}
