#ifndef RADIO_TEST_UUID_H
#define RADIO_TEST_UUID_H
#include <stddef.h>
#include <stdint.h>
struct bt_uuid { uint16_t value; };
#define BT_UUID_STR_LEN 40
int bt_uuid_to_str(const struct bt_uuid *, char *, size_t);
#endif
