# TMNT 2003 → PS Vita: jurnal

## 2026-10-19 – primul run pe Mac

- **Traducătorul** (fork local al lui repentogxm în `recomp/xlat/`): reguli noi pentru `imul r/m8` și `r/m16`, `bt/bts/btr/btc` pe memorie, `jecxz`, x87 transcendental (`fsin`, `fcos`, `fsincos`, `fptan`, `fpatan`, `fyl2x`, `fyl2xp1`, `f2xm1`, `fscale`, `fprem`, `fprem1`, `fxam`), `fld` pe 80 de biți, `ffree`, `rdtsc`, `stmxcsr/ldmxcsr`, `popfd` (cu bitul ID, pentru testul de prezență CPUID). Controlul x87 e modelat: `fldcw/fnstcw` țin `c->fcw`, iar `fist/fistp/frndint` rotunjesc după câmpul RC (`_ftol` din MSVC 7.1 comută pe trunchiere în jurul lui `fistp`).
- **Tabelele de switch**: `discover.py` salvează și muchiile (`edges`: jmp → ținte), pe care traducătorul le emite ca `switch` local; fără ele, un `jmp [tabel + reg*4]` devenea `guest_call` spre o etichetă din interiorul funcției.
- 9 562 de funcții curate din 9 734 (98,2%); restul de 172 sunt variante MMX/3DNow!/SSE ale RenderWare și CRT, plus câteva funcții false.
- **Runtime** (`rt/`):
  - `guest.h`: fork al celui din repentogxm; toate accesele la memorie trec prin `G2H(a) = guest_membase + a` (exe-ul n-are relocări, rămâne la 0x400000);
  - `runtime.c`: spațiul guest de 512 MB (primele 64 KB fără acces), imaginea la 0x400000, TEB la 0x10000, stiva principală de 6 MB sub 0x1000000, heap-ul guest (boundary tags, 32 de bin-uri) de la 0x1000000; dispatch prin căutare binară RVA → funcție; importurile și metodele gazdă sunt adrese „thunk” de la 0xF0000000 (16 octeți distanță); `cpuid` raportează un Pentium II fără MMX/SSE/3DNow!;
  - `win32.c`: cele 147 de importuri (kernel32: fișiere, heap, secțiuni critice cu mutex-uri recursive, evenimente, mutex-uri, thread-uri pe pthreads cu TEB și stivă proprie, TLS, locale 1252/0x409, timp; user32/gdi32 doar stub-uri; registry gol).
- **Build pe Mac**: `make -j8` (cu `DEVELOPER_DIR=/Library/Developer/CommandLineTools`, linkerul din Xcode nu citește SDK-ul 27), `tools/mac_run.sh [--gen] [secunde]` regenerează, compilează și rulează din `game/app`, log în `run.log`.
- **Rezultat**: CRT-ul se inițializează complet (heap, locale, secțiuni critice, TLS), WinMain își înregistrează clasa de fereastră (`TMNT_WIN32`, wndproc 0x4afc5d), creează fereastra și ajunge la `Direct3DCreate9(31)`, unde runtime-ul se oprește intenționat.
- Următorul pas: Direct3D 9 (obiecte COM cu vtable-uri de thunk-uri), apoi DirectInput 8 și DirectSound; pe Mac prin SDL2 + OpenGL, pe Vita prin vitaGL.

## 2026-10-19 – Direct3D 9, DirectInput, DirectSound: meniul principal pe Mac

