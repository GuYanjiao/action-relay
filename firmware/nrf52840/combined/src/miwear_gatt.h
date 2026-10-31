#ifndef ACTION_RELAY_MIWEAR_GATT_H
#define ACTION_RELAY_MIWEAR_GATT_H
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <zephyr/bluetooth/conn.h>
struct miwear_gatt_callbacks {
    /* RX callback runs on the Bluetooth thread: copy/enqueue, never do crypto. */
    void (*received)(const uint8_t *, size_t);
    /* Ready/closed run on the private transport queue. */
    void (*ready)(uint32_t generation);
    void (*closed)(int);
};
void miwear_gatt_init(const struct miwear_gatt_callbacks *);
int miwear_gatt_open(struct bt_conn *);
void miwear_gatt_close(void);
bool miwear_gatt_busy(void);
/* Copies one whole L1 frame. 0 queued, -EAGAIN full; never camera confirmation. */
int miwear_gatt_send(const uint8_t *, size_t);
int miwear_gatt_send_for(uint32_t generation, const uint8_t *, size_t);
#endif
