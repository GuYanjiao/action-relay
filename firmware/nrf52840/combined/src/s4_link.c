/* Reference ownership for one S4 GATT link; not a connection/reconnect policy. */
#include <errno.h>
#include <zephyr/kernel.h>
#include "s4_link.h"
static struct k_spinlock lock;
static struct bt_conn *shared;
static unsigned owners;
static bool valid(enum s4_client client)
{ return client == S4_CLIENT_HEART || client == S4_CLIENT_COMPANION; }
int s4_link_adopt(struct bt_conn *connection, enum s4_client client)
{
    if (!connection || !valid(client)) return -EINVAL;
    k_spinlock_key_t key = k_spin_lock(&lock);
    int error = 0;
    if (shared && shared != connection) error = -EADDRINUSE;
    else if (owners & BIT(client)) error = -EALREADY;
    else {
        if (!shared) shared = bt_conn_ref(connection);
        owners |= BIT(client);
    }
    k_spin_unlock(&lock, key);
    return error;
}
struct bt_conn *s4_link_acquire(enum s4_client client, const bt_addr_le_t *address)
{
    if (!valid(client)) return NULL;
    k_spinlock_key_t key = k_spin_lock(&lock);
    struct bt_conn *candidate = shared && !(owners & BIT(client)) ? bt_conn_ref(shared) : NULL;
    k_spin_unlock(&lock, key);
    if (!candidate) return NULL;
    struct bt_conn_info info;
    if (bt_conn_get_info(candidate, &info) || info.state != BT_CONN_STATE_CONNECTED ||
        (address && bt_addr_le_cmp(address, bt_conn_get_dst(candidate)))) {
        bt_conn_unref(candidate); return NULL;
    }
    key = k_spin_lock(&lock);
    bool acquired = shared == candidate && !(owners & BIT(client));
    if (acquired) owners |= BIT(client);
    k_spin_unlock(&lock, key);
    if (!acquired) { bt_conn_unref(candidate); return NULL; }
    return candidate;
}
bool s4_link_release(struct bt_conn *connection, enum s4_client client)
{
    if (!connection || !valid(client)) return false;
    struct bt_conn *drop = NULL;
    k_spinlock_key_t key = k_spin_lock(&lock);
    bool remaining = false;
    if (shared == connection) {
        owners &= ~BIT(client);
        remaining = owners != 0;
        if (!remaining) { drop = shared; shared = NULL; }
    }
    k_spin_unlock(&lock, key);
    if (drop) bt_conn_unref(drop);
    return remaining;
}
