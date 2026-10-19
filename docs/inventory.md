# TMNT 2003 (PC) → PS Vita – Faza 0: inventar

Data: 2026-10-19. Sursa: `~/Downloads/Teenage_Mutant_Ninja_Turtles_Win_ISO_EN.RU` (2 imagini CD, ediție rusească EN/RU, installer Inno Setup 4.0.5). Despachetat cu `innoextract` în `game/app/`.

## Fișiere

| Fișier | Mărime | Ce e |
|---|---|---|
| `TMNT_EN.EXE` | 2,05 MB | exe-ul în engleză (11.09.2003) – **ținta portului** |
| `TMNT_RU.EXE` | 2,05 MB | același build (același timestamp), diferă 13 598 de octeți (texte) |
| `TMNT_EN.DAT` / `TMNT_RU.DAT` | 622 MB | arhiva principală de date, pe limbă |
| `TMNT_V00–V02.DAT` | 220–250 MB | date (probabil video/nivele) |
| `bgm.afs`, `se.afs`, `voice.afs` | 237 / 78 / 15 MB | muzică, efecte, voci – arhive CRI AFS cu ADX |

Installerul are fiecare exe/DAT de două ori (componenta engleză e marcată `#engfiles`); extragerea cere `innoextract --collisions=rename`.

## Exe-ul

- PE Win32, bază 0x400000, entry în `.text` (0x567ECE). **Fără SecuROM sau alt împachetator**: fără secțiuni `.cms_*`, codul din toate secțiunile executabile începe cu prologuri normale.
- Secțiuni de cod: `.text` (1,52 MB), `_rwcseg` (RenderWare), `PSFD00/_I/_P/_B` (cod obișnuit, rol încă necunoscut).
- ~565 000 de instrucțiuni, ~6 450 de ținte de `call` directe (≈ funcții), 3 154 de apeluri indirecte (vtable-uri COM/RenderWare), 379 de salturi indirecte (tabele de switch).
- Virgulă mobilă: preponderent **x87** (~55 000 de instrucțiuni), plus ~3 500 SSE și MMX (căi alese prin `cpuid` – RenderWare are variante pe CPU).

## Middleware

- **RenderWare 3.5, ramura D3D9** (`//RenderWare/dev_branches/RW35ActiveD3D9/rwsdk/...`): 120 de șiruri cu numele fișierelor sursă RW → funcțiile RW se pot identifica și grupa ușor.
- **CRI ADX/AFS** pentru sunet (`*.adx` în `*.afs`), redat prin DirectSound.

## Ce folosește din Windows

| DLL | Funcții |
|---|---|
| `d3d9.dll` | doar `Direct3DCreate9` – restul prin interfețele COM |
| `DSOUND.dll` | `DirectSoundCreate` (ordinal 1) |
| `dinput8.dll` | încărcat dinamic: `DirectInput8Create` |
| `WINMM.dll` | 6 funcții de timp (`timeSetEvent`, `timeGetTime`…) |
| `KERNEL32.dll` | 102 funcții: fișiere, thread-uri, secțiuni critice, evenimente, `QueryPerformanceCounter` |
| `USER32.dll` | 31 de funcții: fereastra și bucla de mesaje |
| `GDI32`, `ADVAPI32`, `COMCTL32` | 2 / 3 (registry) / 1 |

## Concluzii

- Exe-ul se poate recompila direct: nu e protejat și nici împachetat.
- Suprafața Windows e mică (~150 de funcții importate), iar grafica trece toată prin RenderWare → D3D9, deci stratul de înlocuit e D3D9 (→ vitaGL), DirectSound, DirectInput și câteva zeci de funcții din kernel32/user32.
- Riscurile principale: traducerea x87 (stiva FPU) și apelurile indirecte prin vtable-uri.

## Referința pe Mac

Jocul rulează prin Wine 11.18 (`tools/wine`, prefix `wineprefix`, D3D9 → Vulkan/MoltenVK pe M4): meniuri și gameplay OK (2026-10-19). `game/app/TMNT.DAT` e symlink spre `TMNT_EN.DAT` (exe-ul caută `TMNT.DAT`). Pornire: `WINEPREFIX=… wine TMNT_EN.EXE` din `game/app`.

## Pasul 1: primul test de traducere x86 → C (2026-10-19)

Traducătorul e cel din repentogxm (`ref/repentogxm`, GPL-2+, clonat local): `trans.py` (recursive descent, flag-uri leneșe) și `emit.py` (o instrucțiune → C). Isaac are `.reloc` și e mutat la altă bază; exe-ul TMNT n-are relocări (`RELOCS_STRIPPED`), deci rămâne la 0x400000. Toate accesele la memorie trec prin `ld8/16/32` și `st8/16/32` din `guest.h`, așa că pe Vita adresa gazdă = adresa guest + un offset fix se rezolvă în runtime, fără schimbări în traducător.

Scripturi în `recomp/`:
- `tmnt_image.py`: imaginea la 0x400000, toate secțiunile executabile ca cod, tabelele de noreturn ale lui Isaac golite;
- `discover.py` → `out/functions.json`: rădăcini = entry + pointeri spre cod din `.rdata`/`.data` + imediați spre cod, apoi ținte de `call`, cu rezolvarea tabelelor de switch (`jmp [reg*4 + tabel]`);
- `sweep.py` → `out/sweep.json`: traduce fiecare funcție și numără ce nu se traduce;
- `gen_c.py` → `out/c/guest_NNN.c` (49 de fișiere, 200 de funcții fiecare).

Rezultate:
- **9 734 de funcții** găsite (6 193 prin `call`, 1 316 din pointeri în date, 2 224 din imediați), 569 919 instrucțiuni, 232 de tabele de switch; 139 de funcții au salturi indirecte nerezolvate (tail-call-uri prin vtable).
- **9 501 funcții (97,6%) se traduc complet**; 233 conțin instrucțiuni fără regulă:
  - 80 – variantele **3DNow!** ale RenderWare, 48 – variante **MMX/SSE pe întregi**: RenderWare și CRT-ul aleg calea după `cpuid`; dacă `cpuid` raportează un procesor fără MMX/SSE/3DNow!, rămân necalculate;
  - 105 – restul: funcții de matematică din CRT cu x87 transcendental (`fsin`, `fcos`, `fpatan`, `fyl2x`, `f2xm1`, `fscale`, `fprem`, `fxam`), `fld` pe 80 de biți, căile SSE2 ale CRT-ului (`stmxcsr`, `*pd`); 18 funcții cu `imul` pe 8 biți, 3 cu `bt/bts` pe memorie, 1 cu `jecxz`; 2 „funcții” false (`into`, `les`: date).
- **Tot codul generat se compilează pentru Vita** (`arm-vita-eabi-gcc -O2 -mcpu=cortex-a9 -mthumb`, cu `guest.h` din repentogxm): 9 734 de simboluri `sub_*`, 0 erori, 1 avertisment (`-Wstringop-overflow` în `sub_000072b1`, scriere la o adresă constantă), ~4 min pe 8 nuclee.

Următorii pași: reguli noi în emitter (`imul r/m8`, `bt/bts` pe memorie, `jecxz`, x87 transcendental → libm), `cpuid` fără MMX/SSE/3DNow!, propriul `guest.h` cu offset pentru 0x400000, apoi runtime-ul (stiva guest, `guest_call`, importurile Windows) și primul link.
