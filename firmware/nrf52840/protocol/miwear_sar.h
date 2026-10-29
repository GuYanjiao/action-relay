#ifndef ACTION_RELAY_MIWEAR_SAR_H
#define ACTION_RELAY_MIWEAR_SAR_H
#include "miwear_l1.h"
enum miwear_sar_state { MIWEAR_SAR_CLOSED, MIWEAR_SAR_STARTING, MIWEAR_SAR_READY, MIWEAR_SAR_FAILED };
enum miwear_sar_event { MIWEAR_SAR_OPENED, MIWEAR_SAR_RESET, MIWEAR_SAR_ERROR, MIWEAR_SAR_TX_ACKED };
struct miwear_sar {
    enum miwear_sar_state state;
    uint8_t tx_next, rx_next, retries;
    bool waiting, rx_seen, nak_seen;
    uint16_t remote_mps;
    uint32_t deadline, last_nak;
    uint8_t wire[MIWEAR_L1_HEADER + MIWEAR_L1_MAX_PAYLOAD];
    size_t wire_length;
    /* send must copy/enqueue the entire frame before returning zero. No reentry. */
    int (*send)(void *, const uint8_t *, size_t);
    void (*event)(void *, enum miwear_sar_event);
    void (*data)(void *, const uint8_t *, size_t);
    void *context;
};
void miwear_sar_init(struct miwear_sar *, int (*)(void *, const uint8_t *, size_t),
                      void (*)(void *, enum miwear_sar_event),
                      void (*)(void *, const uint8_t *, size_t), void *);
int miwear_sar_start(struct miwear_sar *, uint32_t now_ms);
/* One in-flight frame, no hidden application queue. -2 means busy. */
int miwear_sar_send(struct miwear_sar *, const uint8_t *, size_t, uint32_t now_ms);
void miwear_sar_receive(struct miwear_sar *, const struct miwear_l1_view *, uint32_t now_ms);
void miwear_sar_tick(struct miwear_sar *, uint32_t now_ms);
void miwear_sar_close(struct miwear_sar *);
#endif
