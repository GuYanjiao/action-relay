#include <stdint.h>
static inline uint16_t sys_get_le16(const uint8_t *p) { return p[0] | ((uint16_t)p[1] << 8); }
