#ifndef ACTION_RELAY_S4_LINK_H
#define ACTION_RELAY_S4_LINK_H
#include <stdbool.h>
#include <zephyr/bluetooth/conn.h>
enum s4_client { S4_CLIENT_HEART, S4_CLIENT_COMPANION };
/* Caller already owns a connection reference. Register only after HCI success. */
int s4_link_adopt(struct bt_conn *, enum s4_client);
/* Acquires a NEW caller-owned reference and logical ownership, only when connected.
 * NULL address means reuse the explicitly selected S4; otherwise require a match. */
struct bt_conn *s4_link_acquire(enum s4_client, const bt_addr_le_t *);
/* Drop logical ownership, then caller drops its reference. Return true when another
 * owner still needs the link; in that case the caller must not disconnect it. */
bool s4_link_release(struct bt_conn *, enum s4_client);
#endif
