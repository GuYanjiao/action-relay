#ifndef ACTION_RELAY_CAMERA_DIAG_H
#define ACTION_RELAY_CAMERA_DIAG_H
#include <stdbool.h>
#include <stdint.h>
void camera_diag_init(void);
bool camera_diag_command(const char *, uint32_t now_ms);
void camera_diag_poll(uint32_t now_ms);
#endif
