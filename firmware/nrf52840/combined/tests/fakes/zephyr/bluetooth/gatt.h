#ifndef RADIO_TEST_GATT_H
#define RADIO_TEST_GATT_H
#include "conn.h"
#include "uuid.h"
#define BT_GATT_ITER_STOP 0
#define BT_GATT_ITER_CONTINUE 1
#define BT_GATT_DISCOVER_PRIMARY 1
#define BT_GATT_DISCOVER_CHARACTERISTIC 2
#define BT_GATT_DISCOVER_DESCRIPTOR 3
#define BT_GATT_CHRC_READ 0x02
#define BT_GATT_CHRC_NOTIFY 0x10
#define BT_GATT_CHRC_WRITE_WITHOUT_RESP 0x04
#define BT_GATT_CCC_NOTIFY 1
#define BT_GATT_SUBSCRIBE_FLAG_VOLATILE 0
#define BT_ATT_FIRST_ATTRIBUTE_HANDLE 1
#define BT_ATT_LAST_ATTRIBUTE_HANDLE 65535
struct bt_gatt_attr { void *user_data; uint16_t handle; };
struct bt_gatt_service_val { struct bt_uuid *uuid; uint16_t end_handle; };
struct bt_gatt_chrc { struct bt_uuid *uuid; uint16_t value_handle; uint8_t properties; };
struct bt_gatt_discover_params {
    const struct bt_uuid *uuid;
    uint16_t start_handle, end_handle;
    uint8_t type;
    uint8_t (*func)(struct bt_conn *, const struct bt_gatt_attr *, struct bt_gatt_discover_params *);
};
int bt_gatt_discover(struct bt_conn *, struct bt_gatt_discover_params *);
struct bt_gatt_read_params {
    uint8_t (*func)(struct bt_conn *, uint8_t, struct bt_gatt_read_params *, const void *, uint16_t);
    uint8_t handle_count;
    struct { uint16_t handle, offset; } single;
};
struct bt_gatt_subscribe_params {
    uint8_t (*notify)(struct bt_conn *, struct bt_gatt_subscribe_params *, const void *, uint16_t);
    void (*subscribe)(struct bt_conn *, uint8_t, struct bt_gatt_subscribe_params *);
    uint16_t value_handle, ccc_handle, value;
    int flags[1];
};
int bt_gatt_read(struct bt_conn *, struct bt_gatt_read_params *);
int bt_gatt_subscribe(struct bt_conn *, struct bt_gatt_subscribe_params *);
int bt_gatt_unsubscribe(struct bt_conn *, struct bt_gatt_subscribe_params *);
void bt_gatt_cancel(struct bt_conn *, void *);
uint16_t bt_gatt_get_mtu(struct bt_conn *);
int bt_gatt_write_without_response(struct bt_conn *, uint16_t, const void *, uint16_t, bool);
#endif
