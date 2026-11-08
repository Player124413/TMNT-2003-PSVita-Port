/* Android's linker owns ELF loading, relocation, TLS and executable pages. No writable+executable memory. */
#ifdef TMNT_CODE_SO
#include <dlfcn.h>
#include <stdio.h>
#include "../code_so.h"
#include "../plat.h"
#include "../runtime.h"
const tmnt_code_exports *tmnt_code_mod;
#define CODE_TRAMPOLINE(name) void name(CPU *__restrict c) { tmnt_code_mod->name(c); }
TMNT_CODE_DIRECT(CODE_TRAMPOLINE)
#undef CODE_TRAMPOLINE
const tmnt_code_exports *code_so_load(const char *path)
{
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) plat_fatal(dlerror());
    const tmnt_code_exports *x = (const tmnt_code_exports *)dlsym(handle, "tmnt_code");
    if (!x || x->magic != TMNT_CODE_MAGIC || x->version != TMNT_CODE_VERSION)
        plat_fatal("This game module does not match this Android port. Rebuild the game pack with tools/make_android_files.py.");
    tmnt_code_mod = x;
    rt_log("[code] Android linker loaded %u translated functions\n", *x->func_count);
    return x;
}
#endif
