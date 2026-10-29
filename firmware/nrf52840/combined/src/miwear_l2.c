/* Wire facts: Core dc08a079, packet/v2/layer2.rs and packet/cipher.rs.
 * PB channel 1, write 1 / encrypted write 2. V2 app CTR uses directional key
 * as IV per peer implementation. No invented counter or plaintext fallback.
 * CTR alone has no integrity; higher layers must validate PB and session/ticket. */
#include <string.h>
#include "miwear_l2.h"
#include "miwear_pb.h"
#include "../../protocol/miwear_l1.h"
#include "../../protocol/miwear_psa.h"
size_t miwear_l2_auth_write(uint8_t *out, size_t capacity, const uint8_t *pb, size_t length)
{
    if (!out || !pb || !length || length > 256 || capacity < length + 2) return 0;
    out[0] = 1; out[1] = 1;
    memcpy(out + 2, pb, length);
    return length + 2;
}
size_t miwear_l2_app_write(const struct miwear_auth_session *s, uint8_t *out,
    size_t capacity, const uint8_t *pb, size_t length)
{
    if (!s || s->state != MIWEAR_AUTH_READY || !out || !pb || !length ||
        length > MIWEAR_L1_MAX_PAYLOAD - 2 || capacity < length + 2) return 0;
    size_t written = 0;
    if (miwear_psa_ctr(s->keys.tx_key, s->keys.tx_key, pb, length, out + 2, capacity - 2, &written)) return 0;
    out[0] = 1; out[1] = 2;
    return written + 2;
}
enum miwear_l2_kind miwear_l2_receive(const struct miwear_auth_session *s,
    const uint8_t *input, size_t length, uint8_t *out, size_t capacity, size_t *written)
{
    if (!written) return MIWEAR_L2_INVALID;
    *written = 0;
    if (!s || !input || !out || length <= 2 || length > MIWEAR_L1_MAX_PAYLOAD ||
        input[0] != 1 || capacity < length - 2) return MIWEAR_L2_INVALID;
    if (s->state == MIWEAR_AUTH_READY) {
        if (input[1] != 2 || miwear_psa_ctr(s->keys.rx_key, s->keys.rx_key,
            input + 2, length - 2, out, capacity, written)) return MIWEAR_L2_INVALID;
        return MIWEAR_L2_APP;
    }
    if ((s->state != MIWEAR_AUTH_VERIFYING && s->state != MIWEAR_AUTH_CONFIRMING) || input[1] != 1 || length > 258)
        return MIWEAR_L2_INVALID;
    enum miwear_pb_auth_kind kind = miwear_pb_auth_kind(input + 2, length - 2);
    if (kind != MIWEAR_PB_DEVICE_VERIFY && kind != MIWEAR_PB_DEVICE_CONFIRM && kind != MIWEAR_PB_ACCOUNT_ERROR)
        return MIWEAR_L2_INVALID;
    memcpy(out, input + 2, length - 2); *written = length - 2;
    return MIWEAR_L2_AUTH;
}
