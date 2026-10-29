#ifndef ACTION_RELAY_MIWEAR_APP_H
#define ACTION_RELAY_MIWEAR_APP_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define MIWEAR_APP_CONTENT_MAX 768
enum miwear_app_kind { MIWEAR_APP_INVALID, MIWEAR_APP_STATUS_REQUEST, MIWEAR_APP_MESSAGE };
struct miwear_app_message {
    enum miwear_app_kind kind;
    size_t length;
    uint8_t content[MIWEAR_APP_CONTENT_MAX];
};
/* Decode only messages addressed to the configured package AND certificate fingerprint.
 * Call only after authenticated L2 decryption. No transport or JSON execution here. */
bool miwear_app_receive(const uint8_t *, size_t, const char *package,
    const uint8_t *fingerprint, size_t fingerprint_length, struct miwear_app_message *);
/* Return zero on failure. Caller copies the result before reuse; never log payloads. */
size_t miwear_app_send(uint8_t *, size_t, const char *, const uint8_t *, size_t,
    const uint8_t *, size_t);
size_t miwear_app_status(uint8_t *, size_t, const char *, const uint8_t *, size_t, bool connected);
#endif
