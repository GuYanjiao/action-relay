#include <assert.h>
#include <string.h>
#include "../src/camera_diag.c"
static int requests;
static bool wanted;
static struct bt_data fields[2];
void radio_scan_register(enum radio_client client, radio_advert_fn callback)
{ assert(client == RADIO_CAMERA && callback == advertisement); }
void radio_scan_request(enum radio_client client, bool enable)
{ assert(client == RADIO_CAMERA); ++requests; wanted = enable; }
void bt_data_parse(struct net_buf_simple *buf, bool (*callback)(struct bt_data *, void *), void *context)
{ (void)buf; for (size_t i = 0; i < 2; ++i) callback(&fields[i], context); }
int bt_addr_le_cmp(const bt_addr_le_t *a, const bt_addr_le_t *b)
{ return memcmp(a, b, sizeof *a); }
int bt_addr_le_to_str(const bt_addr_le_t *a, char *text, size_t size)
{ (void)a; if (size) text[0] = 0; return 0; }
int main(void)
{
    uint8_t manufacturer[] = {0xaa, 0x08, 0, 0, 0xfa};
    fields[0] = (struct bt_data){.type = BT_DATA_MANUFACTURER_DATA, .data_len = 5, .data = manufacturer};
    uint8_t name[] = {'A', '\n', '4'};
    fields[1] = (struct bt_data){.type = BT_DATA_NAME_COMPLETE, .data_len = 3, .data = name};
    struct advert parsed = {0}; field(&fields[0], &parsed); field(&fields[1], &parsed);
    assert(parsed.dji && !strcmp(parsed.name, "A?4"));
    for (unsigned n = 0; n < 5; ++n) { parsed.dji = false; fields[0].data_len = n; field(&fields[0], &parsed); assert(!parsed.dji); }
    fields[0].data_len = 5; manufacturer[4] = 0; parsed.dji = false;
    field(&fields[0], &parsed); assert(!parsed.dji); manufacturer[4] = 0xfa;
    camera_diag_init();assert(!camera_diag_command("camera connect", 0));assert(!requests);
    assert(camera_diag_command("camera scan", 100));assert(wanted && requests == 1);
    bt_addr_le_t address = {.type = BT_ADDR_LE_RANDOM}; struct net_buf_simple ad = {0};
    advertisement(&address, -50, 0, &ad); advertisement(&address, -49, 0, &ad);
    assert(seen_count == 1);
    for (uint8_t i = 1; i < 16; ++i) { address.a[0] = i; advertisement(&address, -50, 0, &ad); }
    assert(seen_count == 8);
    camera_diag_poll(10099);assert(wanted && requests == 1);
    camera_diag_poll(10100);assert(!wanted && requests == 2);
    camera_diag_poll(10101);assert(requests == 2);
    assert(camera_diag_command("camera scan", UINT32_MAX - 5000));assert(seen_count == 0);
    camera_diag_poll(UINT32_MAX);assert(wanted);
    camera_diag_poll(4999);assert(!wanted);
    assert(camera_diag_command("camera scan", 0));
    assert(camera_diag_command("camera stop", 1));assert(!wanted);
    camera_diag_poll(20000);assert(!wanted);
    return 0;
}
