#include <assert.h>
#include <string.h>
#include <pb_decode.h>
#include "miwear_auth.pb.h"
#include "../src/miwear_pb.h"
int main(void)
{
    uint8_t random[16], signature[32], out[256];
    for (size_t i = 0; i < 16; ++i) random[i] = (uint8_t)i;
    const uint8_t verify[] = {8,1,16,26,26,21,0xf2,1,18,10,16,
                             0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
    assert(miwear_pb_verify(out, sizeof out, random) == sizeof verify);
    assert(!memcmp(out, verify, sizeof verify));
    assert(miwear_pb_verify(out, sizeof verify - 1, random) == 0);
    uint8_t challenge[61] = {8,1,16,26,26,55,0xfa,1,52,10,16};
    for (size_t i = 0; i < 16; ++i) challenge[11 + i] = (uint8_t)(i + 16);
    challenge[27] = 18; challenge[28] = 32;
    for (size_t i = 0; i < 32; ++i) challenge[29 + i] = (uint8_t)(i + 32);
    assert(miwear_pb_device_verify(challenge, sizeof challenge, random, signature));
    assert(!memcmp(random, challenge + 11, 16) && !memcmp(signature, challenge + 29, 32));
    for (size_t i = 0; i < sizeof challenge; ++i) {
        assert(!miwear_pb_device_verify(challenge, i, random, signature));
        for (size_t j = 0; j < 16; ++j) assert(random[j] == 0);
        for (size_t j = 0; j < 32; ++j) assert(signature[j] == 0);
    }
    challenge[3] = 27; assert(!miwear_pb_device_verify(challenge, sizeof challenge, random, signature));
    challenge[3] = 26; challenge[1] = 20;
    assert(!miwear_pb_device_verify(challenge, sizeof challenge, random, signature));
    uint8_t confirm[] = {8,1,16,27,26,5,0x8a,2,2,8,1};
    assert(miwear_pb_device_confirm(confirm, sizeof confirm));
    confirm[10] = 0; assert(!miwear_pb_device_confirm(confirm, sizeof confirm));
    const uint8_t no_result[] = {8,1,16,27,26,3,0x8a,2,0};
    assert(!miwear_pb_device_confirm(no_result, sizeof no_result));
    const uint8_t error_only[] = {8,1,16,27,26,2,24,0};
    assert(!miwear_pb_device_confirm(error_only, sizeof error_only));
    const uint8_t encrypted[12] = {0};
    size_t count = miwear_pb_confirm(out, sizeof out, signature, encrypted, sizeof encrypted);
    assert(count);
    actionrelay_AuthEnvelope decoded = actionrelay_AuthEnvelope_init_zero;
    pb_istream_t stream = pb_istream_from_buffer(out, count);
    assert(pb_decode(&stream, actionrelay_AuthEnvelope_fields, &decoded));
    assert(decoded.type == 1 && decoded.id == 27 && decoded.has_account);
    assert(decoded.account.which_payload == actionrelay_AuthAccount_app_confirm_tag);
    assert(decoded.account.payload.app_confirm.encrypted_companion.size == sizeof encrypted);
    assert(miwear_pb_confirm(out, sizeof out, signature, encrypted, 3) == 0);
    assert(miwear_pb_companion(out, sizeof out, 1, "Action Relay", false, 0) == 16);
    const uint8_t companion[] = {8,1,26,12,'A','c','t','i','o','n',' ','R','e','l','a','y'};
    assert(!memcmp(out, companion, sizeof companion));
    assert(miwear_pb_companion(out, sizeof out, 99, "Action Relay", false, 0) == 0);
    assert(miwear_pb_companion(out, sizeof out, 1, "", false, 0) == 0);
    assert(miwear_pb_companion(out, sizeof out, 1, "012345678901234567890123456789012", false, 0) == 0);
    return 0;
}
