/* Serial integration of tested framing/SAR/L2/auth. No pairing or key storage. */
#include <string.h>
#include "miwear_companion.h"
#include "miwear_l2.h"
enum { OPENED = 1, RESET = 2, FAILED = 4, VERIFIED = 8 };
static void wipe(void *data, size_t length) { volatile uint8_t *p = data; while (length--) *p++ = 0; }
static int send_wire(void *context, const uint8_t *bytes, size_t length)
{ struct miwear_companion *s = context; return s->ops.send ? s->ops.send(s->ops.context, bytes, length) : -1; }
static void sar_event(void *context, enum miwear_sar_event event)
{
    struct miwear_companion *s = context;
    if (event == MIWEAR_SAR_OPENED) s->events |= OPENED;
    if (event == MIWEAR_SAR_RESET) s->events |= RESET;
    if (event == MIWEAR_SAR_ERROR) s->events |= FAILED;
}
static void auth_event(void *context, enum miwear_auth_event event)
{
    struct miwear_companion *s = context;
    if (event == MIWEAR_AUTH_ACCEPTED) s->events |= VERIFIED;
    if (event == MIWEAR_AUTH_REJECTED) s->events |= FAILED;
}
static void sar_data(void *context, const uint8_t *bytes, size_t length)
{
    struct miwear_companion *s = context;
    if (s->rx_count == 2 || length > sizeof s->rx[0]) { s->overflow = true; return; }
    unsigned slot = (s->rx_head + s->rx_count) % 2;
    memcpy(s->rx[slot], bytes, length); s->rx_length[slot] = length; ++s->rx_count;
}
static void frame(void *context, const struct miwear_l1_view *packet)
{ struct miwear_companion *s = context; miwear_sar_receive(&s->sar, packet, s->now); }
static int send_auth(void *context, const uint8_t *bytes, size_t length)
{
    struct miwear_companion *s = context;
    size_t size = miwear_l2_auth_write(s->outgoing, sizeof s->outgoing, bytes, length);
    return size ? miwear_sar_send(&s->sar, s->outgoing, size, s->now) : -1;
}
static void state(struct miwear_companion *s, enum miwear_companion_state value)
{ s->state = value; if (s->ops.state) s->ops.state(s->ops.context, value); }
void miwear_companion_init(struct miwear_companion *s, const struct miwear_companion_ops *ops)
{
    memset(s, 0, sizeof *s); if (ops) s->ops = *ops;
    miwear_sar_init(&s->sar, send_wire, sar_event, sar_data, s);
    miwear_auth_session_init(&s->auth, send_auth, auth_event, s);
}
void miwear_companion_close(struct miwear_companion *s)
{
    if (!s) return;
    struct miwear_companion_ops ops = s->ops;
    wipe(s, sizeof *s); miwear_companion_init(s, &ops);
}
static void failed(struct miwear_companion *s)
{
    miwear_companion_close(s); state(s, MIWEAR_COMPANION_FAILED);
}
int miwear_companion_start(struct miwear_companion *s, const uint8_t key[16], const uint8_t nonce[16], uint32_t now)
{
    if (!s || !key || !nonce || !s->ops.send || (s->state != MIWEAR_COMPANION_IDLE && s->state != MIWEAR_COMPANION_FAILED)) return -1;
    miwear_companion_close(s); s->now = now; memcpy(s->key, key, 16); memcpy(s->nonce, nonce, 16);
    state(s, MIWEAR_COMPANION_SAR);
    if (miwear_sar_start(&s->sar, now)) { failed(s); return -1; }
    return 0;
}
void miwear_companion_poll(struct miwear_companion *s, uint32_t now)
{
    if (!s || s->state == MIWEAR_COMPANION_IDLE || s->state == MIWEAR_COMPANION_FAILED) return;
    s->now = now;
    miwear_sar_tick(&s->sar, now);
    for (unsigned count = 0; count < 4; ++count) {
        unsigned events = s->events; s->events = 0;
        if (s->overflow || (events & (RESET | FAILED))) { failed(s); return; }
        if ((events & OPENED) && s->state == MIWEAR_COMPANION_SAR) {
            state(s, MIWEAR_COMPANION_AUTH);
            int error = miwear_auth_session_begin(&s->auth, s->key, s->nonce, 1, now);
            wipe(s->key, sizeof s->key); wipe(s->nonce, sizeof s->nonce);
            if (error) { failed(s); return; }
        }
        if ((events & VERIFIED) && s->auth.state == MIWEAR_AUTH_READY) state(s, MIWEAR_COMPANION_READY);
        if (!s->rx_count) break;
        unsigned slot = s->rx_head; size_t length = 0;
        enum miwear_l2_kind kind = miwear_l2_receive(&s->auth, s->rx[slot], s->rx_length[slot], s->incoming, sizeof s->incoming, &length);
        wipe(s->rx[slot], sizeof s->rx[slot]); s->rx_length[slot] = 0;
        s->rx_head = (s->rx_head + 1) % 2; --s->rx_count;
        if (kind == MIWEAR_L2_AUTH) miwear_auth_session_receive(&s->auth, s->incoming, length, now);
        else if (kind == MIWEAR_L2_APP && s->state == MIWEAR_COMPANION_READY && s->ops.application)
            s->ops.application(s->ops.context, s->incoming, length);
        wipe(s->incoming, sizeof s->incoming);
    }
    miwear_auth_session_poll(&s->auth, now);
    if (s->events & FAILED) failed(s);
    else if ((s->events & VERIFIED) && s->auth.state == MIWEAR_AUTH_READY) {
        s->events &= ~VERIFIED; state(s, MIWEAR_COMPANION_READY);
    }
}
void miwear_companion_receive(struct miwear_companion *s, const uint8_t *bytes, size_t length, uint32_t now)
{
    if (!s || (!bytes && length) || s->state == MIWEAR_COMPANION_IDLE || s->state == MIWEAR_COMPANION_FAILED) return;
    s->now = now; miwear_l1_feed(&s->stream, bytes, length, frame, s); miwear_companion_poll(s, now);
}
int miwear_companion_send_pb(struct miwear_companion *s, const uint8_t *bytes, size_t length, uint32_t now)
{
    if (!s || s->state != MIWEAR_COMPANION_READY) return -1;
    size_t size = miwear_l2_app_write(&s->auth, s->outgoing, sizeof s->outgoing, bytes, length);
    return size ? miwear_sar_send(&s->sar, s->outgoing, size, now) : -1;
}
