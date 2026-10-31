#include <errno.h>
#include <string.h>
#include <psa/crypto.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include "miwear_runtime.h"
#include "miwear_companion.h"
#include "miwear_gatt.h"
LOG_MODULE_REGISTER(miwear_runtime, LOG_LEVEL_INF);
static struct k_spinlock lock;
static struct miwear_companion session;
static struct k_work_q queue;
K_THREAD_STACK_DEFINE(protocol_stack, 6144);
static struct k_work_delayable worker;
static bool initialized, link_ready, key_pending, authenticating, authenticated, overflow;
static uint32_t generation, worker_generation;
static uint32_t transport_generation, worker_transport_generation;
static uint8_t staged_key[16];
struct rx_chunk { uint32_t generation; uint16_t length; uint8_t bytes[512]; };
K_MSGQ_DEFINE(receive_queue, sizeof(struct rx_chunk), 8, 4);
static void wipe(void *data, size_t length) { volatile uint8_t *p = data; while (length--) *p++ = 0; }
static void kick(void) { k_work_reschedule_for_queue(&queue, &worker, K_NO_WAIT); }
static void ready(uint32_t transport_epoch)
{
    k_spinlock_key_t key = k_spin_lock(&lock);
    ++generation; transport_generation = transport_epoch; link_ready = true; key_pending = authenticating = authenticated = overflow = false;
    wipe(staged_key, sizeof staged_key);
    k_spin_unlock(&lock, key); kick();
    LOG_INF("S4 channel available; waiting for a local one-use authentication key");
}
static void closed(int reason)
{
    ARG_UNUSED(reason);
    k_spinlock_key_t key = k_spin_lock(&lock);
    ++generation; link_ready = key_pending = authenticating = authenticated = false;
    wipe(staged_key, sizeof staged_key);
    k_spin_unlock(&lock, key); kick();
}
static void received(const uint8_t *bytes, size_t length)
{
    struct rx_chunk chunk = {0};
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool accept = link_ready; chunk.generation = generation;
    k_spin_unlock(&lock, key);
    if (!accept) return;
    if (length > sizeof chunk.bytes) { key = k_spin_lock(&lock); overflow = true; k_spin_unlock(&lock, key); kick(); return; }
    chunk.length = length; memcpy(chunk.bytes, bytes, length);
    if (k_msgq_put(&receive_queue, &chunk, K_NO_WAIT)) { key = k_spin_lock(&lock); overflow = true; k_spin_unlock(&lock, key); }
    wipe(&chunk, sizeof chunk); kick();
}
static int send_wire(void *context, const uint8_t *bytes, size_t length)
{
    ARG_UNUSED(context);
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool valid = link_ready && worker_generation == generation;
    k_spin_unlock(&lock, key);
    return valid ? miwear_gatt_send_for(worker_transport_generation, bytes, length) : -ENOTCONN;
}
static void changed(void *context, enum miwear_companion_state value)
{
    ARG_UNUSED(context);
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool current = link_ready && worker_generation == generation;
    if (current) {
        authenticated = value == MIWEAR_COMPANION_READY;
        authenticating = value == MIWEAR_COMPANION_SAR || value == MIWEAR_COMPANION_AUTH;
    }
    k_spin_unlock(&lock, key);
    if (current && value == MIWEAR_COMPANION_READY) LOG_INF("S4 authentication confirmed by device");
    if (current && value == MIWEAR_COMPANION_FAILED) LOG_WRN("S4 authentication failed; key cleared, no automatic retry");
}
static void application(void *context, const uint8_t *bytes, size_t length)
{ ARG_UNUSED(context); ARG_UNUSED(bytes); LOG_INF("Authenticated application PB received (%u bytes); app routing not enabled", (unsigned)length); }
static void pump(struct k_work *work)
{
    ARG_UNUSED(work);
    uint8_t key_bytes[16] = {0}, nonce[16] = {0};
    k_spinlock_key_t key = k_spin_lock(&lock);
    uint32_t current_generation = generation;
    uint32_t current_transport_generation = transport_generation;
    bool usable = link_ready;
    bool start = usable && key_pending;
    bool overrun = overflow; overflow = false;
    if (start) { memcpy(key_bytes, staged_key, 16); wipe(staged_key, 16); key_pending = false; }
    k_spin_unlock(&lock, key);
    if (worker_generation != current_generation || !usable) {
        miwear_companion_close(&session); worker_generation = current_generation;
        worker_transport_generation = current_transport_generation;
    }
    if (overrun) {
        key = k_spin_lock(&lock); link_ready = key_pending = authenticating = authenticated = false;
        wipe(staged_key, sizeof staged_key); ++generation; k_spin_unlock(&lock, key);
        miwear_companion_close(&session); miwear_gatt_close(); usable = false;
    }
    if (start && usable) {
        if (psa_generate_random(nonce, sizeof nonce) != PSA_SUCCESS ||
            miwear_companion_start(&session, key_bytes, nonce, k_uptime_get_32())) {
            key = k_spin_lock(&lock); authenticating = false; k_spin_unlock(&lock, key);
            LOG_WRN("Unable to begin S4 authentication; key cleared");
        }
    }
    wipe(key_bytes, sizeof key_bytes); wipe(nonce, sizeof nonce);
    struct rx_chunk chunk;
    for (unsigned count = 0; count < 8 && !k_msgq_get(&receive_queue, &chunk, K_NO_WAIT); ++count) {
        if (usable && chunk.generation == worker_generation)
            miwear_companion_receive(&session, chunk.bytes, chunk.length, k_uptime_get_32());
        wipe(&chunk, sizeof chunk);
    }
    if (usable) { miwear_companion_poll(&session, k_uptime_get_32()); k_work_reschedule_for_queue(&queue, &worker, K_MSEC(20)); }
}
void miwear_runtime_init(void)
{
    if (initialized) return;
    const struct miwear_companion_ops ops = {.send = send_wire, .state = changed, .application = application};
    miwear_companion_init(&session, &ops);
    k_work_queue_start(&queue, protocol_stack, K_THREAD_STACK_SIZEOF(protocol_stack), 8, NULL);
    k_work_init_delayable(&worker, pump);
    const struct miwear_gatt_callbacks cb = {.ready = ready, .closed = closed, .received = received};
    initialized = true; miwear_gatt_init(&cb);
}
static int digit(char value)
{ if (value >= '0' && value <= '9') return value - '0'; if (value >= 'a' && value <= 'f') return value - 'a' + 10; if (value >= 'A' && value <= 'F') return value - 'A' + 10; return -1; }
int miwear_runtime_auth(const char *hex_key)
{
    if (!initialized || !hex_key || strlen(hex_key) != 32) return -EINVAL;
    uint8_t key_bytes[16] = {0};
    for (size_t i = 0; i < 16; ++i) {
        int high = digit(hex_key[i * 2]), low = digit(hex_key[i * 2 + 1]);
        if (high < 0 || low < 0) { wipe(key_bytes, sizeof key_bytes); return -EINVAL; }
        key_bytes[i] = (uint8_t)((high << 4) | low);
    }
    k_spinlock_key_t key = k_spin_lock(&lock);
    int error = !link_ready ? -ENOTCONN : key_pending || authenticating || authenticated ? -EBUSY : 0;
    if (!error) { memcpy(staged_key, key_bytes, 16); key_pending = authenticating = true; }
    k_spin_unlock(&lock, key); wipe(key_bytes, sizeof key_bytes); if (!error) kick(); return error;
}
