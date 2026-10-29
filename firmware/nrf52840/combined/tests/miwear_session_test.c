#include <assert.h>
#include <string.h>
#include <pb_decode.h>
#include "miwear_auth.pb.h"
#include "../src/miwear_session.h"
#include "../../protocol/miwear_psa.h"
#include "../../protocol/tests/vectors.h"
struct fixture { bool busy; int error; unsigned sent, accepted, rejected, reset; uint8_t packet[256]; size_t length; };
static int send_pb(void *context, const uint8_t *packet, size_t length)
{
    struct fixture *f = context;
    if (f->busy) return -2;
    if (f->error) return f->error;
    assert(length <= sizeof f->packet); memcpy(f->packet, packet, length); f->length = length; ++f->sent;
    return 0;
}
static void event(void *context, enum miwear_auth_event value)
{
    struct fixture *f = context;
    if (value == MIWEAR_AUTH_ACCEPTED) ++f->accepted;
    else if (value == MIWEAR_AUTH_REJECTED) ++f->rejected;
    else ++f->reset;
}
static bool zero(const void *bytes, size_t size)
{ const uint8_t *p = bytes; for (size_t i = 0; i < size; ++i) if (p[i]) return false; return true; }
static void challenge(uint8_t out[61])
{
    const uint8_t prefix[] = {8,1,16,26,26,55,0xfa,1,52,10,16};
    memcpy(out, prefix, sizeof prefix);
    for (size_t i = 0; i < 16; ++i) out[11 + i] = (uint8_t)(i + 32);
    out[27] = 18; out[28] = 32; memcpy(out + 29, gold_watch, 32);
}
int main(void)
{
    assert(miwear_psa_init() == 0);
    struct miwear_auth_session s; struct fixture f = {0};
    uint8_t key[16], random[16], packet[61];
    for (size_t i = 0; i < 16; ++i) { key[i] = (uint8_t)i; random[i] = (uint8_t)(i + 16); }
    challenge(packet);
    const uint8_t confirmed[] = {8,1,16,27,26,5,0x8a,2,2,8,1};
    miwear_auth_session_init(&s, send_pb, event, &f);
    assert(miwear_auth_session_begin(&s, key, random, 1, 0) == 0 && f.sent == 1);
    miwear_auth_session_receive(&s, confirmed, sizeof confirmed, 1);
    assert(s.state == MIWEAR_AUTH_VERIFYING && f.accepted == 0);
    miwear_auth_session_receive(&s, packet, sizeof packet, 2);
    assert(s.state == MIWEAR_AUTH_CONFIRMING && f.sent == 2 && zero(s.authkey, 16));
    actionrelay_AuthEnvelope message = actionrelay_AuthEnvelope_init_zero;
    pb_istream_t stream = pb_istream_from_buffer(f.packet, f.length);
    assert(pb_decode(&stream, actionrelay_AuthEnvelope_fields, &message));
    assert(message.account.which_payload == actionrelay_AuthAccount_app_confirm_tag);
    assert(!memcmp(message.account.payload.app_confirm.app_sign, gold_phone, 32));
    uint8_t nonce[12] = {0}, plain[64]; size_t length = 0;
    memcpy(nonce, gold_keys + 36, 4);
    assert(miwear_psa_ccm(false, gold_keys + 16, nonce,
        message.account.payload.app_confirm.encrypted_companion.bytes,
        message.account.payload.app_confirm.encrypted_companion.size, plain, sizeof plain, &length) == 0);
    const uint8_t companion[] = {8,1,26,12,'A','c','t','i','o','n',' ','R','e','l','a','y'};
    assert(length == sizeof companion && !memcmp(plain, companion, sizeof companion));
    miwear_auth_session_receive(&s, confirmed, sizeof confirmed, 3);
    assert(s.state == MIWEAR_AUTH_READY && f.accepted == 1 && zero(s.random, 16));
    assert(!memcmp(&s.keys, gold_keys, sizeof s.keys));
    miwear_auth_session_close(&s);
    assert(s.state == MIWEAR_AUTH_IDLE && zero(&s.keys, sizeof s.keys) && f.reset == 1);
    for (size_t i = 0; i < 32; ++i) {
        memset(&f, 0, sizeof f); miwear_auth_session_init(&s, send_pb, event, &f);
        assert(miwear_auth_session_begin(&s, key, random, 1, 0) == 0);
        challenge(packet); packet[29 + i] ^= 1;
        miwear_auth_session_receive(&s, packet, sizeof packet, 1);
        assert(s.state == MIWEAR_AUTH_FAILED && f.rejected == 1 && f.sent == 1 && !f.accepted);
        assert(zero(s.authkey, 16) && zero(&s.keys, sizeof s.keys) && zero(s.pending, sizeof s.pending));
    }
    memset(&f, 0, sizeof f); f.busy = true; challenge(packet);
    miwear_auth_session_init(&s, send_pb, event, &f);
    assert(miwear_auth_session_begin(&s, key, random, 1, 0) == 0 && !f.sent && s.pending_length);
    miwear_auth_session_receive(&s, packet, sizeof packet, 1); assert(s.state == MIWEAR_AUTH_VERIFYING);
    f.busy = false; miwear_auth_session_poll(&s, 2); assert(f.sent == 1 && !s.pending_length);
    f.busy = true; miwear_auth_session_receive(&s, packet, sizeof packet, 3);
    assert(s.state == MIWEAR_AUTH_CONFIRMING && s.pending_length && f.sent == 1);
    miwear_auth_session_receive(&s, confirmed, sizeof confirmed, 4); assert(!f.accepted);
    f.busy = false; miwear_auth_session_poll(&s, 5);
    miwear_auth_session_receive(&s, confirmed, sizeof confirmed, 6); assert(f.accepted == 1);
    miwear_auth_session_close(&s); f.busy = true;
    assert(miwear_auth_session_begin(&s, key, random, 1, UINT32_MAX - 10) == 0);
    miwear_auth_session_poll(&s, 19988); assert(s.state == MIWEAR_AUTH_VERIFYING);
    miwear_auth_session_poll(&s, 19989); assert(s.state == MIWEAR_AUTH_FAILED && zero(s.authkey, 16));
    memset(&f, 0, sizeof f); f.error = -1; miwear_auth_session_init(&s, send_pb, event, &f);
    assert(miwear_auth_session_begin(&s, key, random, 1, 0) == -1 && s.state == MIWEAR_AUTH_FAILED);
    return 0;
}
