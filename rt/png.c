/* Small PNG writer shared by desktop SDL and Android. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <zlib.h>
#include "png.h"

static void put32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16); p[2] = (uint8_t)(v >> 8); p[3] = (uint8_t)v; }

static void png_chunk(FILE *f, const char *type, const uint8_t *data, uint32_t n)
{
    uint8_t hdr[8];
    put32(hdr, n);
    memcpy(hdr + 4, type, 4);
    fwrite(hdr, 1, 8, f);
    if (n) fwrite(data, 1, n, f);
    uLong crc = crc32(0, (const Bytef *)type, 4);
    if (n) crc = crc32(crc, data, n);
    uint8_t c[4];
    put32(c, (uint32_t)crc);
    fwrite(c, 1, 4, f);
}

void write_png(const char *path, const uint8_t *rgb_top_down, int w, int h)
{
    uint8_t *raw = malloc((size_t)(w * 3 + 1) * h);
    for (int y = 0; y < h; y++) {
        raw[(size_t)y * (w * 3 + 1)] = 0;
        memcpy(raw + (size_t)y * (w * 3 + 1) + 1, rgb_top_down + (size_t)y * w * 3, (size_t)w * 3);
    }
    uLongf zn = compressBound((uLong)(w * 3 + 1) * h);
    uint8_t *z = malloc(zn);
    compress(z, &zn, raw, (uLong)(w * 3 + 1) * h);
    FILE *f = fopen(path, "wb");
    if (f) {
        static const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
        uint8_t ihdr[13];
        fwrite(sig, 1, 8, f);
        put32(ihdr, (uint32_t)w);
        put32(ihdr + 4, (uint32_t)h);
        ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
        png_chunk(f, "IHDR", ihdr, 13);
        png_chunk(f, "IDAT", z, (uint32_t)zn);
        png_chunk(f, "IEND", NULL, 0);
        fclose(f);
    }
    free(raw);
    free(z);
}

void plat_write_rgba_png(const char *path, const uint8_t *rgba, int w, int h)
{
    uint8_t *rgb = malloc((size_t)w * h * 3);
    for (int i = 0; i < w * h; i++) memcpy(rgb + i * 3, rgba + i * 4, 3);
    write_png(path, rgb, w, h);
    free(rgb);
}

