/* Read-only camera discovery. Reuses the manufacturer-data predicate used by
 * the working Android relay. Candidate != verified Action 4 or authorization. */
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include "camera_diag.h"
#include "radio_scan.h"
LOG_MODULE_REGISTER(camera_diag, LOG_LEVEL_INF);
static struct k_spinlock lock;
static bool scanning;
static uint32_t deadline;
static bt_addr_le_t seen[8];
static size_t seen_count;
struct advert { bool dji; char name[32]; };
static bool field(struct bt_data *data, void *context)
{
    struct advert *advert = context;
    if (data->type == BT_DATA_MANUFACTURER_DATA && data->data_len >= 5 &&
        data->data[0] == 0xaa && data->data[1] == 0x08 && data->data[4] == 0xfa) advert->dji = true;
    if (data->type == BT_DATA_NAME_COMPLETE || data->type == BT_DATA_NAME_SHORTENED) {
        size_t n = MIN(data->data_len, sizeof advert->name - 1);
        for (size_t i = 0; i < n; ++i) advert->name[i] = data->data[i] < 32 || data->data[i] == 127 ? '?' : data->data[i];
        advert->name[n] = 0;
    }
    return true;
}
static void advertisement(const bt_addr_le_t *address, int8_t rssi, uint8_t type,
                           struct net_buf_simple *data)
{
    ARG_UNUSED(type);
    struct advert advert = {0};
    bt_data_parse(data, field, &advert);
    if (!advert.dji) return;
    k_spinlock_key_t key = k_spin_lock(&lock);
    if (!scanning || seen_count == ARRAY_SIZE(seen)) { k_spin_unlock(&lock, key); return; }
    for (size_t i = 0; i < seen_count; ++i)
        if (!bt_addr_le_cmp(&seen[i], address)) { k_spin_unlock(&lock, key); return; }
    seen[seen_count++] = *address;
    k_spin_unlock(&lock, key);
    char text[BT_ADDR_LE_STR_LEN];
    bt_addr_le_to_str(address, text, sizeof text);
    LOG_INF("DJI candidate %s RSSI=%d name=%s (not authorized)", text, rssi, advert.name);
}
void camera_diag_init(void) { radio_scan_register(RADIO_CAMERA, advertisement); }
bool camera_diag_command(const char *line, uint32_t now)
{
    if (!line || (strcmp(line, "camera scan") && strcmp(line, "camera stop"))) return false;
    bool start = !strcmp(line, "camera scan");
    k_spinlock_key_t key = k_spin_lock(&lock);
    scanning = start;
    if (start) { seen_count = 0; deadline = now + 10000U; }
    k_spin_unlock(&lock, key);
    radio_scan_request(RADIO_CAMERA, start);
    LOG_INF("Camera discovery %s; no connections or pairing", start ? "started (10s)" : "stopped");
    return true;
}
void camera_diag_poll(uint32_t now)
{
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool expired = scanning && (int32_t)(now - deadline) >= 0;
    size_t count = seen_count;
    if (expired) scanning = false;
    k_spin_unlock(&lock, key);
    if (expired) {
        radio_scan_request(RADIO_CAMERA, false);
        LOG_INF("Camera discovery finished: %u unique candidates", (unsigned)count);
    }
}
