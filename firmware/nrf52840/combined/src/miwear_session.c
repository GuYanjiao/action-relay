/* Existing-device authentication only; no account/bond changes or application sends. */
#include <string.h>
#include "miwear_session.h"
#include "miwear_pb.h"
#include "../../protocol/miwear_psa.h"
#define AUTH_TIMEOUT_MS 20000U
static void wipe(void *data, size_t size)
{ volatile uint8_t *bytes = data; while (size--) *bytes++ = 0; }
static void clear(struct miwear_auth_session *s)
{
    wipe(&s->keys, sizeof s->keys); wipe(s->authkey, 16); wipe(s->random, 16);
    wipe(s->pending, sizeof s->pending); s->pending_length = 0; s->deadline = 0;
}
static void notify(struct miwear_auth_session *s, enum miwear_auth_event event)
{ if (s->event) s->event(s->context, event); }
static void reject(struct miwear_auth_session *s)
{ clear(s); s->state = MIWEAR_AUTH_FAILED; notify(s, MIWEAR_AUTH_REJECTED); }
void miwear_auth_session_init(struct miwear_auth_session *s,
    int (*send)(void *, const uint8_t *, size_t), void (*event)(void *, enum miwear_auth_event), void *context)
{ memset(s, 0, sizeof *s); s->send_pb = send; s->event = event; s->context = context; }
void miwear_auth_session_poll(struct miwear_auth_session *s, uint32_t now)
{
    if (!s || (s->state != MIWEAR_AUTH_VERIFYING && s->state != MIWEAR_AUTH_CONFIRMING)) return;
    if ((int32_t)(now - s->deadline) >= 0) { reject(s); return; }
    if (s->pending_length) {
        int error = s->send_pb ? s->send_pb(s->context, s->pending, s->pending_length) : -1;
        if (error == -2) return;
        if (error) { reject(s); return; }
        wipe(s->pending, sizeof s->pending); s->pending_length = 0;
    }
}
int miwear_auth_session_begin(struct miwear_auth_session *s, const uint8_t key[16],
                               const uint8_t random[16], uint32_t type, uint32_t now)
{
    if (!s || !key || !random || !s->send_pb || (type > 2 && type != 15) ||
        s->state == MIWEAR_AUTH_VERIFYING || s->state == MIWEAR_AUTH_CONFIRMING || s->state == MIWEAR_AUTH_READY) return -1;
    clear(s); memcpy(s->authkey, key, 16); memcpy(s->random, random, 16);
    s->companion_type = type;
    s->pending_length = miwear_pb_verify(s->pending, sizeof s->pending, random);
    if (!s->pending_length) { reject(s); return -1; }
    s->state = MIWEAR_AUTH_VERIFYING; s->deadline = now + AUTH_TIMEOUT_MS;
    miwear_auth_session_poll(s, now);
    return s->state == MIWEAR_AUTH_FAILED ? -1 : 0;
}
static bool prepare_confirm(struct miwear_auth_session *s, const uint8_t *packet, size_t length)
{
    uint8_t watch[16], signature[32], proof[32], companion[64], encrypted[68], nonce[12] = {0};
    bool ok = miwear_pb_device_verify(packet, length, watch, signature) &&
              miwear_derive(miwear_psa_hmac, NULL, s->authkey, s->random, watch, &s->keys) == 0 &&
              miwear_verify_watch(miwear_psa_hmac, NULL, &s->keys, s->random, watch, signature) == 0 &&
              miwear_phone_proof(miwear_psa_hmac, NULL, &s->keys, s->random, watch, proof) == 0;
    wipe(s->authkey, 16);
    size_t plain_length = 0, encrypted_length = 0;
    if (ok) {
        plain_length = miwear_pb_companion(companion, sizeof companion, s->companion_type, "Action Relay", false, 0);
        memcpy(nonce, s->keys.tx_nonce, 4);
        ok = plain_length && miwear_psa_ccm(true, s->keys.tx_key, nonce, companion, plain_length,
                                           encrypted, sizeof encrypted, &encrypted_length) == 0;
    }
    if (ok) {
        s->pending_length = miwear_pb_confirm(s->pending, sizeof s->pending, proof, encrypted, encrypted_length);
        ok = s->pending_length != 0;
    }
    wipe(watch, sizeof watch); wipe(signature, sizeof signature); wipe(proof, sizeof proof);
    wipe(companion, sizeof companion); wipe(encrypted, sizeof encrypted); wipe(nonce, sizeof nonce);
    return ok;
}
void miwear_auth_session_receive(struct miwear_auth_session *s, const uint8_t *packet, size_t length, uint32_t now)
{
    if (!s || s->state == MIWEAR_AUTH_IDLE || s->state == MIWEAR_AUTH_FAILED) return;
    enum miwear_pb_auth_kind kind = miwear_pb_auth_kind(packet, length);
    if (kind == MIWEAR_PB_OTHER) return;
    if (s->state == MIWEAR_AUTH_READY) {
        if (kind == MIWEAR_PB_DEVICE_VERIFY || kind == MIWEAR_PB_ACCOUNT_ERROR) reject(s);
        return;
    }
    if ((int32_t)(now - s->deadline) >= 0 || kind == MIWEAR_PB_MALFORMED || kind == MIWEAR_PB_ACCOUNT_ERROR) {
        reject(s); return;
    }
    /* A response cannot complete a message the transport has not accepted yet. */
    if (s->pending_length) return;
    if (s->state == MIWEAR_AUTH_VERIFYING && kind == MIWEAR_PB_DEVICE_VERIFY) {
        if (!prepare_confirm(s, packet, length)) { reject(s); return; }
        s->state = MIWEAR_AUTH_CONFIRMING; s->deadline = now + AUTH_TIMEOUT_MS;
        miwear_auth_session_poll(s, now);
    } else if (s->state == MIWEAR_AUTH_CONFIRMING && kind == MIWEAR_PB_DEVICE_CONFIRM) {
        if (!miwear_pb_device_confirm(packet, length)) { reject(s); return; }
        wipe(s->random, 16); s->state = MIWEAR_AUTH_READY; notify(s, MIWEAR_AUTH_ACCEPTED);
    }
}
void miwear_auth_session_close(struct miwear_auth_session *s)
{ if (s) { clear(s); s->state = MIWEAR_AUTH_IDLE; notify(s, MIWEAR_AUTH_RESET); } }
