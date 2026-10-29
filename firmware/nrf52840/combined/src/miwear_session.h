#ifndef ACTION_RELAY_MIWEAR_SESSION_H
#define ACTION_RELAY_MIWEAR_SESSION_H
#include "../../protocol/miwear_auth.h"
#include <stdbool.h>
enum miwear_auth_state { MIWEAR_AUTH_IDLE, MIWEAR_AUTH_VERIFYING,
    MIWEAR_AUTH_CONFIRMING, MIWEAR_AUTH_READY, MIWEAR_AUTH_FAILED };
enum miwear_auth_event { MIWEAR_AUTH_ACCEPTED, MIWEAR_AUTH_REJECTED, MIWEAR_AUTH_RESET };
struct miwear_auth_session {
    enum miwear_auth_state state;
    struct miwear_keys keys;
    uint8_t authkey[16], random[16], pending[256];
    size_t pending_length;
    uint32_t deadline, companion_type;
    /* Copies a plaintext authentication PB message: 0 accepted, -2 busy, other error.
     * The transport must wrap it in the proper L2/SAR frame, not write raw PB. */
    int (*send_pb)(void *, const uint8_t *, size_t);
    void (*event)(void *, enum miwear_auth_event);
    void *context;
};
/* Serialize calls; callbacks must copy data and must not reenter the session. */
void miwear_auth_session_init(struct miwear_auth_session *,
    int (*)(void *, const uint8_t *, size_t), void (*)(void *, enum miwear_auth_event), void *);
/* Caller supplies a fresh CSPRNG nonce per attempt; no persistent key storage here. */
int miwear_auth_session_begin(struct miwear_auth_session *, const uint8_t key[16],
                               const uint8_t random[16], uint32_t companion_type, uint32_t now_ms);
void miwear_auth_session_poll(struct miwear_auth_session *, uint32_t now_ms);
void miwear_auth_session_receive(struct miwear_auth_session *, const uint8_t *, size_t, uint32_t now_ms);
void miwear_auth_session_close(struct miwear_auth_session *);
#endif
