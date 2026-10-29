#ifndef ACTIVITY_H
#define ACTIVITY_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#define ACTIVITY_SEARCH_MS 45000
#define ACTIVITY_RETRY_MS 60000
#define ACTIVITY_INACTIVE_MS 180000
#define ACTIVITY_HR_LOST_MS 30000
#define CADENCE_LISTEN_MS 5000
#define CADENCE_POLL_MS 20000

enum activity_state { ACTIVITY_STANDBY, ACTIVITY_SEARCHING, ACTIVITY_FORWARDING };
struct activity {
    enum activity_state state;
    int64_t deadline, retry_after, last_motion;
};
/* Main-thread only; motion is a validated crank revolution, not an RF packet. */
void activity_step(struct activity *state, int64_t now, bool motion,
                   bool fresh_hr, int64_t hr_received);
struct cadence_tracker {
    bool initialized;
    uint16_t revolutions, event_time;
    int64_t received;
};
/* type 122: cadence only; type 121: speed/cadence combined. */
bool cadence_motion(struct cadence_tracker *state, uint8_t type,
                    const uint8_t *payload, size_t size, int64_t now);
#endif
