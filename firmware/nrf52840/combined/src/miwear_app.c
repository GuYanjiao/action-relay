#include <string.h>
#include <pb_decode.h>
#include <pb_encode.h>
#include "miwear_app.pb.h"
#include "miwear_app.h"

static bool identity(actionrelayapp_Identity *out, const char *package,
                     const uint8_t *fingerprint, size_t length)
{
    if (!package || !fingerprint || !length || length > sizeof out->fingerprint.bytes) return false;
    size_t n = 0;
    while (n <= sizeof out->package_name.bytes && package[n]) ++n;
    if (!n || n > sizeof out->package_name.bytes) return false;
    out->package_name.size = n;
    memcpy(out->package_name.bytes, package, n);
    out->fingerprint.size = length;
    memcpy(out->fingerprint.bytes, fingerprint, length);
    return true;
}
static bool matches(const actionrelayapp_Identity *a, const actionrelayapp_Identity *b)
{
    return a->package_name.size == b->package_name.size &&
        !memcmp(a->package_name.bytes, b->package_name.bytes, a->package_name.size) &&
        a->fingerprint.size == b->fingerprint.size &&
        !memcmp(a->fingerprint.bytes, b->fingerprint.bytes, a->fingerprint.size);
}
bool miwear_app_receive(const uint8_t *input, size_t length, const char *package,
    const uint8_t *fingerprint, size_t fingerprint_length, struct miwear_app_message *out)
{
    if (!out) return false;
    memset(out, 0, sizeof *out);
    if (!input || !length || length > 1024) return false;
    actionrelayapp_Identity expected = actionrelayapp_Identity_init_zero;
    if (!identity(&expected, package, fingerprint, fingerprint_length)) return false;
    actionrelayapp_Envelope packet = actionrelayapp_Envelope_init_zero;
    pb_istream_t stream = pb_istream_from_buffer(input, length);
    if (!pb_decode(&stream, actionrelayapp_Envelope_fields, &packet) || packet.type != 20 || !packet.has_app) return false;
    if (packet.id == 6 && packet.app.which_payload == actionrelayapp_App_identity_tag &&
        matches(&packet.app.payload.identity, &expected)) {
        out->kind = MIWEAR_APP_STATUS_REQUEST;
        return true;
    }
    if (packet.id != 9 || packet.app.which_payload != actionrelayapp_App_content_tag ||
        !matches(&packet.app.payload.content.identity, &expected)) return false;
    out->length = packet.app.payload.content.content.size;
    memcpy(out->content, packet.app.payload.content.content.bytes, out->length);
    out->kind = MIWEAR_APP_MESSAGE;
    return true;
}
static size_t encode(uint8_t *output, size_t capacity, const actionrelayapp_Envelope *packet)
{
    if (!output) return 0;
    pb_ostream_t stream = pb_ostream_from_buffer(output, capacity);
    return pb_encode(&stream, actionrelayapp_Envelope_fields, packet) ? stream.bytes_written : 0;
}
size_t miwear_app_send(uint8_t *output, size_t capacity, const char *package,
    const uint8_t *fingerprint, size_t fingerprint_length, const uint8_t *content, size_t length)
{
    if ((!content && length) || length > MIWEAR_APP_CONTENT_MAX) return 0;
    actionrelayapp_Envelope packet = actionrelayapp_Envelope_init_zero;
    packet.type = 20; packet.id = 8; packet.has_app = true;
    packet.app.which_payload = actionrelayapp_App_content_tag;
    if (!identity(&packet.app.payload.content.identity, package, fingerprint, fingerprint_length)) return 0;
    packet.app.payload.content.content.size = length;
    if (length) memcpy(packet.app.payload.content.content.bytes, content, length);
    return encode(output, capacity, &packet);
}
size_t miwear_app_status(uint8_t *output, size_t capacity, const char *package,
    const uint8_t *fingerprint, size_t fingerprint_length, bool connected)
{
    actionrelayapp_Envelope packet = actionrelayapp_Envelope_init_zero;
    packet.type = 20; packet.id = 7; packet.has_app = true;
    packet.app.which_payload = actionrelayapp_App_status_tag;
    if (!identity(&packet.app.payload.status.identity, package, fingerprint, fingerprint_length)) return 0;
    packet.app.payload.status.status = connected ? 1 : 2;
    return encode(output, capacity, &packet);
}
