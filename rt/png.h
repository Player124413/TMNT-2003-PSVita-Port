#ifndef TMNT_PNG_H
#define TMNT_PNG_H
#include <stdint.h>
void write_png(const char *path, const uint8_t *rgb_top_down, int w, int h);
#endif
