#ifndef RADIO_TEST_CONN_H
#define RADIO_TEST_CONN_H
#include "bluetooth.h"
struct bt_conn { int id; };
#define BT_CONN_LE_CREATE_CONN NULL
#define BT_LE_CONN_PARAM_DEFAULT NULL
int bt_conn_le_create(const bt_addr_le_t *, const void *, const void *, struct bt_conn **);
#endif
