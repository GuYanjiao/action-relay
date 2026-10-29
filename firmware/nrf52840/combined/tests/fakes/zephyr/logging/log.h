#ifndef RADIO_TEST_LOG_H
#define RADIO_TEST_LOG_H
#define LOG_MODULE_REGISTER(...)
#define LOG_LEVEL_INF 0
static inline void fake_log(const char *format, ...) { (void)format; }
#define LOG_WRN(...) fake_log(__VA_ARGS__)
#define LOG_INF(...) fake_log(__VA_ARGS__)
#endif
