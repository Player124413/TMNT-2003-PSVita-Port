#include <stdatomic.h>
#include "touch.h"
static _Atomic uint32_t buttons;
static _Atomic int32_t stick_x, stick_y;
static _Atomic int paused, perf;
static _Atomic uint32_t fps_bits, ms_bits;
void android_touch_buttons(uint32_t mask) { atomic_store_explicit(&buttons, mask & ((1U << TMNT_ACT_COUNT) - 1U), memory_order_relaxed); }
void android_touch_stick(int32_t x, int32_t y) { atomic_store_explicit(&stick_x, x, memory_order_relaxed); atomic_store_explicit(&stick_y, y, memory_order_relaxed); }
void android_touch_read(uint32_t *mask, int32_t *x, int32_t *y)
{
    *mask = atomic_load_explicit(&buttons, memory_order_relaxed); *x = atomic_load_explicit(&stick_x, memory_order_relaxed); *y = atomic_load_explicit(&stick_y, memory_order_relaxed);
}
void android_touch_clear(void) { atomic_store_explicit(&buttons, 0, memory_order_relaxed); atomic_store_explicit(&stick_x, 0, memory_order_relaxed); atomic_store_explicit(&stick_y, 0, memory_order_relaxed); }
void android_set_paused(int value) { atomic_store_explicit(&paused, value != 0, memory_order_relaxed); if (value) android_touch_clear(); }
int android_is_paused(void) { return atomic_load_explicit(&paused, memory_order_relaxed); }
void android_set_perf(int enabled) { atomic_store_explicit(&perf, enabled != 0, memory_order_relaxed); }
static uint32_t fbits(float f) { uint32_t u; __builtin_memcpy(&u, &f, 4); return u; }
void android_frame_stats(float fps, float ms)
{
    if (!atomic_load_explicit(&perf, memory_order_relaxed)) return;
    atomic_store_explicit(&fps_bits, fbits(fps), memory_order_relaxed); atomic_store_explicit(&ms_bits, fbits(ms), memory_order_relaxed);
}
uint32_t android_stats_bits(int which) { return atomic_load_explicit(which ? &ms_bits : &fps_bits, memory_order_relaxed); }

static _Atomic int quit_requested;
void android_request_quit(void) { atomic_store_explicit(&quit_requested, 1, memory_order_relaxed); }
int android_quit_requested(void) { return atomic_load_explicit(&quit_requested, memory_order_relaxed); }
