#ifndef ACTION_RELAY_MIWEAR_L1_H
#define ACTION_RELAY_MIWEAR_L1_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
/* MCU allocation limit, not a claim about the peer's maximum frame size. */
#define MIWEAR_L1_MAX_PAYLOAD 1024
#define MIWEAR_L1_HEADER 8
enum miwear_l1_type { MIWEAR_NAK, MIWEAR_ACK, MIWEAR_COMMAND, MIWEAR_DATA };
struct miwear_l1_view {
    uint8_t type, sequence;
    bool frx;
    const uint8_t *payload;
    size_t length;
};
struct miwear_l1_stream {
    uint8_t bytes[MIWEAR_L1_HEADER + MIWEAR_L1_MAX_PAYLOAD];
    size_t used;
};
typedef void (*miwear_l1_received)(void *, const struct miwear_l1_view *);
uint16_t miwear_crc16_arc(const uint8_t *data, size_t length);
size_t miwear_l1_encode(uint8_t *out, size_t capacity, uint8_t type, bool frx,
                        uint8_t sequence, const uint8_t *payload, size_t length);
/* Exact-frame parser; the returned payload borrows the input buffer. */
int miwear_l1_parse(const uint8_t *data, size_t length, struct miwear_l1_view *out);
/* Zero initialize/reset per connection. Callbacks borrow storage only for the call;
 * do not re-enter feed from a callback. No ACK, retransmit or auth is performed. */
void miwear_l1_feed(struct miwear_l1_stream *, const uint8_t *, size_t,
                     miwear_l1_received, void *);
#endif
