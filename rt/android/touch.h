/* Java UI -> native input. Only atomics: no JNI or allocation on the render/game hot path. */
#ifndef TMNT_ANDROID_TOUCH_H
#define TMNT_ANDROID_TOUCH_H
#include <stdint.h>
enum { TMNT_ACT_WEAK, TMNT_ACT_STRONG, TMNT_ACT_JUMP, TMNT_ACT_SHURIKEN, TMNT_ACT_DASH,
       TMNT_ACT_SWITCH, TMNT_ACT_JUGGLE, TMNT_ACT_SPECIAL, TMNT_ACT_START, TMNT_ACT_COUNT };
void android_touch_buttons(uint32_t mask);
void android_touch_stick(int32_t x, int32_t y);
void android_touch_read(uint32_t *mask, int32_t *x, int32_t *y);
void android_touch_clear(void);
void android_set_paused(int paused);
int android_is_paused(void);
void android_request_quit(void);
int android_quit_requested(void);
void android_set_perf(int enabled);
void android_frame_stats(float fps, float ms);
#endif
