#include "runtime_android.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static char data_dir[4096];
static char path_buf[4096];
void android_set_data_dir(const char *path) { snprintf(data_dir, sizeof data_dir, "%s", path); }
const char *android_game_path(const char *name)
{
    snprintf(path_buf, sizeof path_buf, "%s/%s", data_dir, name);
    return path_buf;
}
void android_setenv_default(const char *name, const char *value) { setenv(name, value, 0); }
void android_load_settings(void)
{
    if (!data_dir[0] || chdir(data_dir)) { fprintf(stderr, "Android data directory is missing\n"); exit(2); }
    FILE *f = fopen("tmnt_env.txt", "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = 0;
        char *eq = strchr(line, '=');
        if (line[0] == '#' || !eq) continue;
        *eq = 0;
        /* Only known port options, never arbitrary linker variables from an imported file. */
        static const char *keys[] = { "TMNT_FPS_LOCK", "TMNT_ASPECT", "TMNT_AUDIO", "TMNT_PERF", "TMNT_RENDER_THREAD",
            "TMNT_PAR", "TMNT_30FPS", "TMNT_VBO", "TMNT_RENDER_SCALE", "TMNT_VSYNC", "TMNT_CPU_TEXGEN" };
        for (unsigned i = 0; i < sizeof keys / sizeof keys[0]; i++)
            if (!strcmp(line, keys[i])) { setenv(line, eq + 1, 1); break; }
    }
    fclose(f);
}
