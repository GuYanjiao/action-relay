/* Tests command boundaries and owner isolation, not live FE95 compatibility. */
#include <assert.h>
#include "../src/watch_diag.c"
static int scan_requests, connection_requests, released, disconnects, discoveries, finished;
static bool scan_wanted;
static struct bt_conn owned = {1}, foreign = {2};
static struct bt_gatt_discover_params *last_discovery;
void radio_scan_register(enum radio_client client, radio_advert_fn callback)
{ assert(client == RADIO_WATCH && callback); }
void radio_scan_request(enum radio_client client, bool wanted)
{ assert(client == RADIO_WATCH); scan_requests++; scan_wanted = wanted; }
int radio_scan_connect(enum radio_client client, const bt_addr_le_t *address,
                       struct bt_conn **out, void (*failed)(int))
{ assert(client == RADIO_WATCH && address->type == BT_ADDR_LE_RANDOM && failed); connection_requests++; *out = &owned; return 0; }
void radio_scan_connected(enum radio_client client) { assert(client == RADIO_WATCH); finished++; }
int bt_addr_from_str(const char *text, void *address)
{ (void)address; return strcmp(text, "AA:BB:CC:DD:EE:FF") ? -EINVAL : 0; }
int bt_addr_le_to_str(const bt_addr_le_t *address, char *text, size_t size)
{ (void)address; if (size) text[0] = 0; return 0; }
void bt_data_parse(struct net_buf_simple *b, bool (*callback)(struct bt_data *, void *), void *context)
{ (void)b; (void)callback; (void)context; }
int bt_uuid_to_str(const struct bt_uuid *uuid, char *text, size_t size)
{ (void)uuid; if (size) text[0] = 0; return 0; }
void bt_conn_unref(struct bt_conn *conn) { assert(conn == &owned); released++; }
int bt_conn_disconnect(struct bt_conn *conn, uint8_t reason)
{ assert(conn == &owned && reason == BT_HCI_ERR_REMOTE_USER_TERM_CONN); disconnects++; return 0; }
int bt_gatt_discover(struct bt_conn *conn, struct bt_gatt_discover_params *params)
{ assert(conn == &owned); discoveries++; last_discovery = params; return 0; }
static bool command(const char *text)
{ char line[128]; assert(strlen(text) < sizeof line); strcpy(line, text); return watch_diag_command(line); }
int main(void)
{
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
    watch_diagnostic_callbacks.disconnected(&foreign, 0); assert(released == 0);
    assert(command("watch disconnect") && disconnects == 1);
    watch_diagnostic_callbacks.disconnected(&owned, 0); assert(released == 1);
    assert(command("watch chars") && discoveries == 2);
    assert(command("watch connect AA:BB:CC:DD:EE:FF random") && connection_requests == 2);
    watch_diagnostic_callbacks.connected(&owned, 0x3e); assert(released == 2 && finished == 2);
    assert(command("watch stop") && !scan_wanted);
    return 0;
}
