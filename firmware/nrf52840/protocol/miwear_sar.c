/* Initial stop-and-wait MiWear v2 session. All calls must be serialized.
 * Wire/sequence evidence: AstroBox Core dc08a079.../sar/mod.rs and layer1cmd.rs.
 * This is not authentication and does not automatically restart a failed session. */
#include <string.h>
#include "miwear_sar.h"
#define TIMEOUT_MS 4000U
#define MAX_RETRIES 3
static uint16_t le16(const uint8_t *p) { return p[0] | ((uint16_t)p[1] << 8); }
static void event(struct miwear_sar *s, enum miwear_sar_event value)
{ if (s->event) s->event(s->context, value); }
static void sequences(struct miwear_sar *s)
{
    s->tx_next = s->rx_next = s->retries = 0;
    s->waiting = s->rx_seen = s->nak_seen = false;
    s->wire_length = 0; s->remote_mps = MIWEAR_L1_MAX_PAYLOAD;
    memset(s->wire, 0, sizeof s->wire);
}
static void failed(struct miwear_sar *s)
{ sequences(s); s->state = MIWEAR_SAR_FAILED; event(s, MIWEAR_SAR_ERROR); }
static int transmit(struct miwear_sar *s, const uint8_t *wire, size_t length)
{
    if (!s->send || !length || s->send(s->context, wire, length)) { failed(s); return -1; }
    return 0;
}
static int control(struct miwear_sar *s, uint8_t type, uint8_t seq, const uint8_t *payload, size_t size)
{
    uint8_t wire[40];
    size_t count = miwear_l1_encode(wire, sizeof wire, type, false, seq, payload, size);
    return transmit(s, wire, count);
}
static size_t start_payload(uint8_t *out, uint8_t command)
{
    /* Version 1.0.0; max payload 1024; window 1; timeout 4000ms. */
    const uint8_t fields[] = {1,3,0,1,0,0, 2,2,0,0,4, 3,2,0,1,0, 4,2,0,0xa0,0x0f};
    out[0] = command; memcpy(out + 1, fields, sizeof fields);
    return sizeof fields + 1;
}
static bool start_parameters(const uint8_t *data, size_t length, uint16_t *mps)
{
    if (!data || !length) return false;
    *mps = MIWEAR_L1_MAX_PAYLOAD;
    for (size_t offset = 1; offset < length;) {
        if (length - offset < 3) return false;
        uint8_t tag = data[offset]; size_t size = le16(data + offset + 1); offset += 3;
        if (size > length - offset) return false;
        if ((tag == 1 && size != 3) || (tag >= 2 && tag <= 4 && size != 2)) return false;
        if (tag == 2) {
            uint16_t value = le16(data + offset); if (!value) return false;
            *mps = value < MIWEAR_L1_MAX_PAYLOAD ? value : MIWEAR_L1_MAX_PAYLOAD;
        }
        offset += size;
    }
    return true;
}
void miwear_sar_init(struct miwear_sar *s, int (*send)(void *, const uint8_t *, size_t),
                      void (*notify)(void *, enum miwear_sar_event),
                      void (*data)(void *, const uint8_t *, size_t), void *context)
{
    memset(s, 0, sizeof *s); s->send = send; s->event = notify; s->data = data; s->context = context;
    s->remote_mps = MIWEAR_L1_MAX_PAYLOAD;
}
int miwear_sar_start(struct miwear_sar *s, uint32_t now)
{
    if (!s || !s->send || s->state == MIWEAR_SAR_STARTING || s->state == MIWEAR_SAR_READY) return -1;
    sequences(s); s->state = MIWEAR_SAR_STARTING;
    uint8_t payload[24]; size_t size = start_payload(payload, 1);
    s->wire_length = miwear_l1_encode(s->wire, sizeof s->wire, MIWEAR_COMMAND, false, 0, payload, size);
    s->deadline = now + TIMEOUT_MS;
    return transmit(s, s->wire, s->wire_length);
}
int miwear_sar_send(struct miwear_sar *s, const uint8_t *payload, size_t length, uint32_t now)
{
    if (!s || s->state != MIWEAR_SAR_READY || (!payload && length) || length > s->remote_mps) return -1;
    if (s->waiting) return -2;
    s->wire_length = miwear_l1_encode(s->wire, sizeof s->wire, MIWEAR_DATA, false, s->tx_next, payload, length);
    s->waiting = true; s->retries = 0; s->nak_seen = false; s->deadline = now + TIMEOUT_MS;
    return transmit(s, s->wire, s->wire_length);
}
static void acknowledge(struct miwear_sar *s)
{
    s->waiting = false; s->wire_length = 0; s->retries = 0;
    ++s->tx_next; event(s, MIWEAR_SAR_TX_ACKED);
}
static void retry(struct miwear_sar *s, uint32_t now)
{
    if (s->retries >= MAX_RETRIES) { failed(s); return; }
    ++s->retries; s->deadline = now + TIMEOUT_MS;
    (void)transmit(s, s->wire, s->wire_length);
}
void miwear_sar_receive(struct miwear_sar *s, const struct miwear_l1_view *frame, uint32_t now)
{
    if (!s || !frame || (!frame->payload && frame->length)) return;
    if (frame->type == MIWEAR_COMMAND) {
        uint16_t mps;
        if (!start_parameters(frame->payload, frame->length, &mps)) return;
        uint8_t command = frame->payload[0];
        if (command == 2 && s->state == MIWEAR_SAR_STARTING) {
            sequences(s); s->remote_mps = mps; s->state = MIWEAR_SAR_READY;
            event(s, MIWEAR_SAR_OPENED);
        } else if (command == 1 && s->state != MIWEAR_SAR_CLOSED && s->state != MIWEAR_SAR_FAILED) {
            bool reset = s->state == MIWEAR_SAR_READY;
            sequences(s); s->remote_mps = mps; s->state = MIWEAR_SAR_READY;
            if (reset) event(s, MIWEAR_SAR_RESET);
            uint8_t payload[24]; size_t size = start_payload(payload, 2);
            if (!control(s, MIWEAR_COMMAND, 0, payload, size)) event(s, MIWEAR_SAR_OPENED);
        } else if (command == 3 && s->state == MIWEAR_SAR_READY) {
            uint8_t response = 4;
            if (!control(s, MIWEAR_COMMAND, 0, &response, 1)) miwear_sar_close(s);
        }
        return;
    }
    if (s->state != MIWEAR_SAR_READY) return;
    if (frame->type == MIWEAR_ACK) {
        if (s->waiting && frame->sequence == s->tx_next) acknowledge(s);
    } else if (frame->type == MIWEAR_NAK && s->waiting) {
        if (frame->sequence == (uint8_t)(s->tx_next + 1)) acknowledge(s);
        else if (frame->sequence == s->tx_next) {
            if (s->nak_seen && (uint32_t)(now - s->last_nak) < 100) return;
            s->nak_seen = true; s->last_nak = now; retry(s, now);
        } else failed(s);
    } else if (frame->type == MIWEAR_DATA) {
        bool bypass = frame->frx || (frame->length && (frame->payload[0] == 7 || frame->payload[0] == 10));
        if (!bypass) {
            if (frame->sequence != s->rx_next) {
                if ((uint8_t)(frame->sequence - s->rx_next) < 128)
                    (void)control(s, MIWEAR_NAK, s->rx_next, NULL, 0);
                else if (s->rx_seen) (void)control(s, MIWEAR_ACK, (uint8_t)(s->rx_next - 1), NULL, 0);
                return;
            }
            ++s->rx_next; s->rx_seen = true;
            if (control(s, MIWEAR_ACK, frame->sequence, NULL, 0)) return;
        }
        if (s->data) s->data(s->context, frame->payload, frame->length);
    }
}
void miwear_sar_tick(struct miwear_sar *s, uint32_t now)
{
    if (s && (s->state == MIWEAR_SAR_STARTING || (s->state == MIWEAR_SAR_READY && s->waiting)) &&
        (int32_t)(now - s->deadline) >= 0) retry(s, now);
}
void miwear_sar_close(struct miwear_sar *s)
{
    if (!s) return;
    sequences(s); s->state = MIWEAR_SAR_CLOSED; event(s, MIWEAR_SAR_RESET);
}
