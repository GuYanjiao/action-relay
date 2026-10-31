#include <assert.h>
#include <string.h>
#include "../src/miwear_companion.h"
#include "../../protocol/miwear_psa.h"
#include "../../protocol/tests/vectors.h"
struct fixture { uint8_t wire[24][1032]; size_t length[24]; unsigned sent, ready, failed, application; };
static int send(void *context, const uint8_t *bytes, size_t length)
{
    struct fixture *f = context; assert(f->sent < 24 && length <= 1032);
    memcpy(f->wire[f->sent], bytes, length); f->length[f->sent++] = length; return 0;
}
static void changed(void *context, enum miwear_companion_state state)
{ struct fixture *f = context; if (state == MIWEAR_COMPANION_READY) ++f->ready; if (state == MIWEAR_COMPANION_FAILED) ++f->failed; }
static void application(void *context, const uint8_t *bytes, size_t length)
{ struct fixture *f = context; const uint8_t expected[] = {8,20,16,6}; assert(length == sizeof expected && !memcmp(bytes, expected, length)); ++f->application; }
static bool zero(const void *data, size_t length)
{ const uint8_t *bytes = data; for (size_t i = 0; i < length; ++i) if (bytes[i]) return false; return true; }
static void feed(struct miwear_companion *s, uint8_t type, uint8_t seq, const uint8_t *bytes, size_t length)
{
    uint8_t wire[1032]; size_t count = miwear_l1_encode(wire, sizeof wire, type, false, seq, bytes, length); assert(count);
    for (size_t i = 0; i < count; ++i) miwear_companion_receive(s, wire + i, 1, 100);
}
static void start(struct miwear_companion *s, struct fixture *f)
{
    const struct miwear_companion_ops ops = {.send = send, .state = changed, .application = application, .context = f};
    miwear_companion_init(s, &ops);
    uint8_t key[16], nonce[16]; for (unsigned i = 0; i < 16; ++i) { key[i] = i; nonce[i] = i + 16; }
    assert(!miwear_companion_start(s, key, nonce, 100)); assert(s->state == MIWEAR_COMPANION_SAR);
    const uint8_t reply[] = {2}; feed(s, MIWEAR_COMMAND, 0, reply, sizeof reply);
    assert(s->state == MIWEAR_COMPANION_AUTH && zero(s->key, 16));
}
int main(void)
{
    assert(!miwear_psa_init());
    struct miwear_companion s; struct fixture f = {0}; start(&s, &f);
    assert(f.sent == 2 && !f.ready);
    uint8_t challenge[63] = {1,1,8,1,16,26,26,55,0xfa,1,52,10,16};
    for (unsigned i = 0; i < 16; ++i) challenge[13 + i] = i + 32;
    challenge[29] = 18; challenge[30] = 32; memcpy(challenge + 31, gold_watch, 32);
    feed(&s, MIWEAR_ACK, 0, NULL, 0);
    feed(&s, MIWEAR_DATA, 0, challenge, sizeof challenge);
    assert(s.auth.state == MIWEAR_AUTH_CONFIRMING && zero(s.auth.authkey, 16) && !f.ready);
    feed(&s, MIWEAR_ACK, 1, NULL, 0);
    const uint8_t confirmation[] = {1,1,8,1,16,27,26,5,0x8a,2,2,8,1};
    feed(&s, MIWEAR_DATA, 1, confirmation, sizeof confirmation);
    assert(s.state == MIWEAR_COMPANION_READY && f.ready == 1);
    uint8_t frame[128] = {1,2}; size_t written = 0; const uint8_t app[] = {8,20,16,6};
    assert(!miwear_psa_ctr(s.auth.keys.rx_key, s.auth.keys.rx_key, app, sizeof app, frame + 2, sizeof frame - 2, &written));
    feed(&s, MIWEAR_DATA, 2, frame, written + 2); assert(f.application == 1);
    assert(!miwear_companion_send_pb(&s, app, sizeof app, 100));
    assert(miwear_companion_send_pb(&s, app, sizeof app, 100) == -2); // one in flight
    const uint8_t restart[] = {1}; feed(&s, MIWEAR_COMMAND, 0, restart, sizeof restart);
    assert(s.state == MIWEAR_COMPANION_FAILED && f.failed == 1 && zero(&s.auth.keys, sizeof s.auth.keys));
    assert(miwear_companion_send_pb(&s, app, sizeof app, 100) == -1);
    memset(&f, 0, sizeof f); start(&s, &f);
    feed(&s, MIWEAR_ACK, 0, NULL, 0); challenge[31] ^= 1;
    feed(&s, MIWEAR_DATA, 0, challenge, sizeof challenge);
    assert(s.state == MIWEAR_COMPANION_FAILED && !f.ready && f.failed == 1 && zero(&s.auth.keys, sizeof s.auth.keys));
    miwear_companion_close(&s); assert(s.state == MIWEAR_COMPANION_IDLE && zero(s.key, 16));
    return 0;
}
