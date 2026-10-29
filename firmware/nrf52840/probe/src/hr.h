#ifndef ACTION_RELAY_HR_H
#define ACTION_RELAY_HR_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
/* Heart Rate Measurement: bit 0 selects 8/16-bit little-endian BPM. */
static inline bool hr_decode(const uint8_t *data, size_t size, uint16_t *bpm)
{
    if (!data || !bpm || size < 2) return false;
    if (data[0] & 1) {
        if (size < 3) return false;
        *bpm = (uint16_t)data[1] | ((uint16_t)data[2] << 8);
    } else *bpm = data[1];
    return true;
}
#endif
