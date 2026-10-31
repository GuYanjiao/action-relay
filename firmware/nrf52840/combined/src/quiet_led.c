/* Take ownership of the board LED PWM, including any bootloader residue.
 * Dim 100ms pulse every 10s: 1kHz PWM at 2%, without busy waiting. */
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/pwm.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
#include <string.h>
#include "quiet_led.h"
LOG_MODULE_REGISTER(quiet_led, LOG_LEVEL_INF);
static const struct pwm_dt_spec led = PWM_DT_SPEC_GET(DT_ALIAS(pwm_led0));
static const struct gpio_dt_spec polarity = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static bool lit;
static atomic_t enabled = ATOMIC_INIT(1);
static atomic_t inverted;
static atomic_t pulses;
static void update(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(led_work, update);
static int brightness(bool on)
{
    /* GPIO alias, rather than the generic board's inverted PWM alias, defines
     * this LED's actual active level. No changes to bootloader or UICR. */
    bool active_low = !!(polarity.dt_flags & GPIO_ACTIVE_LOW) != !!atomic_get(&inverted);
    return pwm_set(led.dev, led.channel, PWM_USEC(1000), on ? PWM_USEC(20) : 0,
        active_low ? PWM_POLARITY_INVERTED : 0);
}
static void update(struct k_work *work)
{
    ARG_UNUSED(work);
    lit = atomic_get(&enabled) && !lit;
    if (brightness(lit)) { LOG_WRN("LED PWM update failed"); return; }
    if (lit) atomic_inc(&pulses);
    if (atomic_get(&enabled)) k_work_reschedule(&led_work, K_MSEC(lit ? 100 : 9900));
}
void quiet_led_init(void)
{
    if (!device_is_ready(led.dev) || brightness(false)) { LOG_WRN("LED PWM unavailable"); return; }
    lit = false;
    k_work_reschedule(&led_work, K_SECONDS(10));
    LOG_INF("LED dim mode: 2 percent PWM, 100ms every 10 seconds");
}
bool quiet_led_command(const char *line)
{
    if (!strcmp(line, "led off")) atomic_set(&enabled, 0);
    else if (!strcmp(line, "led dim")) atomic_set(&enabled, 1);
    else if (!strcmp(line, "led invert")) atomic_set(&inverted, !atomic_get(&inverted));
    else if (strcmp(line, "led status")) return false;
    if (strcmp(line, "led status")) k_work_reschedule(&led_work, K_NO_WAIT);
    LOG_INF("LED mode=%s invert=%ld pin=%u device=%s ready=%u pulses=%ld",
        atomic_get(&enabled) ? "dim" : "off", (long)atomic_get(&inverted),
        polarity.pin, led.dev->name, device_is_ready(led.dev), (long)atomic_get(&pulses));
    return true;
}
