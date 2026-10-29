#ifndef ACTION_RELAY_RADIO_SCAN_H
#define ACTION_RELAY_RADIO_SCAN_H
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>

enum radio_client { RADIO_HEART, RADIO_WATCH, RADIO_CAMERA, RADIO_CLIENT_COUNT };
typedef void (*radio_advert_fn)(const bt_addr_le_t *, int8_t, uint8_t, struct net_buf_simple *);
/* Call once, after bt_enable, before any request. Callbacks do not own the buffer. */
void radio_scan_init(void);
void radio_scan_register(enum radio_client client, radio_advert_fn callback);
void radio_scan_request(enum radio_client client, bool enabled);
/* One bounded initiation at a time. Error callback runs if queued creation fails.
 * Each owner's connected callback must call radio_scan_connected, even on HCI error. */
int radio_scan_connect(enum radio_client client, const bt_addr_le_t *address,
                       struct bt_conn **connection, void (*failed)(int));
void radio_scan_connected(enum radio_client client);
#endif
