#include "activity.h"
static uint16_t le16(const uint8_t *p) { return p[0] | ((uint16_t)p[1] << 8); }
static void standby(struct activity *s, int64_t now)
{
    s->state = ACTIVITY_STANDBY;
    s->retry_after = now + ACTIVITY_RETRY_MS;
}
void activity_step(struct activity *s, int64_t now, bool motion,
                   bool fresh_hr, int64_t hr_received)
{
    if (motion) s->last_motion = now;
    if (s->state == ACTIVITY_STANDBY) {
        if (motion && now >= s->retry_after) {
            s->state = ACTIVITY_SEARCHING;
            s->deadline = now + ACTIVITY_SEARCH_MS;
        }
        return;
    }
    if (now - s->last_motion >= ACTIVITY_INACTIVE_MS) {
        standby(s, now);
    } else if (s->state == ACTIVITY_SEARCHING) {
        if (fresh_hr && now < s->deadline) {
            s->state = ACTIVITY_FORWARDING;
            s->deadline = hr_received + ACTIVITY_HR_LOST_MS;
        } else if (now >= s->deadline) standby(s, now);
    } else {
        if (fresh_hr) s->deadline = hr_received + ACTIVITY_HR_LOST_MS;
        if (now >= s->deadline) standby(s, now);
    }
}
bool cadence_motion(struct cadence_tracker *s, uint8_t type,
                    const uint8_t *p, size_t size, int64_t now)
{
    if (!p || size < 8 || (type != 121 && type != 122)) return false;
    if (type == 122 && (p[0] & 0x7f) > 5) return false;
    unsigned offset = type == 121 ? 0 : 4;
    uint16_t event = le16(p + offset), rev = le16(p + offset + 2);
    bool moving = false;
    if (s->initialized && now - s->received < 30000) {
        uint16_t dr = (uint16_t)(rev - s->revolutions);
        uint16_t dt = (uint16_t)(event - s->event_time);
        /* Count/event rollover is valid. Reject resets, duplicates and >250 rpm. */
        moving = dr > 0 && dt > 0 && (uint32_t)dr * 61440 <= (uint32_t)dt * 250;
    }
    s->initialized = true;
    s->revolutions = rev;
    s->event_time = event;
    s->received = now;
    return moving;
}
