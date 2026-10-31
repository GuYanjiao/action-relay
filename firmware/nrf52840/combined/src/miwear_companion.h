#ifndef ACTION_RELAY_MIWEAR_COMPANION_H
#define ACTION_RELAY_MIWEAR_COMPANION_H
#include "miwear_session.h"
#include "../../protocol/miwear_sar.h"
enum miwear_companion_state { MIWEAR_COMPANION_IDLE, MIWEAR_COMPANION_SAR,
    MIWEAR_COMPANION_AUTH, MIWEAR_COMPANION_READY, MIWEAR_COMPANION_FAILED };
struct miwear_companion_ops {
    int (*send)(void *, const uint8_t *, size_t);
    void (*state)(void *, enum miwear_companion_state);
    /* Decrypted application PB; caller must still validate package/fingerprint/JSON. */
    void (*application)(void *, const uint8_t *, size_t);
    void *context;
};
struct miwear_companion {
    struct miwear_companion_ops ops;
    struct miwear_sar sar;
    struct miwear_auth_session auth;
    struct miwear_l1_stream stream;
    enum miwear_companion_state state;
    uint8_t key[16], nonce[16];
    uint8_t incoming[1024], outgoing[1024], rx[2][1024];
    size_t rx_length[2];
    unsigned rx_head, rx_count, events;
    uint32_t now;
    bool overflow;
};
/* Serialize all calls on one thread; callbacks must copy and must not reenter. */
void miwear_companion_init(struct miwear_companion *, const struct miwear_companion_ops *);
int miwear_companion_start(struct miwear_companion *, const uint8_t key[16], const uint8_t fresh_nonce[16], uint32_t);
void miwear_companion_receive(struct miwear_companion *, const uint8_t *, size_t, uint32_t);
void miwear_companion_poll(struct miwear_companion *, uint32_t);
int miwear_companion_send_pb(struct miwear_companion *, const uint8_t *, size_t, uint32_t);
void miwear_companion_close(struct miwear_companion *);
#endif
