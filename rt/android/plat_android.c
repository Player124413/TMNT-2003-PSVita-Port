/* Android platform: SDL2 window/audio/controllers, gl4es over GLES2, Java touch overlay.
 * The renderer still sees the original D3D fixed-function pipeline; no per-frame JNI or texture transcoding. */
#include <SDL.h>
#include <EGL/egl.h>
#include <gl4esinit.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "plat.h"
#include "runtime.h"
#include "game_hooks.h"
#include "osdep.h"
#include "touch.h"
#include "runtime_android.h"
#include "../png.h"

int game_main(int argc, char **argv);
static SDL_Window *window;
static SDL_GLContext context;
static int game_w, game_h, screen_w, screen_h, render_w, render_h;
static GLuint offscreen, color_tex, depth_rb;
static int direct_framebuffer, context_ready;
static uint8_t dik[256];
static SDL_GameController *controller;
static uint32_t physical_buttons;
static int physical_pov = -1;
static int32_t physical_axes[4];
static float render_scale = .75f;
static void (*mixer)(int16_t *, int);
static SDL_AudioDeviceID audio_device;

void plat_fatal(const char *msg)
{
    rt_log("[fatal] %s\n", msg);
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "TMNT 2003", msg, window);
    exit(2);
}
static void setup_gl4es(void)
{
    android_setenv_default("LIBGL_ES", "2");
    android_setenv_default("LIBGL_FB", "0");
    android_setenv_default("LIBGL_BATCH", "1");
    android_setenv_default("LIBGL_AVOID16BITS", "1");
    android_setenv_default("LIBGL_MIPMAP", "3");
    android_setenv_default("LIBGL_NOERROR", "1");
    android_setenv_default("LIBGL_NOBANNER", "0");
    android_setenv_default("LIBGL_DEEPBIND", "0");
    /* Shader binaries are port-owned, never part of an imported game pack. */
    android_setenv_default("LIBGL_NOPSA", "0");
    android_setenv_default("LIBGL_PSA_FOLDER", SDL_AndroidGetInternalStoragePath());
    initialize_gl4es();
    atexit(close_gl4es);
}
static void resize_target(void);
static void destroy_target(void)
{
    if (offscreen) { glDeleteFramebuffers(1, &offscreen); offscreen=0; }
    if (color_tex) { glDeleteTextures(1, &color_tex); color_tex=0; }
    if (depth_rb) { glDeleteRenderbuffers(1, &depth_rb); depth_rb=0; }
}
int plat_frame_begin(void)
{
    int w=render_w,h=render_h; resize_target(); return w!=render_w || h!=render_h;
}
static void resize_target(void)
{
    SDL_GL_GetDrawableSize(window, &screen_w, &screen_h);
    if (screen_w <= 0 || screen_h <= 0) return;
    float scale = direct_framebuffer ? 1.f : render_scale;
    int w = ((int)(screen_w * scale) + 3) & ~3, h = ((int)(screen_h * scale) + 3) & ~3;
    w = w < 320 ? 320 : w; h = h < 200 ? 200 : h;
    if (offscreen && render_w == w && render_h == h) return;
    destroy_target(); render_w=w; render_h=h;
    if (!direct_framebuffer) {
        glGenTextures(1,&color_tex); glBindTexture(GL_TEXTURE_2D,color_tex);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
        glGenRenderbuffers(1,&depth_rb); glBindRenderbuffer(GL_RENDERBUFFER,depth_rb);
        glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH24_STENCIL8,w,h);
        glGenFramebuffers(1,&offscreen); glBindFramebuffer(GL_FRAMEBUFFER,offscreen);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color_tex,0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_RENDERBUFFER,depth_rb);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            rt_log("[android] packed depth/stencil unavailable; using native framebuffer\n");
            direct_framebuffer=1; resize_target(); return;
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER,offscreen);
    rt_log("[android] surface %dx%d, game target %dx%d\n",screen_w,screen_h,render_w,render_h);
}
int plat_video_init(int width, int height)
{
    if (window) return 1;
    game_w=width; game_h=height;
    const char *s=getenv("TMNT_RENDER_SCALE");
    if (s) { float v=strtof(s,NULL); if (v>=.25f && v<=1.f) render_scale=v; }
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,2);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,0);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE,24); SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE,8);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER,1);
    setup_gl4es();
    window=SDL_CreateWindow("TMNT 2003",SDL_WINDOWPOS_UNDEFINED,SDL_WINDOWPOS_UNDEFINED,width,height,
                            SDL_WINDOW_OPENGL|SDL_WINDOW_FULLSCREEN|SDL_WINDOW_ALLOW_HIGHDPI);
    if (!window) plat_fatal(SDL_GetError());
    context=SDL_GL_CreateContext(window);
    if (!context || SDL_GL_MakeCurrent(window,context)) plat_fatal("Cannot create an OpenGL ES 2 context.");
    context_ready=1;
    resize_target();
    SDL_GL_SetSwapInterval(getenv("TMNT_VSYNC") && !strcmp(getenv("TMNT_VSYNC"),"0") ? 0 : 1);
    rt_log("[android] GL4ES / %s\n",glGetString(GL_RENDERER));
    for (int i=0;i<SDL_NumJoysticks();i++) if (SDL_IsGameController(i)) { controller=SDL_GameControllerOpen(i); if(controller) break; }
    return 1;
}
void plat_screen_size(int *w,int *h) { *w=render_w ? render_w : game_w; *h=render_h ? render_h : game_h; }
void plat_game_size(int *w,int *h) { *w=game_w; *h=game_h; }
void plat_gl_thread(int take)
{
    if (context_ready && SDL_GL_MakeCurrent(window,take?context:NULL)) rt_log("[android] GL move: %s\n",SDL_GetError());
}
void plat_swap(void)
{
    if (offscreen) {
        /* One full-screen triangle pair: no glReadPixels, CPU readback or extra texture upload. */
        glBindFramebuffer(GL_FRAMEBUFFER,0);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
        glDisable(GL_ALPHA_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND); glDisable(GL_LIGHTING); glDisable(GL_FOG);
        glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
        glViewport(0,0,screen_w,screen_h); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION); glLoadIdentity(); glMatrixMode(GL_MODELVIEW); glLoadIdentity();
        glMatrixMode(GL_TEXTURE); glLoadIdentity(); glMatrixMode(GL_MODELVIEW);
        for (int u=1;u<4;u++) { glActiveTexture(GL_TEXTURE0+u); glDisable(GL_TEXTURE_2D); }
        glActiveTexture(GL_TEXTURE0); glEnable(GL_TEXTURE_2D); glBindTexture(GL_TEXTURE_2D,color_tex);
        glTexEnvi(GL_TEXTURE_ENV,GL_TEXTURE_ENV_MODE,GL_REPLACE); glColor4f(1,1,1,1);
        static const float pos[]={-1,-1,1,-1,-1,1,1,1}, uv[]={0,0,1,0,0,1,1,1};
        glBindBuffer(GL_ARRAY_BUFFER,0); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
        glClientActiveTexture(GL_TEXTURE0);
        glEnableClientState(GL_VERTEX_ARRAY); glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_COLOR_ARRAY); glDisableClientState(GL_NORMAL_ARRAY);
        glVertexPointer(2,GL_FLOAT,0,pos); glTexCoordPointer(2,GL_FLOAT,0,uv);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        glDisableClientState(GL_VERTEX_ARRAY);
        for (int u=0;u<4;u++) { glClientActiveTexture(GL_TEXTURE0+u); glDisableClientState(GL_TEXTURE_COORD_ARRAY); }
        glClientActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER,offscreen);
    }
    SDL_GL_SwapWindow(window);
}
static uint8_t key_of(SDL_Scancode k)
{
    if (k>=SDL_SCANCODE_A && k<=SDL_SCANCODE_Z) {
        static const uint8_t a[26]={0x1E,0x30,0x2E,0x20,0x12,0x21,0x22,0x23,0x17,0x24,0x25,0x26,0x32,0x31,0x18,0x19,0x10,0x13,0x1F,0x14,0x16,0x2F,0x11,0x2D,0x15,0x2C};
        return a[k-SDL_SCANCODE_A];
    }
    switch(k) { case SDL_SCANCODE_RETURN: return 0x1C; case SDL_SCANCODE_ESCAPE: return 0x01;
        case SDL_SCANCODE_SPACE:return 0x39; case SDL_SCANCODE_UP:return 0xC8; case SDL_SCANCODE_DOWN:return 0xD0;
        case SDL_SCANCODE_LEFT:return 0xCB; case SDL_SCANCODE_RIGHT:return 0xCD; default:return 0; }
}
int plat_pump(void)
{
    if (android_quit_requested()) return 0;
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
        switch(e.type) {
        case SDL_QUIT: return 0;
        case SDL_KEYDOWN: case SDL_KEYUP: { uint8_t k=key_of(e.key.keysym.scancode); if(k) dik[k]=e.type==SDL_KEYDOWN?0x80:0; break; }
        case SDL_CONTROLLERDEVICEADDED: if(!controller) controller=SDL_GameControllerOpen(e.cdevice.which); break;
        case SDL_CONTROLLERDEVICEREMOVED:
            if(controller && SDL_GameControllerGetAttached(controller)==SDL_FALSE) { SDL_GameControllerClose(controller); controller=NULL; }
            android_touch_clear(); break;
        case SDL_WINDOWEVENT:
            if(e.window.event==SDL_WINDOWEVENT_SIZE_CHANGED && context_ready) {
                /* Both the game and render threads may pump. Only the current context owner may allocate GL objects. */
                if(SDL_GL_GetCurrentContext()==context) resize_target();
            }
            break;
        case SDL_APP_WILLENTERBACKGROUND: case SDL_APP_WILLENTERFOREGROUND: android_touch_clear(); memset(dik,0,sizeof dik); break;
        }
    }
    physical_buttons=0; physical_pov=-1; memset(physical_axes,0,sizeof physical_axes);
    if(controller && SDL_GameControllerGetAttached(controller)) {
        static const SDL_GameControllerButton map[]={SDL_CONTROLLER_BUTTON_A,SDL_CONTROLLER_BUTTON_B,SDL_CONTROLLER_BUTTON_X,SDL_CONTROLLER_BUTTON_Y,
            SDL_CONTROLLER_BUTTON_LEFTSHOULDER,SDL_CONTROLLER_BUTTON_BACK,SDL_CONTROLLER_BUTTON_RIGHTSHOULDER};
        uint32_t b=0; for(int i=0;i<7;i++) if(SDL_GameControllerGetButton(controller,map[i])) b|=1U<<i;
        if((b&0x50)==0x50) physical_buttons=(b&~0x50U)|0x80U; else physical_buttons=b;
        if(SDL_GameControllerGetButton(controller,SDL_CONTROLLER_BUTTON_START)) physical_buttons|=1U<<TMNT_ACT_START;
        for(int i=0;i<4;i++) physical_axes[i]=SDL_GameControllerGetAxis(controller,(SDL_GameControllerAxis)i)*1000/32767;
        int up=SDL_GameControllerGetButton(controller,SDL_CONTROLLER_BUTTON_DPAD_UP),dn=SDL_GameControllerGetButton(controller,SDL_CONTROLLER_BUTTON_DPAD_DOWN);
        int lf=SDL_GameControllerGetButton(controller,SDL_CONTROLLER_BUTTON_DPAD_LEFT),rt=SDL_GameControllerGetButton(controller,SDL_CONTROLLER_BUTTON_DPAD_RIGHT);
        if(up||dn||lf||rt) { static const int dirs[3][3]={{31500,0,4500},{27000,-1,9000},{22500,18000,13500}}; physical_pov=dirs[dn?2:up?0:1][lf?0:rt?2:1]; }
    }
    /* Edit/pause is a cooperative boundary after a frame, never a suspended GL/context or audio callback. */
    while(android_is_paused()) {
        if (android_quit_requested()) return 0;
        android_touch_clear(); memset(dik,0,sizeof dik);
        SDL_Event pe; while (SDL_PollEvent(&pe)) if (pe.type==SDL_QUIT) return 0;
        SDL_Delay(16);
    }
    return 1;
}
const uint8_t *plat_dik_state(void)
{
    uint32_t b; int32_t x,y; android_touch_read(&b,&x,&y);
    static uint32_t previous_start;
    uint32_t start=b&(1U<<TMNT_ACT_START);
    if (start!=previous_start) { uint8_t key=tmnt_title_active()?0x1C:0x01; dik[key]=start?0x80:0; previous_start=start; }
    return dik;
}
void plat_pad(uint32_t *buttons,int32_t axes[4],int *pov)
{
    uint32_t touch; int32_t x,y; android_touch_read(&touch,&x,&y);
    *buttons=touch|physical_buttons;
    for(int i=0;i<4;i++) axes[i]=physical_axes[i];
    if(x||y) { axes[0]=x; axes[1]=y; }
    *pov=physical_pov;
}
uint64_t plat_ticks_us(void) { return SDL_GetPerformanceCounter()*1000000ULL/SDL_GetPerformanceFrequency(); }
void plat_thread_label(const char *name) { if(name) pthread_setname_np(pthread_self(),name); }
void plat_cpu_report(void) {}
void plat_power_info(int *arm,int *gpu,int *battery)
{
    *arm=*gpu=0; int seconds,percent;
    SDL_GetPowerInfo(&seconds,&percent); *battery=percent>=0&&percent<=100?percent:0;
}
static void SDLCALL audio_callback(void *opaque,Uint8 *stream,int length)
{
    (void)opaque;
    if(android_is_paused()) { memset(stream,0,(size_t)length); return; }
    if(mixer) mixer((int16_t*)stream,length/4); else memset(stream,0,(size_t)length);
}
int plat_audio_start(void (*mix)(int16_t*,int))
{
    mixer=mix;
    if(SDL_InitSubSystem(SDL_INIT_AUDIO)) { rt_log("[audio] %s\n",SDL_GetError()); return 48000; }
    SDL_AudioSpec want={0},have;
    want.freq=48000; want.format=AUDIO_S16SYS; want.channels=2; want.samples=512; want.callback=audio_callback;
    audio_device=SDL_OpenAudioDevice(NULL,0,&want,&have,0);
    if(!audio_device) { rt_log("[audio] %s\n",SDL_GetError()); return 48000; }
    SDL_PauseAudioDevice(audio_device,0);
    rt_log("[audio] %d Hz, %d frames/grain\n",have.freq,have.samples);
    return have.freq;
}
void plat_screenshot(const char *path)
{
    int w=render_w,h=render_h;
    if (!w || !h) return;
    uint8_t *rgba=malloc((size_t)w*h*4), *rgb=malloc((size_t)w*h*3);
    if (!rgba || !rgb) { free(rgba); free(rgb); return; }
    glBindFramebuffer(GL_FRAMEBUFFER,offscreen);
    glReadBuffer(offscreen ? GL_COLOR_ATTACHMENT0 : GL_BACK);
    glPixelStorei(GL_PACK_ALIGNMENT,1);
    glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        const uint8_t *src=rgba+((size_t)(h-1-y)*w+x)*4; uint8_t *dst=rgb+((size_t)y*w+x)*3;
        dst[0]=src[0]; dst[1]=src[1]; dst[2]=src[2];
    }
    write_png(path,rgb,w,h); free(rgba); free(rgb);
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved) { (void)vm; (void)reserved; return JNI_VERSION_1_6; }
static char *args[3];
static int game_result;
static void *game_thread(void *unused)
{
    (void)unused; plat_thread_label("tmnt-game");
    game_result=game_main(2,args); return NULL;
}
int SDL_main(int argc,char **argv)
{
    (void)argc; (void)argv;
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE,"1");
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE_PAUSEAUDIO,"1");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON,"1");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS,"0");
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS,"0");
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS,"0");
    if(SDL_Init(SDL_INIT_VIDEO|SDL_INIT_GAMECONTROLLER|SDL_INIT_EVENTS)) plat_fatal(SDL_GetError());
    const char *base=SDL_AndroidGetInternalStoragePath();
    if(!base) plat_fatal("Cannot locate the app-private data directory.");
    android_set_data_dir(base);
    args[0]="tmnt"; args[1]=(char*)android_game_path("image.bin"); args[2]=NULL;
    /* Translated calls nest on the native stack; the Java SDL thread's default stack is too small. */
    pthread_attr_t attr; pthread_attr_init(&attr); pthread_attr_setstacksize(&attr,8U<<20);
    pthread_t thread;
    if(pthread_create(&thread,&attr,game_thread,NULL)) plat_fatal("Cannot start the game thread.");
    pthread_attr_destroy(&attr); pthread_join(thread,NULL);
    return game_result;
}
