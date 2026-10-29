#ifndef ACTION_RELAY_MIWEAR_AUTH_H
#define ACTION_RELAY_MIWEAR_AUTH_H
#include <stddef.h>
#include <stdint.h>
/* Backend returns zero on success; supplies HMAC-SHA256, not a home-grown cipher. */
typedef int (*miwear_hmac_fn)(void *,const uint8_t *,size_t,const uint8_t *,size_t,uint8_t[32]);
struct miwear_keys {
    uint8_t rx_key[16],tx_key[16],rx_nonce[4],tx_nonce[4];
};
int miwear_derive(miwear_hmac_fn hmac,void *ctx,const uint8_t authkey[16],
                  const uint8_t phone_random[16],const uint8_t watch_random[16],struct miwear_keys *out);
int miwear_verify_watch(miwear_hmac_fn hmac,void *ctx,const struct miwear_keys *keys,
                        const uint8_t phone_random[16],const uint8_t watch_random[16],const uint8_t signature[32]);
int miwear_phone_proof(miwear_hmac_fn hmac,void *ctx,const struct miwear_keys *keys,
                       const uint8_t phone_random[16],const uint8_t watch_random[16],uint8_t signature[32]);
#endif
