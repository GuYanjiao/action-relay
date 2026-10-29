/* Independent bounded codec for the documented MiWear v2 wire fields.
 * Evidence: AstroBox Core dc08a079.../packet/v2/layer1.rs. CRC is CRC-16/ARC. */
#include <string.h>
#include "miwear_l1.h"
static uint16_t le16(const uint8_t *p)
{ return (uint16_t)p[0] | ((uint16_t)p[1] << 8); }
static void put16(uint8_t *p, uint16_t value)
{ p[0] = (uint8_t)value; p[1] = (uint8_t)(value >> 8); }
uint16_t miwear_crc16_arc(const uint8_t *data, size_t length)
{
    uint16_t crc = 0;
    for (size_t i = 0; i < length; ++i) {
        crc ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (uint16_t)((crc >> 1) ^ ((crc & 1) ? 0xa001 : 0));
    }
    return crc;
}
size_t miwear_l1_encode(uint8_t *out, size_t capacity, uint8_t type, bool frx,
                        uint8_t sequence, const uint8_t *payload, size_t length)
{
    if (!out || type > MIWEAR_DATA || length > MIWEAR_L1_MAX_PAYLOAD ||
        (length && !payload) || capacity < MIWEAR_L1_HEADER + length) return 0;
    /* memmove permits payload staged within the output area. */
    if (length) memmove(out + MIWEAR_L1_HEADER, payload, length);
    out[0] = out[1] = 0xa5;
    out[2] = type | (frx ? 0x10 : 0);
    out[3] = sequence;
    put16(out + 4, (uint16_t)length);
    put16(out + 6, miwear_crc16_arc(out + MIWEAR_L1_HEADER, length));
    return MIWEAR_L1_HEADER + length;
}
int miwear_l1_parse(const uint8_t *data, size_t length, struct miwear_l1_view *out)
{
    if (!out) return -1;
    memset(out, 0, sizeof *out);
    if (!data || length < MIWEAR_L1_HEADER || data[0] != 0xa5 || data[1] != 0xa5) return -1;
    uint16_t size = le16(data + 4);
    uint8_t type = data[2] & 0x0f;
    if (type > MIWEAR_DATA || size > MIWEAR_L1_MAX_PAYLOAD ||
        length != MIWEAR_L1_HEADER + (size_t)size ||
        miwear_crc16_arc(data + MIWEAR_L1_HEADER, size) != le16(data + 6)) return -1;
    out->type = type; out->frx = (data[2] & 0x10) != 0; out->sequence = data[3];
    out->payload = data + MIWEAR_L1_HEADER; out->length = size;
    return 0;
}
static void discard(struct miwear_l1_stream *stream, size_t count)
{
    stream->used -= count;
    memmove(stream->bytes, stream->bytes + count, stream->used);
}
void miwear_l1_feed(struct miwear_l1_stream *stream, const uint8_t *data, size_t length,
                     miwear_l1_received received, void *context)
{
    if (!stream || (!data && length) || !received) return;
    for (size_t i = 0; i < length; ++i) {
        if (stream->used >= sizeof stream->bytes) stream->used = 0;
        stream->bytes[stream->used++] = data[i];
        while (stream->used >= 2) {
            if (stream->bytes[0] != 0xa5 || stream->bytes[1] != 0xa5) {
                discard(stream, 1); continue;
            }
            if (stream->used < MIWEAR_L1_HEADER) break;
            size_t payload = le16(stream->bytes + 4);
            if (payload > MIWEAR_L1_MAX_PAYLOAD || (stream->bytes[2] & 0x0f) > MIWEAR_DATA) {
                discard(stream, 1); continue;
            }
            size_t total = MIWEAR_L1_HEADER + payload;
            if (stream->used < total) break;
            struct miwear_l1_view view;
            if (miwear_l1_parse(stream->bytes, total, &view)) discard(stream, 1);
            else { received(context, &view); discard(stream, total); }
        }
    }
}
