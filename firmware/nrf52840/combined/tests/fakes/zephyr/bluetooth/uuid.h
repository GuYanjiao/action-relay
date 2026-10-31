#ifndef RADIO_TEST_UUID_H
#define RADIO_TEST_UUID_H
#include <stddef.h>
#include <stdint.h>
struct bt_uuid { uint16_t value; };
struct bt_uuid_16 { struct bt_uuid uuid; };
#define BT_UUID_INIT_16(v) {{v}}
#define BT_UUID_GATT_CCC (&(const struct bt_uuid){.value = 0x2902})
static inline int bt_uuid_cmp(const struct bt_uuid *a, const struct bt_uuid *b) { return (int)a->value - b->value; }
#define BT_UUID_STR_LEN 40
int bt_uuid_to_str(const struct bt_uuid *, char *, size_t);
#endif
