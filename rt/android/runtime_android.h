#ifndef TMNT_RUNTIME_ANDROID_H
#define TMNT_RUNTIME_ANDROID_H
void android_set_data_dir(const char *path);
const char *android_game_path(const char *name);
void android_load_settings(void);
void android_setenv_default(const char *name, const char *value);
#endif
