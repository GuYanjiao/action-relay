#ifndef ACTION_RELAY_MIWEAR_PB_H
#define ACTION_RELAY_MIWEAR_PB_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
/* No transport, pairing, key storage or account mutation in these helpers. */
size_t miwear_pb_verify(uint8_t *, size_t, const uint8_t random[16]);
bool miwear_pb_device_verify(const uint8_t *, size_t, uint8_t random[16], uint8_t signature[32]);
size_t miwear_pb_confirm(uint8_t *, size_t, const uint8_t signature[32], const uint8_t *, size_t);
bool miwear_pb_device_confirm(const uint8_t *, size_t);
size_t miwear_pb_companion(uint8_t *, size_t, uint32_t device_type, const char *name,
                            bool has_capability, uint32_t capability);
#endif
