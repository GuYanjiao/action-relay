#ifndef ACTION_RELAY_MIWEAR_PSA_H
#define ACTION_RELAY_MIWEAR_PSA_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
int miwear_psa_init(void);
/* Compatible with miwear_hmac_fn. Keys are volatile, never logged or persisted. */
int miwear_psa_hmac(void *, const uint8_t *, size_t, const uint8_t *, size_t, uint8_t[32]);
/* MiWear AES-128-CCM: 12-byte nonce, empty AAD, 4-byte appended tag.
 * Caller owns nonce/counter uniqueness. This helper does not manage sessions. */
int miwear_psa_ccm(bool encrypt, const uint8_t key[16], const uint8_t nonce[12],
                   const uint8_t *input, size_t length, uint8_t *output,
                   size_t capacity, size_t *output_length);
#endif
