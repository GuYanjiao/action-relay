#ifndef ACTION_RELAY_MIWEAR_RUNTIME_H
#define ACTION_RELAY_MIWEAR_RUNTIME_H
void miwear_runtime_init(void);
/* Copies a 32-hex-character key into a one-use RAM slot. Never logs/persists it. */
int miwear_runtime_auth(const char *hex_key);
#endif
