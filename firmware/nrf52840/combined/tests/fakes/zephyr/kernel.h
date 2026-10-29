#ifndef RADIO_TEST_KERNEL_H
#define RADIO_TEST_KERNEL_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define ARG_UNUSED(x) (void)(x)
#define BIT(x) (1U << (x))
#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define K_NO_WAIT 0
#define K_MSEC(x) (x)
typedef int atomic_t;
typedef int atomic_val_t;
#define ATOMIC_INIT(x) (x)
static inline int atomic_get(const atomic_t *p) { return *p; }
static inline void atomic_set(atomic_t *p, int value) { *p = value; }
static inline void atomic_inc(atomic_t *p) { ++*p; }
static inline void atomic_set_bit(atomic_t *p, int bit) { *p |= (int)BIT(bit); }
static inline void atomic_clear_bit(atomic_t *p, int bit) { *p &= ~(int)BIT(bit); }
static inline bool atomic_cas(atomic_t *p, int old, int value)
{ if (*p != old) return false; *p = value; return true; }
struct k_spinlock { int unused; };
typedef int k_spinlock_key_t;
static inline int k_spin_lock(struct k_spinlock *p) { (void)p; return 0; }
static inline void k_spin_unlock(struct k_spinlock *p, int key) { (void)p; (void)key; }
struct k_work { void (*handler)(struct k_work *); };
struct k_work_delayable { struct k_work work; };
#define K_WORK_DELAYABLE_DEFINE(name, callback) struct k_work_delayable name = {{callback}}
int k_work_reschedule(struct k_work_delayable *, int);
#endif
