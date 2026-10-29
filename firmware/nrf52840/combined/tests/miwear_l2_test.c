#include <assert.h>
#include <string.h>
#include "../src/miwear_l2.h"
#include "../../protocol/miwear_psa.h"
int main(void)
{
    assert(!miwear_psa_init());
    struct miwear_auth_session session = {0};
    uint8_t frame[1024], decoded[1024], encrypted[1024];
    const uint8_t confirm[] = {8,1,16,27,26,5,0x8a,2,2,8,1};
    const uint8_t app[] = {8,20,16,6,0xb2,1,8,0x2a,6,10,1,'a',18,1,0};
    size_t written = 0, count = miwear_l2_auth_write(frame, sizeof frame, confirm, sizeof confirm);
    assert(count == sizeof confirm + 2 && frame[0] == 1 && frame[1] == 1);
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_INVALID);
    session.state = MIWEAR_AUTH_CONFIRMING;
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_AUTH);
    assert(written == sizeof confirm && !memcmp(decoded, confirm, written));
    assert(!miwear_l2_app_write(&session, encrypted, sizeof encrypted, app, sizeof app));
    session.state = MIWEAR_AUTH_READY;
    for (size_t i = 0; i < 16; ++i) { session.keys.tx_key[i] = (uint8_t)i; session.keys.rx_key[i] = (uint8_t)(16 + i); }
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_INVALID);
    count = miwear_l2_app_write(&session, encrypted, sizeof encrypted, app, sizeof app);
    assert(count == sizeof app + 2 && encrypted[0] == 1 && encrypted[1] == 2);
    assert(!miwear_psa_ctr(session.keys.tx_key, session.keys.tx_key, encrypted + 2, count - 2, decoded, sizeof decoded, &written));
    assert(written == sizeof app && !memcmp(decoded, app, written));
    frame[0] = 1; frame[1] = 2;
    assert(!miwear_psa_ctr(session.keys.rx_key, session.keys.rx_key, app, sizeof app, frame + 2, sizeof frame - 2, &written));
    count = written + 2;
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_APP);
    assert(written == sizeof app && !memcmp(decoded, app, written));
    frame[0] = 7;
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_INVALID);
    frame[0] = 1; frame[1] = 3;
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_INVALID);
    frame[1] = 2; session.state = MIWEAR_AUTH_FAILED;
    assert(miwear_l2_receive(&session, frame, count, decoded, sizeof decoded, &written) == MIWEAR_L2_INVALID);
    session.state = MIWEAR_AUTH_READY;
    assert(!miwear_l2_app_write(&session, frame, 2, app, sizeof app));
    assert(miwear_l2_receive(&session, frame, 1, decoded, sizeof decoded, &written) == MIWEAR_L2_INVALID);
    return 0;
}