- **COM** (`rt/com.c`): obiecte guest `{vtbl, index host}`; vtable-urile se construiesc din tabelele generate din headerele mingw-w64 (`tools/gen_com_tables.py` → `rt/com_tables.h`, 24 de interfețe, 443 de metode, cu numărul exact de argumente stdcall); metodele neimplementate se loghează o dată și întorc 0. Constantele DirectX: `tools/gen_d3d_consts.py` → `rt/dx_consts.h` (1 814).
- **Direct3D 9 → OpenGL 2.1** (`rt/d3d9.c`, `rt/d3d9_gl.c`): device fixed-function (fără vertex/pixel shadere; RenderWare rămâne pe pipeline-urile fixe), texturi cu nivelurile în memoria guest (încărcate în GL la desenare, DXT direct), VB/IB în memoria guest, FVF și vertex declarations, vertecși RHW (ortho cu corecția de jumătate de pixel), lumini, ceață, texture stages → `GL_COMBINE`, texgen pentru env-map, stări de blend/alpha/z/stencil/cull. `TMNT_DUMP_FRAME=N` loghează desenările unui cadru și salvează texturile; `TMNT_SHOT_FRAME`/`TMNT_SHOT_EVERY` fac capturi PNG. Vsync oprit implicit (`TMNT_VSYNC=1`): pe macOS `SwapWindow` se blochează cât fereastra nu e vizibilă.
- **Platforma Mac** (`rt/plat_sdl.c`): SDL2 + GL, tastatura în coduri DIK, gamepad ca joystick DirectInput, ieșire audio S16 stereo.
- **DirectInput 8** (`rt/dinput.c`): tastatură, mouse, joystick (axe pe intervalul setat prin `DIPROP_RANGE`).
- **DirectSound** (`rt/dsound.c`): buffere în memoria guest, cursoare de redare care avansează după ceasul ieșirii, mixer cu volum și pan.
- **Win32**: coada de mesaje (`WM_ACTIVATEAPP`/`WM_ACTIVATE`/`WM_SETFOCUS` la `ShowWindow`, `DispatchMessageA` cheamă WndProc-ul guest) — fără ele scheletul RenderWare nu rula bucla jocului; `timeSetEvent` pe thread-uri gazdă; `SuspendThread`/`ResumeThread` cu suspend count (auto-suspendare blocantă, suspendarea altui thread se aplică la următorul apel de API); `PulseEvent` cu contor de generație.
- **CRI ADX**: middleware-ul de sunet are 3 thread-uri (vsync din timer, fișiere, idle). Probele de CPU `0x5630a0` (SSE) și `0x563110` (MMX) sunt înlocuite (`OVERRIDES` în `gen_c.py`) să întoarcă 0, ca decodorul să fie cel în C.
- **Bug-uri în traducător (moștenite din repentogxm)**:
  - `adc`/`sbb` derivau CF/OF fără carry-ul de intrare: `sbb eax,eax; sbb eax,-1` din `strncmp`/`strcmp` dădea 0 în loc de -1, iar jocul nu își găsea fișierele (`fonts/font.met`). Acum flag-urile se scriu explicit (`FLAG_PARTIAL`). `TMNT_SELFTEST=1` verifică `strchr`/`strcmp`/`strncmp` față de libc.
  - x87 pe registre: Capstone scrie `D8 E0+i` (`st0 = st0 op st(i)`) ca `fsub st(i)`, la fel ca forma cu pop `DE` (`st(i) = st(i) op st0`); emitter-ul scria în `st(i)`. Coordonatele 2D ieșeau greșite.
  - `pushf`/`popf` pe 16 biți (testul CPUID al RenderWare, care eșuează intenționat și duce la căile generice), `fnsave/frstor/fnstenv/fldenv`, `emms`, `movhlps/movlhps`, `rcpps/rcpss`, `rsqrtps/rsqrtss`, `andnps`, `cmp*ps`.
