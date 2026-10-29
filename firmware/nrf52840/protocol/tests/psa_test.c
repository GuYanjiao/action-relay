#include <assert.h>
#include <string.h>
#include <openssl/evp.h>
#include "../miwear_psa.h"
#include "../miwear_auth.h"
#include "../miwear_l1.h"
#include "vectors.h"
static void openssl_ccm(const uint8_t key[16], const uint8_t nonce[12],
                         const uint8_t *input, size_t size, uint8_t *output)
{
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new(); assert(ctx);
    assert(EVP_EncryptInit_ex(ctx, EVP_aes_128_ccm(), NULL, NULL, NULL) == 1);
    assert(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_CCM_SET_IVLEN, 12, NULL) == 1);
    assert(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_CCM_SET_TAG, 4, NULL) == 1);
    assert(EVP_EncryptInit_ex(ctx, NULL, NULL, key, nonce) == 1);
    int count = 0, last = 0;
    assert(EVP_EncryptUpdate(ctx, NULL, &count, NULL, (int)size) == 1);
    assert(EVP_EncryptUpdate(ctx, output, &count, input, (int)size) == 1);
    assert((size_t)count == size);
    assert(EVP_EncryptFinal_ex(ctx, output + count, &last) == 1 && last == 0);
    assert(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_CCM_GET_TAG, 4, output + size) == 1);
    EVP_CIPHER_CTX_free(ctx);
}
int main(void)
{
    assert(miwear_psa_init() == 0);
    uint8_t key[16], phone[16], watch[16], nonce[12], proof[32];
    for (int i = 0; i < 16; ++i) { key[i] = (uint8_t)i; phone[i] = (uint8_t)(i + 16); watch[i] = (uint8_t)(i + 32); }
    for (int i = 0; i < 12; ++i) nonce[i] = (uint8_t)(0xa0 + i);
    struct miwear_keys keys;
    assert(miwear_derive(miwear_psa_hmac, NULL, key, phone, watch, &keys) == 0);
    assert(sizeof keys == 40 && !memcmp(&keys, gold_keys, 40));
    assert(miwear_verify_watch(miwear_psa_hmac, NULL, &keys, phone, watch, gold_watch) == 0);
    assert(miwear_phone_proof(miwear_psa_hmac, NULL, &keys, phone, watch, proof) == 0);
    assert(!memcmp(proof, gold_phone, 32));
    uint8_t plain[MIWEAR_L1_MAX_PAYLOAD], expected[sizeof plain + 4], cipher[sizeof plain + 4], decoded[sizeof plain];
    for (size_t i = 0; i < sizeof plain; ++i) plain[i] = (uint8_t)i;
    const size_t sizes[] = {0, 1, 15, 16, 17, 127, sizeof plain};
    for (size_t i = 0; i < sizeof sizes / sizeof sizes[0]; ++i) {
        size_t size = sizes[i], written = 99;
        EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new(); assert(ctx);
        assert(EVP_EncryptInit_ex(ctx, EVP_aes_128_ctr(), NULL, key, key) == 1);
        int count = 0, last = 0;
        assert(EVP_EncryptUpdate(ctx, expected, &count, plain, (int)size) == 1);
        assert(EVP_EncryptFinal_ex(ctx, expected + count, &last) == 1);
        assert((size_t)(count + last) == size); EVP_CIPHER_CTX_free(ctx);
        assert(miwear_psa_ctr(key, key, plain, size, cipher, sizeof cipher, &written) == 0);
        assert(written == size && !memcmp(cipher, expected, size));
        assert(miwear_psa_ctr(key, key, cipher, size, decoded, sizeof decoded, &written) == 0);
        assert(written == size && !memcmp(decoded, plain, size));
    }
    for (size_t i = 0; i < sizeof sizes / sizeof sizes[0]; ++i) {
        size_t size = sizes[i], written = 99;
        openssl_ccm(key, nonce, plain, size, expected);
        assert(miwear_psa_ccm(true, key, nonce, plain, size, cipher, sizeof cipher, &written) == 0);
        assert(written == size + 4 && !memcmp(cipher, expected, written));
        assert(miwear_psa_ccm(false, key, nonce, cipher, written, decoded, sizeof decoded, &written) == 0);
        assert(written == size && !memcmp(decoded, plain, size));
        for (size_t tag = size; tag < size + 4; ++tag) {
            cipher[tag] ^= 1; memset(decoded, 0x55, sizeof decoded);
            assert(miwear_psa_ccm(false, key, nonce, cipher, size + 4, decoded, sizeof decoded, &written) != 0);
            assert(written == 0);
            for (size_t j = 0; j < size; ++j) assert(decoded[j] == 0);
            cipher[tag] ^= 1;
        }
    }
    size_t written = 99;
    assert(miwear_psa_ctr(key, key, plain, sizeof plain, cipher, 1, &written) != 0 && written == 0);
    assert(miwear_psa_ccm(false, key, nonce, cipher, 3, decoded, sizeof decoded, &written) != 0 && written == 0);
    assert(miwear_psa_ccm(true, key, nonce, plain, sizeof plain, cipher, 1, &written) != 0 && written == 0);
    assert(miwear_psa_hmac(NULL, NULL, 16, plain, 1, proof) != 0);
    for (size_t i = 0; i < sizeof proof; ++i) assert(proof[i] == 0);
    return 0;
}
