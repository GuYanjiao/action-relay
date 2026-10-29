#include "hr_measurement.h"
static uint16_t le16(const uint8_t *p) { return p[0] | ((uint16_t)p[1] << 8); }
bool hr_parse(const uint8_t *data, size_t len, struct hr_measurement *out)
{
    if (!data || !out || len < 2) return false;
    struct hr_measurement value = {0};
    uint8_t flags = data[0];
    size_t pos = (flags & 1) ? 3 : 2;
    if (len < pos) return false;
    uint16_t bpm = (flags & 1) ? le16(data + 1) : data[1];
    if (bpm > 255) return false;
    value.bpm = bpm;
    value.contact_lost = (flags & 4) && !(flags & 2);
    if (flags & 8) {
        if (len - pos < 2) return false;
        pos += 2;
    }
    if (flags & 16) {
        if (len == pos || (len - pos) % 2) return false;
        while (pos < len) {
            uint16_t rr = le16(data + pos);
            if (!rr) return false;
            value.rr_count++;
            value.rr_ticks += rr;
            pos += 2;
        }
    } else if (pos != len) {
        return false;
    }
    *out = value;
    return true;
}
