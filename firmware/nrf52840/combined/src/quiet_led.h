#ifndef ACTION_RELAY_QUIET_LED_H
#define ACTION_RELAY_QUIET_LED_H
#include <stdbool.h>
void quiet_led_init(void);
bool quiet_led_command(const char *);
#endif
