#ifndef RADIO_TEST_BLUETOOTH_H
#define RADIO_TEST_BLUETOOTH_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
typedef struct { uint8_t type, a[6]; } bt_addr_le_t;
#define BT_ADDR_LE_PUBLIC 0
#define BT_ADDR_LE_RANDOM 1
#define BT_ADDR_LE_STR_LEN 40
int bt_addr_from_str(const char *, void *);
int bt_addr_le_to_str(const bt_addr_le_t *, char *, size_t);
struct bt_data { uint8_t type, data_len; uint8_t *data; };
#define BT_DATA_NAME_COMPLETE 9
#define BT_DATA_NAME_SHORTENED 8
#define BT_DATA_UUID16_ALL 3
#define BT_DATA_UUID16_SOME 2
#define BT_DATA_SVC_DATA16 0x16
#define BT_DATA_MANUFACTURER_DATA 0xff
#define BT_DATA_UUID128_ALL 0x07
#define BT_DATA_UUID128_SOME 0x06
#define BT_DATA_SVC_DATA128 0x21
struct net_buf_simple { uint8_t *data; uint16_t len; };
struct net_buf_simple_state { uint8_t *data; uint16_t len; };
static inline void net_buf_simple_save(struct net_buf_simple *b, struct net_buf_simple_state *s)
{ s->data = b->data; s->len = b->len; }
static inline void net_buf_simple_restore(struct net_buf_simple *b, struct net_buf_simple_state *s)
{ b->data = s->data; b->len = s->len; }
struct bt_le_scan_recv_info { const bt_addr_le_t *addr; int8_t rssi; uint8_t adv_type; };
struct bt_le_scan_cb { void (*recv)(const struct bt_le_scan_recv_info *, struct net_buf_simple *); };
struct bt_le_scan_param { int type, interval, window; };
#define BT_LE_SCAN_TYPE_ACTIVE 1
#define BT_GAP_SCAN_FAST_INTERVAL 96
#define BT_GAP_SCAN_FAST_WINDOW 48
void bt_le_scan_cb_register(struct bt_le_scan_cb *);
int bt_le_scan_start(const struct bt_le_scan_param *, void *);
int bt_le_scan_stop(void);
void bt_data_parse(struct net_buf_simple *, bool (*)(struct bt_data *, void *), void *);
#endif
