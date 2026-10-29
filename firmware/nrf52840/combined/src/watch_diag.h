#ifndef ACTION_RELAY_WATCH_DIAG_H
#define ACTION_RELAY_WATCH_DIAG_H
#include <stdbool.h>
void watch_diag_init(void);
/* Consumes a completed USB command line; never retains the supplied buffer. */
bool watch_diag_command(char *line);
#endif
