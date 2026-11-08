/* Host test for the shared atomic input contract. */
#include <assert.h>
#include <stdint.h>
#include "rt/android/touch.h"
int main(void)
{
    uint32_t mask; int32_t x,y;
    android_touch_buttons(0xFFFFFFFFU); android_touch_stick(-1000,1000);
    android_touch_read(&mask,&x,&y);
    assert(mask == (1U << TMNT_ACT_COUNT)-1U); assert(x==-1000 && y==1000);
    android_set_paused(1); assert(android_is_paused()); android_touch_read(&mask,&x,&y); assert(!mask && !x && !y);
    android_set_paused(0); android_touch_buttons(1U<<TMNT_ACT_START); android_touch_read(&mask,&x,&y); assert(mask==(1U<<TMNT_ACT_START));
    android_request_quit(); assert(android_quit_requested());
    android_set_perf(1); android_frame_stats(60.f,16.7f);
    extern uint32_t android_stats_bits(int);
    uint32_t bits=android_stats_bits(0); float fps; __builtin_memcpy(&fps,&bits,4); assert(fps==60.f);
    return 0;
}
