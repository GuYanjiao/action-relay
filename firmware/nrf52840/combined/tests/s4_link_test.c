#include <assert.h>
#include <errno.h>
#include <string.h>
#include "../src/s4_link.h"
struct bt_conn *bt_conn_ref(struct bt_conn *connection)
{ assert(connection->refs); ++connection->refs; return connection; }
void bt_conn_unref(struct bt_conn *connection)
{ assert(connection->refs); --connection->refs; }
int bt_conn_get_info(const struct bt_conn *connection, struct bt_conn_info *info)
{ info->state = connection->state; return 0; }
const bt_addr_le_t *bt_conn_get_dst(const struct bt_conn *connection) { return &connection->address; }
int bt_addr_le_cmp(const bt_addr_le_t *a, const bt_addr_le_t *b) { return memcmp(a, b, sizeof *a); }
int main(void)
{
    struct bt_conn watch = {.id = 1, .state = BT_CONN_STATE_CONNECTED, .refs = 1};
    struct bt_conn other = {.id = 2, .state = BT_CONN_STATE_CONNECTED, .refs = 1};
    assert(s4_link_adopt(&watch, S4_CLIENT_HEART) == 0 && watch.refs == 2);
    assert(s4_link_adopt(&other, S4_CLIENT_COMPANION) == -EADDRINUSE && other.refs == 1);
    assert(s4_link_acquire(S4_CLIENT_HEART, NULL) == NULL && watch.refs == 2);
    bt_addr_le_t wrong = {.type = 1};
    assert(s4_link_acquire(S4_CLIENT_COMPANION, &wrong) == NULL && watch.refs == 2);
    struct bt_conn *app = s4_link_acquire(S4_CLIENT_COMPANION, &watch.address);
    assert(app == &watch && watch.refs == 3);
    assert(s4_link_release(&watch, S4_CLIENT_HEART)); /* app still owns the radio link */
    bt_conn_unref(&watch); assert(watch.refs == 2);
    assert(!s4_link_release(&watch, S4_CLIENT_COMPANION));
    bt_conn_unref(app); assert(watch.refs == 0);
    assert(s4_link_acquire(S4_CLIENT_HEART, NULL) == NULL);
    watch.refs = 1;
    assert(s4_link_adopt(&watch, S4_CLIENT_COMPANION) == 0);
    struct bt_conn *heart = s4_link_acquire(S4_CLIENT_HEART, NULL); assert(heart);
    assert(s4_link_release(&watch, S4_CLIENT_COMPANION));
    bt_conn_unref(&watch); assert(watch.refs == 2); /* reverse order: heart survives */
    watch.state = BT_CONN_STATE_DISCONNECTED;
    assert(s4_link_acquire(S4_CLIENT_COMPANION, NULL) == NULL && watch.refs == 2);
    assert(!s4_link_release(heart, S4_CLIENT_HEART));
    bt_conn_unref(heart); assert(watch.refs == 0);
    assert(s4_link_adopt(NULL, S4_CLIENT_HEART) == -EINVAL);
    assert(s4_link_acquire((enum s4_client)99, NULL) == NULL);
    return 0;
}
