#include <string.h>
#include <pb_encode.h>
#include <pb_decode.h>
#include "miwear_auth.pb.h"
#include "miwear_pb.h"
static size_t encode(uint8_t *out, size_t capacity, const pb_msgdesc_t *fields, const void *message)
{
    if (!out) return 0;
    pb_ostream_t stream = pb_ostream_from_buffer(out, capacity);
    return pb_encode(&stream, fields, message) ? stream.bytes_written : 0;
}
static bool account(const uint8_t *data, size_t length, uint32_t id, actionrelay_AuthEnvelope *message)
{
    if (!data) return false;
    pb_istream_t stream = pb_istream_from_buffer(data, length);
    return pb_decode(&stream, actionrelay_AuthEnvelope_fields, message) &&
           message->type == 1 && message->id == id && message->has_account;
}
size_t miwear_pb_verify(uint8_t *out, size_t capacity, const uint8_t random[16])
{
    if (!random) return 0;
    actionrelay_AuthEnvelope message = actionrelay_AuthEnvelope_init_zero;
    message.type = 1; message.id = 26; message.has_account = true;
    message.account.which_payload = actionrelay_AuthAccount_app_verify_tag;
    memcpy(message.account.payload.app_verify.app_random, random, 16);
    return encode(out, capacity, actionrelay_AuthEnvelope_fields, &message);
}
bool miwear_pb_device_verify(const uint8_t *data, size_t length, uint8_t random[16], uint8_t signature[32])
{
    if (!random || !signature) return false;
    memset(random, 0, 16); memset(signature, 0, 32);
    actionrelay_AuthEnvelope message = actionrelay_AuthEnvelope_init_zero;
    if (!account(data, length, 26, &message) ||
        message.account.which_payload != actionrelay_AuthAccount_device_verify_tag) return false;
    memcpy(random, message.account.payload.device_verify.device_random, 16);
    memcpy(signature, message.account.payload.device_verify.device_sign, 32);
    return true;
}
size_t miwear_pb_confirm(uint8_t *out, size_t capacity, const uint8_t signature[32],
                         const uint8_t *companion, size_t length)
{
    if (!signature || !companion || length < 4 || length > 128) return 0;
    actionrelay_AuthEnvelope message = actionrelay_AuthEnvelope_init_zero;
    message.type = 1; message.id = 27; message.has_account = true;
    message.account.which_payload = actionrelay_AuthAccount_app_confirm_tag;
    memcpy(message.account.payload.app_confirm.app_sign, signature, 32);
    message.account.payload.app_confirm.encrypted_companion.size = length;
    memcpy(message.account.payload.app_confirm.encrypted_companion.bytes, companion, length);
    return encode(out, capacity, actionrelay_AuthEnvelope_fields, &message);
}
bool miwear_pb_device_confirm(const uint8_t *data, size_t length)
{
    actionrelay_AuthEnvelope message = actionrelay_AuthEnvelope_init_zero;
    return account(data, length, 27, &message) &&
           message.account.which_payload == actionrelay_AuthAccount_device_confirm_tag &&
           message.account.payload.device_confirm.confirmed;
}
size_t miwear_pb_companion(uint8_t *out, size_t capacity, uint32_t type, const char *name,
                            bool has_capability, uint32_t capability)
{
    if (!name || (type > 2 && type != 15)) return 0;
    size_t length = 0;
    while (length <= 32 && name[length]) ++length;
    if (!length || length > 32) return 0;
    actionrelay_CompanionDevice message = actionrelay_CompanionDevice_init_zero;
    message.device_type = type;
    memcpy(message.device_name, name, length + 1);
    message.has_capability = has_capability; message.capability = capability;
    return encode(out, capacity, actionrelay_CompanionDevice_fields, &message);
}
