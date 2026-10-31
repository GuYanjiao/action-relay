#include <assert.h>
#include <string.h>
#include "../src/miwear_runtime.c"
#include "../../protocol/miwear_psa.h"
static struct miwear_gatt_callbacks saved;
static unsigned wire_count, transport_closes;
static uint32_t transport_epoch;
uint32_t k_uptime_get_32(void) { return 100; }
int k_work_reschedule_for_queue(struct k_work_q *q, struct k_work_delayable *w, int delay)
{ (void)q; (void)w; (void)delay; return 0; }
int k_msgq_put(struct k_msgq *q, const void *item, int timeout)
{
    (void)timeout; if (q->count == q->capacity) return -ENOMSG;
    memcpy(q->bytes + ((q->head + q->count) % q->capacity) * q->item_size, item, q->item_size); ++q->count; return 0;
}
int k_msgq_get(struct k_msgq *q, void *item, int timeout)
{
    (void)timeout; if (!q->count) return -ENOMSG;
    memcpy(item, q->bytes + q->head * q->item_size, q->item_size); q->head = (q->head + 1) % q->capacity; --q->count; return 0;
}
void miwear_gatt_init(const struct miwear_gatt_callbacks *cb) { saved = *cb; }
int miwear_gatt_send_for(uint32_t epoch, const uint8_t *data, size_t length)
{ assert(data && length && epoch == transport_epoch); ++wire_count; return 0; }
void miwear_gatt_close(void) { ++transport_closes; }
static bool zero(const void *data, size_t length)
{ const uint8_t *p = data; for (size_t i = 0; i < length; ++i) if (p[i]) return false; return true; }
int main(void)
{
    assert(!miwear_psa_init()); miwear_runtime_init();
    const char *test_key = "000102030405060708090a0b0c0d0e0f";
    assert(miwear_runtime_auth(test_key) == -ENOTCONN);
    transport_epoch = 11; saved.ready(transport_epoch);
    assert(miwear_runtime_auth("bad") == -EINVAL);
    assert(miwear_runtime_auth("000102030405060708090a0b0c0d0e0g") == -EINVAL);
    assert(!miwear_runtime_auth(test_key) && key_pending);
    assert(miwear_runtime_auth(test_key) == -EBUSY);
    pump(&worker.work);
    assert(!key_pending && zero(staged_key, 16) && session.state == MIWEAR_COMPANION_SAR && wire_count == 1);
    saved.closed(0); assert(zero(staged_key, 16) && !authenticated);
    transport_epoch = 12; saved.ready(transport_epoch);
    uint8_t byte = 1;
    assert(send_wire(NULL, &byte, 1) == -ENOTCONN); // old worker cannot target the new GATT generation
    pump(&worker.work); assert(session.state == MIWEAR_COMPANION_IDLE && zero(session.key, 16));
    assert(!miwear_runtime_auth(test_key));
    uint8_t notice[20] = {0};
    for (int i = 0; i < 9; ++i) saved.received(notice, sizeof notice);
    assert(overflow);
    pump(&worker.work);
    assert(transport_closes == 1 && !link_ready && zero(staged_key, 16) && zero(session.key, 16));
    assert(wire_count == 1 && !key_pending && !authenticating && !authenticated);
    assert(miwear_runtime_auth(test_key) == -ENOTCONN);
    return 0;
}
