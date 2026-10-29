#ifndef RADIO_TEST_CONN_H
#define RADIO_TEST_CONN_H
#include "bluetooth.h"
enum bt_conn_state { BT_CONN_STATE_CONNECTING, BT_CONN_STATE_CONNECTED, BT_CONN_STATE_DISCONNECTED };
struct bt_conn { int id; enum bt_conn_state state; unsigned refs; bt_addr_le_t address; };
struct bt_conn_info { enum bt_conn_state state; };
struct bt_conn *bt_conn_ref(struct bt_conn *);
int bt_conn_get_info(const struct bt_conn *, struct bt_conn_info *);
const bt_addr_le_t *bt_conn_get_dst(const struct bt_conn *);
int bt_addr_le_cmp(const bt_addr_le_t *, const bt_addr_le_t *);
struct bt_conn_cb { void (*connected)(struct bt_conn *, uint8_t); void (*disconnected)(struct bt_conn *, uint8_t); };
#define BT_CONN_CB_DEFINE(name) struct bt_conn_cb name
#define BT_CONN_LE_CREATE_CONN NULL
#define BT_LE_CONN_PARAM_DEFAULT NULL
int bt_conn_le_create(const bt_addr_le_t *, const void *, const void *, struct bt_conn **);
void bt_conn_unref(struct bt_conn *);
int bt_conn_disconnect(struct bt_conn *, uint8_t);
#endif
