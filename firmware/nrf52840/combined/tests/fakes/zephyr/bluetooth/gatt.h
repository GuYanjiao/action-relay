#ifndef RADIO_TEST_GATT_H
#define RADIO_TEST_GATT_H
#include "conn.h"
#include "uuid.h"
#define BT_GATT_ITER_STOP 0
#define BT_GATT_ITER_CONTINUE 1
#define BT_GATT_DISCOVER_PRIMARY 1
#define BT_GATT_DISCOVER_CHARACTERISTIC 2
#define BT_ATT_FIRST_ATTRIBUTE_HANDLE 1
#define BT_ATT_LAST_ATTRIBUTE_HANDLE 65535
struct bt_gatt_attr { void *user_data; uint16_t handle; };
struct bt_gatt_service_val { struct bt_uuid *uuid; uint16_t end_handle; };
struct bt_gatt_chrc { struct bt_uuid *uuid; uint16_t value_handle; uint8_t properties; };
struct bt_gatt_discover_params {
    uint16_t start_handle, end_handle;
    uint8_t type;
    uint8_t (*func)(struct bt_conn *, const struct bt_gatt_attr *, struct bt_gatt_discover_params *);
};
int bt_gatt_discover(struct bt_conn *, struct bt_gatt_discover_params *);
#endif
