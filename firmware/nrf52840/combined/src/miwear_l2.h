#ifndef ACTION_RELAY_MIWEAR_L2_H
#define ACTION_RELAY_MIWEAR_L2_H
#include "miwear_session.h"
enum miwear_l2_kind { MIWEAR_L2_INVALID, MIWEAR_L2_AUTH, MIWEAR_L2_APP };
/* Caller provides separate input/output buffers. Frames fit one bounded SAR payload.
 * Application data is forbidden before explicit authentication confirmation. */
size_t miwear_l2_auth_write(uint8_t *, size_t, const uint8_t *, size_t);
size_t miwear_l2_app_write(const struct miwear_auth_session *, uint8_t *, size_t, const uint8_t *, size_t);
enum miwear_l2_kind miwear_l2_receive(const struct miwear_auth_session *,
    const uint8_t *, size_t, uint8_t *, size_t, size_t *);
#endif
