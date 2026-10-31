/* Take ownership of the board LED PWM, including any bootloader residue.
 * Dim 100ms pulse every 10s: 1kHz PWM at 2%, without busy waiting. */
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/pwm.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
#include "quiet_led.h"
LOG_MODULE_REGISTER(quiet_led, LOG_LEVEL_INF);
static const struct pwm_dt_spec led = PWM_DT_SPEC_GET(DT_ALIAS(pwm_led0));
static const struct gpio_dt_spec polarity = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static bool lit;
static void update(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(led_work, update);
static int brightness(bool on)
{
    /* GPIO alias, rather than the generic board's inverted PWM alias, defines
     * this LED's actual active level. No changes to bootloader or UICR. */
    return pwm_set(led.dev, led.channel, PWM_USEC(1000), on ? PWM_USEC(20) : 0,
        polarity.dt_flags & GPIO_ACTIVE_LOW ? PWM_POLARITY_INVERTED : 0);
}
static void update(struct k_work *work)
{
    ARG_UNUSED(work);
    lit = !lit;
    if (brightness(lit)) { LOG_WRN("LED PWM update failed"); return; }
    k_work_reschedule(&led_work, K_MSEC(lit ? 100 : 9900));
}
void quiet_led_init(void)
{
    if (!device_is_ready(led.dev) || brightness(false)) { LOG_WRN("LED PWM unavailable"); return; }
    lit = false;
    k_work_reschedule(&led_work, K_SECONDS(10));
    LOG_INF("LED dim mode: 2 percent PWM, 100ms every 10 seconds");
}
