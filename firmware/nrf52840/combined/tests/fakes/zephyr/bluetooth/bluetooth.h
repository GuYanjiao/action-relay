#ifndef RADIO_TEST_BLUETOOTH_H
#define RADIO_TEST_BLUETOOTH_H
#include <stdint.h>
typedef struct { uint8_t type, a[6]; } bt_addr_le_t;
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
#endif
