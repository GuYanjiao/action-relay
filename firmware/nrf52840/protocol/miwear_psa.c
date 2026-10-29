/* PSA Crypto backend; no custom cipher, device keys, logs or persistent objects. */
#include <psa/crypto.h>
#include "miwear_psa.h"
#include "miwear_l1.h"
static void wipe(uint8_t *p, size_t length)
{ volatile uint8_t *bytes = p; while (length--) *bytes++ = 0; }
int miwear_psa_init(void) { return psa_crypto_init() == PSA_SUCCESS ? 0 : -1; }
int miwear_psa_ctr(const uint8_t key[16], const uint8_t iv[16], const uint8_t *input,
    size_t length, uint8_t *output, size_t capacity, size_t *output_length)
{
    if (!output_length) return -1;
    *output_length = 0;
    if (!key || !iv || (!input && length) || !output || length > MIWEAR_L1_MAX_PAYLOAD || capacity < length) return -1;
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_AES);
    psa_set_key_bits(&attributes, 128);
    psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_ENCRYPT);
    psa_set_key_algorithm(&attributes, PSA_ALG_CTR);
    psa_key_id_t id = 0;
    psa_status_t status = psa_import_key(&attributes, key, 16, &id);
    psa_reset_key_attributes(&attributes);
    psa_cipher_operation_t operation = PSA_CIPHER_OPERATION_INIT;
    size_t written = 0, final = 0;
    if (status == PSA_SUCCESS) status = psa_cipher_encrypt_setup(&operation, id, PSA_ALG_CTR);
    if (status == PSA_SUCCESS) status = psa_cipher_set_iv(&operation, iv, 16);
    if (status == PSA_SUCCESS) status = psa_cipher_update(&operation, input, length, output, capacity, &written);
    if (status == PSA_SUCCESS) status = psa_cipher_finish(&operation, output + written, capacity - written, &final);
    if (psa_cipher_abort(&operation) != PSA_SUCCESS) status = PSA_ERROR_BAD_STATE;
    if (id && psa_destroy_key(id) != PSA_SUCCESS) status = PSA_ERROR_BAD_STATE;
    if (status != PSA_SUCCESS || written + final != length) { wipe(output, length); return -1; }
    *output_length = written + final;
    return 0;
}
int miwear_psa_hmac(void *context, const uint8_t *key, size_t key_length,
                     const uint8_t *input, size_t length, uint8_t output[32])
{
    (void)context;
    if (!output) return -1;
    wipe(output, 32);
    if (!key || (key_length != 16 && key_length != 32) || (!input && length)) return -1;
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_HMAC);
    psa_set_key_bits(&attributes, key_length * 8);
    psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_SIGN_MESSAGE);
    psa_set_key_algorithm(&attributes, PSA_ALG_HMAC(PSA_ALG_SHA_256));
    psa_key_id_t id = 0;
    psa_status_t status = psa_import_key(&attributes, key, key_length, &id);
    psa_reset_key_attributes(&attributes);
    size_t written = 0;
    if (status == PSA_SUCCESS) {
        status = psa_mac_compute(id, PSA_ALG_HMAC(PSA_ALG_SHA_256), input, length, output, 32, &written);
        psa_status_t destroyed = psa_destroy_key(id);
        if (destroyed != PSA_SUCCESS) status = destroyed;
    }
    if (status != PSA_SUCCESS || written != 32) { wipe(output, 32); return -1; }
    return 0;
}
int miwear_psa_ccm(bool encrypt, const uint8_t key[16], const uint8_t nonce[12],
                   const uint8_t *input, size_t length, uint8_t *output,
                   size_t capacity, size_t *output_length)
{
    if (!output_length) return -1;
    *output_length = 0;
    if (!key || !nonce || !output || (!input && length) ||
        length > MIWEAR_L1_MAX_PAYLOAD + (encrypt ? 0U : 4U) || (!encrypt && length < 4)) return -1;
    size_t needed = encrypt ? length + 4 : length - 4;
    if (capacity < needed) return -1;
    psa_algorithm_t algorithm = PSA_ALG_AEAD_WITH_SHORTENED_TAG(PSA_ALG_CCM, 4);
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_AES);
    psa_set_key_bits(&attributes, 128);
    psa_set_key_usage_flags(&attributes, encrypt ? PSA_KEY_USAGE_ENCRYPT : PSA_KEY_USAGE_DECRYPT);
    psa_set_key_algorithm(&attributes, algorithm);
    psa_key_id_t id = 0;
    psa_status_t status = psa_import_key(&attributes, key, 16, &id);
    psa_reset_key_attributes(&attributes);
    size_t written = 0;
    if (status == PSA_SUCCESS) {
        if (encrypt) status = psa_aead_encrypt(id, algorithm, nonce, 12, NULL, 0,
                                               input, length, output, capacity, &written);
        else status = psa_aead_decrypt(id, algorithm, nonce, 12, NULL, 0,
                                       input, length, output, capacity, &written);
        psa_status_t destroyed = psa_destroy_key(id);
        if (destroyed != PSA_SUCCESS) status = destroyed;
    }
    if (status != PSA_SUCCESS || written != needed) { wipe(output, needed); return -1; }
    *output_length = written;
    return 0;
}
