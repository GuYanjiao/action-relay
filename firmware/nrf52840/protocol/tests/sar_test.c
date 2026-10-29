#include <assert.h>
#include <string.h>
#include "../miwear_sar.h"
struct fixture {
    unsigned sends, data, opened, resets, errors, acked;
    int send_error;
    uint8_t last[MIWEAR_L1_HEADER + MIWEAR_L1_MAX_PAYLOAD];
    size_t length;
};
static int send_frame(void *context, const uint8_t *wire, size_t length)
{
    struct fixture *f = context; struct miwear_l1_view frame;
    assert(miwear_l1_parse(wire, length, &frame) == 0);
    memcpy(f->last, wire, length); f->length = length; ++f->sends;
    return f->send_error;
}
static void event(void *context, enum miwear_sar_event value)
{
    struct fixture *f = context;
    if (value == MIWEAR_SAR_OPENED) ++f->opened;
    else if (value == MIWEAR_SAR_RESET) ++f->resets;
    else if (value == MIWEAR_SAR_ERROR) ++f->errors;
    else if (value == MIWEAR_SAR_TX_ACKED) ++f->acked;
}
static void receive_data(void *context, const uint8_t *bytes, size_t length)
{ struct fixture *f = context; assert(bytes && length); ++f->data; }
static void command(struct miwear_sar *s, uint8_t cmd, uint32_t now)
{
    struct miwear_l1_view frame = {.type = MIWEAR_COMMAND, .payload = &cmd, .length = 1};
    miwear_sar_receive(s, &frame, now);
}
static void opened(struct miwear_sar *s, struct fixture *f)
{
    memset(f, 0, sizeof *f);
    miwear_sar_init(s, send_frame, event, receive_data, f);
    assert(miwear_sar_start(s, 0) == 0 && s->state == MIWEAR_SAR_STARTING);
    command(s, 2, 1); assert(s->state == MIWEAR_SAR_READY && f->opened == 1);
}
int main(void)
{
    struct miwear_sar s; struct fixture f;
    const uint8_t payload[] = {1,1,8,1};
    opened(&s, &f);
    assert(miwear_sar_send(&s, payload, sizeof payload, 2) == 0 && s.waiting);
    unsigned calls = f.sends;
    assert(miwear_sar_send(&s, payload, sizeof payload, 2) == -2 && f.sends == calls);
    struct miwear_l1_view frame = {.type = MIWEAR_DATA, .payload = payload, .length = sizeof payload};
    miwear_sar_receive(&s, &frame, 3);
    assert(f.data == 1 && s.rx_next == 1 && s.waiting); /* receive is not TX confirmation */
    miwear_sar_receive(&s, &frame, 4); assert(f.data == 1 && f.last[2] == MIWEAR_ACK);
    frame.sequence = 2; miwear_sar_receive(&s, &frame, 5);
    assert(f.data == 1 && f.last[2] == MIWEAR_NAK && f.last[3] == 1);
    frame.frx = true; frame.sequence = 99; calls = f.sends;
    miwear_sar_receive(&s, &frame, 6); assert(f.data == 2 && s.rx_next == 1 && f.sends == calls);
    frame = (struct miwear_l1_view){.type = MIWEAR_ACK, .sequence = 0};
    miwear_sar_receive(&s, &frame, 7); assert(!s.waiting && s.tx_next == 1 && f.acked == 1);
    assert(miwear_sar_send(&s, payload, sizeof payload, 10) == 0);
    uint8_t original[sizeof payload + MIWEAR_L1_HEADER]; memcpy(original, f.last, sizeof original);
    frame.type = MIWEAR_NAK; frame.sequence = 1; calls = f.sends;
    miwear_sar_receive(&s, &frame, 11); assert(f.sends == calls + 1 && !memcmp(original, f.last, sizeof original));
    miwear_sar_receive(&s, &frame, 12); assert(f.sends == calls + 1);
    miwear_sar_receive(&s, &frame, 111);
    miwear_sar_receive(&s, &frame, 211);
    miwear_sar_receive(&s, &frame, 311);
    assert(f.sends == calls + 3 && s.state == MIWEAR_SAR_FAILED && f.errors == 1);
    calls = f.sends; miwear_sar_tick(&s, 99999); assert(f.sends == calls);
    opened(&s, &f);
    assert(miwear_sar_send(&s, payload, sizeof payload, 2) == 0);
    command(&s, 1, 3);
    assert(s.state == MIWEAR_SAR_READY && !s.waiting && !s.tx_next && f.resets == 1 && f.opened == 2);
    struct miwear_l1_view last;
    assert(miwear_l1_parse(f.last, f.length, &last) == 0 && last.type == MIWEAR_COMMAND && last.payload[0] == 2);
    calls = f.sends; miwear_sar_tick(&s, 10000); assert(f.sends == calls); /* no old data replay */
    for (unsigned i = 0; i < 256; ++i) {
        assert(miwear_sar_send(&s, payload, sizeof payload, 10001 + i) == 0);
        frame = (struct miwear_l1_view){.type = MIWEAR_ACK, .sequence = (uint8_t)i};
        miwear_sar_receive(&s, &frame, 10001 + i);
    }
    assert(s.tx_next == 0 && !s.waiting);
    for (unsigned i = 0; i < 256; ++i) {
        frame = (struct miwear_l1_view){.type = MIWEAR_DATA, .sequence = (uint8_t)i, .payload = payload, .length = sizeof payload};
        miwear_sar_receive(&s, &frame, 11000 + i);
    }
    assert(s.rx_next == 0 && f.data == 256);
    miwear_sar_close(&s); assert(s.state == MIWEAR_SAR_CLOSED);
    memset(&f, 0, sizeof f); miwear_sar_init(&s, send_frame, event, receive_data, &f);
    assert(miwear_sar_start(&s, UINT32_MAX - 2000) == 0);
    miwear_sar_tick(&s, 1998); assert(f.sends == 1);
    miwear_sar_tick(&s, 1999); assert(f.sends == 2);
    miwear_sar_tick(&s, 5999); miwear_sar_tick(&s, 9999); miwear_sar_tick(&s, 13999);
    assert(s.state == MIWEAR_SAR_FAILED && f.sends == 4);
    memset(&f, 0, sizeof f); miwear_sar_init(&s, send_frame, event, receive_data, &f);
    assert(miwear_sar_start(&s, 0) == 0);
    const uint8_t truncated[] = {2,2,2,0,3};
    frame = (struct miwear_l1_view){.type = MIWEAR_COMMAND, .payload = truncated, .length = sizeof truncated};
    miwear_sar_receive(&s, &frame, 1); assert(s.state == MIWEAR_SAR_STARTING);
    const uint8_t small_mps[] = {2,2,2,0,3,0};
    frame.payload = small_mps; frame.length = sizeof small_mps;
    miwear_sar_receive(&s, &frame, 2); assert(s.state == MIWEAR_SAR_READY && s.remote_mps == 3);
    assert(miwear_sar_send(&s, payload, 4, 3) == -1);
    assert(miwear_sar_send(&s, payload, 3, 3) == 0);
    miwear_sar_close(&s); f.send_error = -1;
    assert(miwear_sar_start(&s, 4) == -1 && s.state == MIWEAR_SAR_FAILED);
    return 0;
}
