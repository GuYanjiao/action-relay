#include <assert.h>
#include <string.h>
#include <pb_encode.h>
#include "miwear_app.pb.h"
#include "../src/miwear_app.h"
int main(void)
{
    uint8_t output[1024], payload[MIWEAR_APP_CONTENT_MAX], fingerprint[64] = {0};
    struct miwear_app_message message;
    // Hand-derived PB vector: envelope(20,8), field22, content9, identity(a,00), hi.
    const uint8_t golden[] = {8,20,16,8,0xb2,1,14,0x4a,12,10,6,10,1,'a',18,1,0,18,2,'h','i'};
    assert(miwear_app_send(output, sizeof output, "a", fingerprint, 1, (const uint8_t *)"hi", 2) == sizeof golden);
    assert(!memcmp(output, golden, sizeof golden));
    assert(!miwear_app_receive(output, sizeof golden, "a", fingerprint, 1, &message)); // wrong direction
    output[3] = 9;
    assert(miwear_app_receive(output, sizeof golden, "a", fingerprint, 1, &message));
    assert(message.kind == MIWEAR_APP_MESSAGE && message.length == 2 && !memcmp(message.content, "hi", 2));
    for (size_t n = 0; n < sizeof golden; ++n) {
        assert(!miwear_app_receive(output, n, "a", fingerprint, 1, &message));
        assert(message.kind == MIWEAR_APP_INVALID && !message.length);
    }
    assert(!miwear_app_receive(output, sizeof golden, "b", fingerprint, 1, &message));
    fingerprint[0] = 1;
    assert(!miwear_app_receive(output, sizeof golden, "a", fingerprint, 1, &message));
    fingerprint[0] = 0;
    const uint8_t query[] = {8,20,16,6,0xb2,1,8,0x2a,6,10,1,'a',18,1,0};
    assert(miwear_app_receive(query, sizeof query, "a", fingerprint, 1, &message));
    assert(message.kind == MIWEAR_APP_STATUS_REQUEST && message.length == 0);
    const uint8_t connected[] = {8,20,16,7,0xb2,1,12,0x42,10,10,6,10,1,'a',18,1,0,16,1};
    assert(miwear_app_status(output, sizeof output, "a", fingerprint, 1, true) == sizeof connected);
    assert(!memcmp(output, connected, sizeof connected));
    assert(miwear_app_status(output, sizeof output, "a", fingerprint, 1, false) == sizeof connected);
    assert(output[sizeof connected - 1] == 2);
    memset(payload, 0x5a, sizeof payload);
    size_t length = miwear_app_send(output, sizeof output, "cn.local.actionrelay", fingerprint, 64, payload, sizeof payload);
    assert(length && length <= sizeof output); output[3] = 9;
    assert(miwear_app_receive(output, length, "cn.local.actionrelay", fingerprint, 64, &message));
    assert(message.length == sizeof payload && !memcmp(message.content, payload, sizeof payload));
    assert(!miwear_app_send(output, 1, "a", fingerprint, 1, payload, sizeof payload));
    assert(!miwear_app_send(output, sizeof output, "a", fingerprint, 1, payload, sizeof payload + 1));
    assert(!miwear_app_send(output, sizeof output, "a", fingerprint, 0, payload, 1));
    assert(!miwear_app_send(output, sizeof output, "a", fingerprint, 65, payload, 1));
    assert(!miwear_app_send(output, sizeof output, "", fingerprint, 1, payload, 1));
    // A wire package with embedded NUL must not match the shorter configured name.
    actionrelayapp_Envelope packet = actionrelayapp_Envelope_init_zero;
    packet.type = 20; packet.id = 6; packet.has_app = true;
    packet.app.which_payload = actionrelayapp_App_identity_tag;
    packet.app.payload.identity.package_name.size = 3;
    memcpy(packet.app.payload.identity.package_name.bytes, "a\0b", 3);
    packet.app.payload.identity.fingerprint.size = 1;
    pb_ostream_t stream = pb_ostream_from_buffer(output, sizeof output);
    assert(pb_encode(&stream, actionrelayapp_Envelope_fields, &packet));
    assert(!miwear_app_receive(output, stream.bytes_written, "a", fingerprint, 1, &message));
    return 0;
}
