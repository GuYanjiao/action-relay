#ifndef HR_MEASUREMENT_H
#define HR_MEASUREMENT_H
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
struct hr_measurement {
    uint8_t bpm;
    bool contact_lost;
    uint16_t rr_count;
    uint32_t rr_ticks;
};
/* RR and ANT beat timestamps both use units of 1/1024 second. */
bool hr_parse(const uint8_t *data, size_t len, struct hr_measurement *out);
#endif
