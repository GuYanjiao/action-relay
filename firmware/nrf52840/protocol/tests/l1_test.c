#include <assert.h>
#include <string.h>
#include "../miwear_l1.h"
static unsigned frames;
static void received(void *context, const struct miwear_l1_view *frame)
{
    unsigned *count = context;
    assert(frame->type == MIWEAR_DATA && frame->frx && frame->sequence == 254);
    assert(frame->length == 9 && !memcmp(frame->payload, "123456789", 9));
    ++*count;
}
int main(void)
{
    const uint8_t golden[] = {0xa5, 0xa5, 0x13, 0xfe, 9, 0, 0x3d, 0xbb,
                             '1', '2', '3', '4', '5', '6', '7', '8', '9'};
    uint8_t out[MIWEAR_L1_HEADER + MIWEAR_L1_MAX_PAYLOAD], bad[sizeof golden];
    struct miwear_l1_view frame;
    assert(miwear_crc16_arc((const uint8_t *)"123456789", 9) == 0xbb3d);
    assert(miwear_l1_encode(out, sizeof out, MIWEAR_DATA, true, 254,
                            (const uint8_t *)"123456789", 9) == sizeof golden);
    assert(!memcmp(out, golden, sizeof golden));
    assert(miwear_l1_parse(golden, sizeof golden, &frame) == 0);
    for (size_t i = 0; i < sizeof golden; ++i)
        assert(miwear_l1_parse(golden, i, &frame) != 0 && !frame.payload);
    for (size_t i = 6; i < sizeof golden; ++i) {
        memcpy(bad, golden, sizeof bad); bad[i] ^= 1;
        assert(miwear_l1_parse(bad, sizeof bad, &frame) != 0);
    }
    memcpy(bad, golden, sizeof bad); bad[2] = 4;
    assert(miwear_l1_parse(bad, sizeof bad, &frame) != 0);
    for (size_t split = 0; split <= sizeof golden; ++split) {
        struct miwear_l1_stream stream = {0}; frames = 0;
        miwear_l1_feed(&stream, golden, split, received, &frames);
        miwear_l1_feed(&stream, golden + split, sizeof golden - split, received, &frames);
        assert(frames == 1 && !stream.used);
    }
    struct miwear_l1_stream stream = {0}; frames = 0;
    uint8_t joined[2 * sizeof golden];
    memcpy(joined, golden, sizeof golden); memcpy(joined + sizeof golden, golden, sizeof golden);
    miwear_l1_feed(&stream, joined, sizeof joined, received, &frames);
    assert(frames == 2 && !stream.used);
    const uint8_t noise[] = {0, 0xa5, 0, 0xff, 0xa5};
    miwear_l1_feed(&stream, noise, sizeof noise, received, &frames);
    memcpy(bad, golden, sizeof bad); bad[16] ^= 1;
    miwear_l1_feed(&stream, bad, sizeof bad, received, &frames);
    const uint8_t huge[] = {0xa5, 0xa5, 3, 0, 0xff, 0xff, 0, 0};
    miwear_l1_feed(&stream, huge, sizeof huge, received, &frames);
    miwear_l1_feed(&stream, golden, sizeof golden, received, &frames);
    assert(frames == 3 && !stream.used);
    memset(out, 0x42, sizeof out);
    assert(miwear_l1_encode(out, sizeof out, MIWEAR_DATA, false, 0, out,
                            MIWEAR_L1_MAX_PAYLOAD) == sizeof out);
    assert(miwear_l1_parse(out, sizeof out, &frame) == 0 && frame.length == MIWEAR_L1_MAX_PAYLOAD);
    assert(frame.payload[0] == 0x42 && frame.payload[MIWEAR_L1_MAX_PAYLOAD - 1] == 0x42);
    assert(miwear_l1_encode(out, sizeof out, MIWEAR_DATA, false, 0, out, MIWEAR_L1_MAX_PAYLOAD + 1) == 0);
    assert(miwear_l1_encode(out, 7, MIWEAR_ACK, false, 0, NULL, 0) == 0);
    assert(miwear_l1_encode(out, sizeof out, MIWEAR_ACK, false, 255, NULL, 0) == 8);
    assert(miwear_l1_parse(out, 8, &frame) == 0 && frame.sequence == 255 && frame.length == 0);
    assert(miwear_l1_parse(NULL, 0, &frame) != 0);
    assert(miwear_l1_parse(golden, sizeof golden, NULL) != 0);
    return 0;
}
