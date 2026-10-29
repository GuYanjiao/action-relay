#ifndef RADIO_TEST_CONN_H
#define RADIO_TEST_CONN_H
#include "bluetooth.h"
struct bt_conn { int id; };
struct bt_conn_cb { void (*connected)(struct bt_conn *, uint8_t); void (*disconnected)(struct bt_conn *, uint8_t); };
#define BT_CONN_CB_DEFINE(name) struct bt_conn_cb name
#define BT_CONN_LE_CREATE_CONN NULL
#define BT_LE_CONN_PARAM_DEFAULT NULL
int bt_conn_le_create(const bt_addr_le_t *, const void *, const void *, struct bt_conn **);
void bt_conn_unref(struct bt_conn *);
int bt_conn_disconnect(struct bt_conn *, uint8_t);
#endif
