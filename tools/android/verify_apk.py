#!/usr/bin/env python3
"""Verify a built Android APK and exercise the private game-pack ABI without game data.

Checks signing/alignment/manifest/ABI, then compiles a tiny synthetic tmnt_code.so against the
runtime extracted from the APK and writes a synthetic .tmnt.zip. The fixture contains no game
code or data and must never be presented as a playable game pack.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import make_android_files as android


def run(cmd, **kw):
    print("+", " ".join(map(str, cmd)), flush=True)
    return subprocess.run(list(map(str, cmd)), check=True, text=True, **kw)


def output(cmd):
    return run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


def sdk_root(explicit):
    for value in (explicit, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if value and Path(value).is_dir():
            return Path(value)
    raise SystemExit("Android SDK not found; set ANDROID_HOME or pass --sdk")


def build_tools(sdk, explicit):
    if explicit:
        p = Path(explicit)
        if p.is_dir():
            return p
        raise SystemExit("--build-tools is not a directory: " + str(p))
    base = sdk / "build-tools"
    for name in ("35.0.0", "35.0.1", "35.0.2", "34.0.0"):
        if (base / name).is_dir():
            return base / name
    candidates = sorted((p for p in base.iterdir() if p.is_dir()), reverse=True) if base.is_dir() else []
    if candidates:
        return candidates[0]
    raise SystemExit("Android build-tools not found in " + str(base))


def tool_command(directory, name):
    exe = directory / (name + (".exe" if os.name == "nt" else ""))
    if exe.is_file():
        return [str(exe)]
    jar = directory / (name + ".jar")
    if jar.is_file():
        java = shutil.which("java")
        if not java:
            raise SystemExit("java is required to run " + str(jar))
        return [java, "-jar", str(jar)]
    raise SystemExit("missing build tool: " + name + " in " + str(directory))


def readelf_tool(ndk_clang, explicit):
    if explicit:
        return explicit
    candidate = Path(ndk_clang).parent / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
    if candidate.is_file():
        return str(candidate)
    for name in ("llvm-readelf", "readelf"):
        found = shutil.which(name)
        if found:
            return found
    raise SystemExit("llvm-readelf/readelf not found")


def nm_tool(ndk_clang):
    candidate = Path(ndk_clang).parent / ("llvm-nm.exe" if os.name == "nt" else "llvm-nm")
    if candidate.is_file():
        return str(candidate)
    found = shutil.which("llvm-nm") or shutil.which("nm")
    if found:
        return found
    raise SystemExit("llvm-nm/nm not found")


def check(condition, message):
    if not condition:
        raise SystemExit("APK verification failed: " + message)


def elf32_arm(path):
    data = Path(path).read_bytes()[:52]
    check(len(data) >= 52 and data[:6] == b"\x7fELF\x01\x01", str(path) + " is not ELF32 little-endian")
    check(struct.unpack_from("<H", data, 16)[0] == 3, str(path) + " is not ET_DYN")
    check(struct.unpack_from("<H", data, 18)[0] == 40, str(path) + " is not ARM")


def synthetic_module(clang, runtime, work):
    source = work / "synthetic_module.c"
    source.write_text(r'''
#include <stdint.h>
#include "guest.h"
#include "code_so.h"
const uint32_t guest_func_rvas[1];
const guest_fn guest_func_ptrs[1];
const uint32_t guest_func_count;
const uint32_t guest_image_entry;
const uint32_t guest_image_size;
const guest_import_slot guest_import_slots[1];
const uint32_t guest_import_count;
#define DECL(name) void name(CPU *__restrict c) { (void)c; }
TMNT_CODE_DIRECT(DECL)
#undef DECL
''', encoding="utf-8")
    flags = [str(clang), "--target=armv7a-linux-androideabi26", *android.CFLAGS, "-I", str(ROOT / "rt")]
    objects = []
    for index, src in enumerate((source, ROOT / "rt/code_so_entry.c")):
        obj = work / ("synthetic-%d.o" % index)
        run(flags + ["-c", str(src), "-o", str(obj)])
        objects.append(obj)
    module = work / "tmnt_code.so"
    run([str(clang), "--target=armv7a-linux-androideabi26", "-shared", "-Wl,-z,max-page-size=16384",
         "-Wl,-z,now", "-Wl,--no-undefined", "-o", str(module), *map(str, objects), str(runtime), "-lm"])
    elf32_arm(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk")
    parser.add_argument("--ndk")
    parser.add_argument("--build-tools")
    parser.add_argument("--clang")
    parser.add_argument("--readelf")
    parser.add_argument("--work", type=Path, default=ROOT / "build/ci")
    args = parser.parse_args()
    apk = args.apk.resolve()
    check(apk.is_file(), "APK does not exist: " + str(apk))
    args.work.mkdir(parents=True, exist_ok=True)
    sdk = sdk_root(args.sdk)
    bt = build_tools(sdk, args.build_tools)
    clang = args.clang or android.find_ndk(args.ndk)
    readelf = readelf_tool(clang, args.readelf)
    nm = nm_tool(clang)

    print(output(tool_command(bt, "apksigner") + ["verify", "--verbose", "--print-certs", str(apk)]))
    run(tool_command(bt, "zipalign") + ["-c", "-p", "-v", "4", str(apk)], stdout=subprocess.DEVNULL)
    badging = output(tool_command(bt, "aapt2") + ["dump", "badging", str(apk)])
    print(badging)
    check("package: name='org.tmnt.port'" in badging, "wrong application id")
    check("sdkVersion:'26'" in badging, "wrong minSdk")
    check("targetSdkVersion:'35'" in badging, "wrong targetSdk")
    check("launchable-activity: name='org.tmnt.port.LauncherActivity'" in badging, "launcher activity missing")
    check("uses-permission: name='android.permission.INTERNET'" not in badging, "APK must not request INTERNET")
    check("native-code: 'armeabi-v7a'" in badging, "wrong native ABI")

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        check(len(names) == len(set(names)), "duplicate APK entries")
        check("classes.dex" in names, "classes.dex missing")
        check("AndroidManifest.xml" in names and "resources.arsc" in names, "manifest/resources missing")
        libs = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        check(libs == ["lib/armeabi-v7a/libSDL2.so", "lib/armeabi-v7a/libtmnt_runtime.so"], "unexpected native libraries: " + repr(libs))
        forbidden = {".exe", ".dat", ".afs", ".bin"}
        check(not any(Path(n).suffix.lower() in forbidden for n in names), "game data found inside APK")
        check(any(n.startswith("assets/licenses/") for n in names), "license assets missing")
        for name in libs:
            info = z.getinfo(name)
            check(info.compress_type == zipfile.ZIP_STORED, name + " must be stored uncompressed")
        libdir = args.work / "apk-lib"
        libdir.mkdir(exist_ok=True)
        for name in libs:
            target = libdir / Path(name).name
            target.write_bytes(z.read(name))
            elf32_arm(target)

    runtime = libdir / "libtmnt_runtime.so"
    sdl = libdir / "libSDL2.so"
    dynamic = output([readelf, "-d", str(runtime)])
    check("SONAME" in dynamic and "libtmnt_runtime.so" in dynamic, "runtime SONAME missing/wrong")
    check("libSDL2.so" in dynamic, "runtime does not depend on libSDL2.so")
    check("RUNPATH" not in dynamic and "RPATH" not in dynamic, "runtime must not contain host RPATH/RUNPATH")
    symbols = output([nm, "-D", str(runtime)])
    for symbol in ("SDL_main", "JNI_OnLoad", "Java_org_tmnt_port_NativeBridge_touchButtons", "dsound_mix"):
        check(symbol in symbols, "runtime export missing: " + symbol)
    sdl_dynamic = output([readelf, "-d", str(sdl)])
    check("SONAME" in sdl_dynamic and "libSDL2.so" in sdl_dynamic, "SDL SONAME missing/wrong")

    with tempfile.TemporaryDirectory(prefix="tmnt-apk-verify-", dir=args.work) as tmp:
        work = Path(tmp)
        module = synthetic_module(clang, runtime, work)
        module_dynamic = output([readelf, "-d", str(module)])
        check("libtmnt_runtime.so" in module_dynamic, "synthetic module does not link against APK runtime")
        check("tmnt_code" in output([nm, "-D", str(module)]), "synthetic module does not export tmnt_code")
        stage = work / "pack"
        stage.mkdir()
        shutil.copyfile(module, stage / "tmnt_code.so")
        for name in ["image.bin", *android.vita.DATA_FILES.values()]:
            (stage / name).write_bytes(("synthetic-no-game-data:" + name).encode() * 32)
        pack = args.work / "synthetic-pack.tmnt.zip"
        manifest = android.write_pack(stage, pack)
        with zipfile.ZipFile(pack) as z:
            check(z.namelist()[0] == "pack.json", "synthetic pack manifest is not first")
            check(json.loads(z.read("pack.json")) == manifest, "synthetic pack manifest mismatch")
            check(len(z.namelist()) == 10, "synthetic pack entry count mismatch")
            for name, meta in manifest["files"].items():
                check(hashlib.sha256(z.read(name)).hexdigest() == meta["sha256"], "synthetic pack hash mismatch: " + name)
    print("OK: APK is signed/aligned, contains only port code, and the synthetic game-pack ABI link passed.")
    print("Synthetic fixture (not playable):", args.work / "synthetic-pack.tmnt.zip")
    print("APK SHA-256:", hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