- `discover.py`: tabelele de salt ale `memcpy` din CRT au slotul 0 nefolosit; cititorul sare acum peste el. 9 619 funcții curate din 9 734 (98,8%); restul sunt doar MMX/3DNow!.
- `GUEST_ENTER(rva)` la intrarea fiecărei funcții generate + `TMNT_TRACE_FUNCS=rva,...` (argumente, registre, șiruri). Crash handler pe Mac cu stiva nativă (`sub_RVA`).
- **Rezultat**: intro-ul (logo Konami, imagini) și **meniul principal** (Story/VS/Challenge/Database/Options/Save/Load/Quit) se randează la ~60 FPS pe Mac, cu DirectSound activ. De verificat: culorile logo-ului Konami (magenta), comparativ cu jocul prin Wine.
- **Culorile** (2026-10-19): texturile ieșeau decolorate (țestoase cyan, logo Konami magenta) pentru că RenderWare aplică la încărcare un tabel gamma construit cu `pow()`, iar `pow` tradus întorcea 2^round(−t). Cauza: Capstone 5 dă `fxch st(i)` ca pereche de operanzi (st(0), st(i)), iar emitter-ul folosea primul operand, deci toate cele 363 de `fxch` erau no-op. Reparat; `TMNT_SELFTEST_POW=1` verifică `pow` (CRT `0x566180`, cdecl) după inițializarea CRT. Referința culorilor: imaginile 8-bit cu paletă din dicționarele PI TXD (chunk `0x23`) decodate direct în PNG. Userul a confirmat că acum culorile sunt corecte.
- **Salvări**: jocul scrie `SaveGame.dat` și `SaveSetting.dat` în folderul lui (`C:\TMNT\` → `game/app/`). Build-urile nu ating `game/app`, deci salvările făcute la teste rămân; pe Vita același fișier se copiază în folderul de date al portului (formatul e al jocului, nu depinde de platformă).
- **Scena 3D dispărea la prima acțiune** (2026-10-19): matricile `world` primeau NaN. Rutinele de matematică din CRT-ul MSVC 7.1 (`sin` 0x566074, `cos` 0x565e94, `sqrt` 0x566854, `acos`/`asin` 0x567204/0x567134) cheamă un helper care întoarce rezultatul și în flag-uri (`call 0x56b405; je special`); traducătorul trata `ret` ca punct unde flag-urile mor și elimina comparația din helper. Acum un bloc care se termină cu `ret` ține toate flag-urile vii (`trans.flag_liveness`). `TMNT_SELFTEST_POW=1` testează și funcțiile astea.
- Unelte: fișierul `game/app/dump.req` cere jocului care rulează o captură + dump-ul desenărilor din cadrul următor + matricile camerei; `SetTransform` raportează matricile NaN/infinite cu adresa apelantului.
- **Scurgere de memorie** (2026-10-19): texturile nu erau eliberate niciodată (eliberarea era sub `TMNT_FREE_TEXTURES`); după ~45 de minute de demo-uri, heap-ul guest ajungea la 492 MB și o încărcare rămânea blocată. Acum `Release` la 0 eliberează nivelele, suprafețele și numele GL (șterse pe thread-ul de randare la următorul `Present`, pentru că jocul eliberează și de pe thread-ul de încărcare). Heap stabil 31–56 MB pe 22 000 de cadre de demo.
- **Checkpoint-uri pe zone** (`rt/game_tmnt.c`, hook pe `LoadArea` 0x4ae293 prin `HOOKS` în `gen_c.py`): tabelul de zone la 0x5e0560 (56 B: stagiu, zonă, nume, index `.pac`), starea jocului la `[0x60a438]` (+0x13c = zona următoare). Doar apelurile din calea de gameplay (`0x4b5320`) contează; demo-urile de prezentare încarcă zone din `0x411db0`. Fișier separat `TMNTCheckpoint.dat`; `TMNT_NO_CHECKPOINTS=1` le oprește.
- Checkpoint-urile funcționează (confirmat de user): `StartArea` (0x411d93, al doilea argument = zona) e folosit și de joc, și de demo-uri; le deosebește apelantul ultimului `SetNextArea` (0x4047d7): meniul de stagii (0x48c92d, 0x4a1f96) sau zonă terminată (0x49475f). Rămâne de verificat ștergerea checkpoint-ului la terminarea unui stagiu întreg.

## 2026-10-19/20 – Faza Vita: jocul rulează pe consolă

- **Platforma Vita**:
  - `rt/plat_vita.c`: vitaGL la 960x544; SceCtrl e un joystick DirectInput (✕ ◯ □ △ L R Select Start, cruce = POV, ambele stick-uri); SceAudioOut la 48 kHz; PNG fără zlib; L+R+Select creează `dump.req`.
  - `rt/osdep.h`: mutex și condiție de kernel (condițiile pthread pierd treziri pe Vita), timp, sleep, plasarea thread-urilor.
  - Build: `vita/CMakeLists.txt` → `build/vita/TMNTVita.vpk`, title ID `TMNT02003`. Build-ul complet durează ~10 min; link-ul are nevoie de `stdc++` pentru vitaGL.
  - Pe card: `ux0:data/TMNT2003/` conține datele, `image.bin`, salvările, `log.txt` și opțional `tmnt_env.txt`, cu linii `NAME=value` în loc de variabile de mediu.
- **Ecranul**: Win32 raportează rezoluția jocului (`plat_game_size`, 800x600); doar maparea viewport/scissor/clear folosește ecranul real. Altfel jocul ieșea la pornire. Implicit imaginea e întinsă, iar `TMNT_ASPECT=4:3` pune benzi negre. Pe Mac, `TMNT_WINDOW=960x544` previzualizează ecranul Vitei.
- **Blocajele de la pornire de pe Vita**, rezolvate în ordine:
  1. `SuspendThread` se aplica la următorul apel de API, iar un worker ADX era oprit cu o secțiune critică luată. Acum un thread se oprește doar în afara secțiunilor critice.
  2. Pe Vita, thread-urile cu aceeași prioritate nu își împart timpul pe un nucleu, deci un thread care se învârte îl blochează pe celălalt. Acum thread-ul principal are nucleul 0 singur, restul folosesc nucleele 1–2; timerele au prioritate time-critical, iar `SetThreadPriority` se aplică.
  3. `timeSetEvent` crea un thread nou la fiecare armare. Acum fiecare slot are un thread permanent.
  4. `__thread` e emulat pe Vita, iar thread-urile se blocau în emulare. Runtime-ul nu mai are TLS: contextul thread-ului se găsește după `CPU*`.
- **Ecran alb**: `glClientActiveTexture` pe unitățile 2–3 (inexistente în vitaGL) dezactiva coordonatele unității 0. Acum se ating doar unitățile folosite.
- **Texgen pe procesor**: vitaGL n-are `glTexGen`. Cel-shading-ul țestoaselor (rampa 16x1, `TCI_CAMERASPACENORMAL` + matricea de textură) se calculează în `cpu_texgen_coords`, cu normala prin inversa transpusă a world*view. Fără inversa transpusă apăreau contururi albe. Pe Mac, `TMNT_CPU_TEXGEN=1` compară cu OpenGL nativ.
- **Demo-ul de pe PRESS START** e scos: `CODE_PATCHES` în `recomp/tmnt_image.py`, pragul 0x708 → 0xFFFFFFFF la 0x49865a.
- **Viteza în misiune** (Vita): 245 ms/cadru → 114–133 ms (~8 FPS), prin:
  - desenele convertesc doar vârfurile desenate (60 → 13 ms);
  - `GUEST_GPR_LOCAL=1` pentru codul generat (pe Vita și pe Mac), cu joc ~185 → ~100 ms;
  - `rdtsc` pe `sceKernelGetProcessTimeLow` (jocul îl citește de 6–15k ori pe cadru);
  - memorie a stării GL în `d3d9_gl.c` (netestată încă pe Vita).
- **Profilare**: `TMNT_PROF=1` loghează timpul API pe metodă și funcțiile guest eșantionate; fără el se loghează doar împărțirea cadrului. `TMNT_WATCHDOG=1` loghează starea thread-urilor la 2 s.
- **Test automat pe Mac fără fereastră**: `TMNT_HIDDEN=1`. Tastele implicite ale jocului: F confirmă, D anulează; la alegerea țestoasei se apasă săgeata, apoi F. Scenariul cu `TMNT_KEYS` ajunge în Town2 în ~110 s.
- **Rămase**:
  - filmele de intro nu se văd nici pe Mac (`StretchRect` lipsește);
  - mipmap-urile nu se folosesc pe Vita;
  - stiva x87 virtualizată în variabile locale (estimare: ~30% din codul jocului e x87);
  - un thread separat de randare;
  - sacadare la prima întâlnire a unei stări noi, probabil compilarea shaderelor ffp din vitaGL.
