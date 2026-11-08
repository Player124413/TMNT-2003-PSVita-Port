#!/usr/bin/env python3
"""Verify a built Android APK and exercise the private game-pack ABI without game data.

Clean APK: checks signing/alignment/manifest/ABI, then compiles a tiny synthetic tmnt_code.so
against the runtime extracted from the APK and writes a synthetic .tmnt.zip. The fixture contains
no game code or data and must never be presented as a playable game pack.

Private APK (-PtmntEmbeddedDir): additionally checks the embedded libtmnt_code.so, image.bin and
embedded.json hashes, and that no PC data files were packaged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools/android"))
import make_android_files as android
import embedded_game as embedded


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
        text = "APK verification failed: " + message
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print("::error::" + text, flush=True)
        raise SystemExit(text)


def elf_android(path, abi):
    cfg = android.abi_config(abi)
    with open(path, "rb") as f:
        data = f.read(64)
    check(len(data) >= 52 and data[:4] == b"\x7fELF" and data[5] == 1, str(path) + " is not little-endian ELF")
    check(data[4] == cfg["elf_class"], str(path) + " has wrong ELF class for " + abi)
    check(struct.unpack_from("<H", data, 16)[0] == 3, str(path) + " is not ET_DYN")
    check(struct.unpack_from("<H", data, 18)[0] == cfg["machine"], str(path) + " has wrong machine for " + abi)


def zip_hash(archive, name):
    h = hashlib.sha256()
    size = 0
    with archive.open(name) as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            size += len(block)
            h.update(block)
    return size, h.hexdigest()


def synthetic_module(clang, runtime, work, soname=None, abi=android.ABI):
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
    cfg = android.abi_config(abi)
    flags = [str(clang), "--target=" + cfg["target"], *android.cflags(abi), "-I", str(ROOT / "rt")]
    objects = []
    for index, src in enumerate((source, ROOT / "rt/code_so_entry.c")):
        obj = work / ("synthetic-%d.o" % index)
        run(flags + ["-c", str(src), "-o", str(obj)])
        objects.append(obj)
    module = work / (soname or "tmnt_code.so")
    command = [str(clang), "--target=" + cfg["target"], "-shared", "-Wl,-z,max-page-size=16384",
               "-Wl,-z,now", "-Wl,--no-undefined"]
    if soname:
        command += ["-Wl,-soname," + soname]
    command += ["-o", str(module), *map(str, objects), str(runtime), "-lm"]
    run(command)
    elf_android(module, abi)
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

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        check(len(names) == len(set(names)), "duplicate APK entries")
        embedded_mode = "assets/tmnt/embedded.json" in names
        manifest = None
        if embedded_mode:
            info = z.getinfo("assets/tmnt/embedded.json")
            check(info.file_size <= 65536, "embedded manifest too large")
            try:
                manifest = json.loads(z.read(info).decode("utf-8"))
                embedded.validate_manifest(manifest)
            except (ValueError, UnicodeError, embedded.EmbeddedGameError) as exc:
                check(False, "invalid embedded manifest: " + str(exc))
            check("assets/tmnt/image.bin" in names, "embedded image.bin asset missing")

    print(output(tool_command(bt, "apksigner") + ["verify", "--verbose", "--print-certs", str(apk)]))
    run(tool_command(bt, "zipalign") + ["-c", "-p", "-v", "4", str(apk)], stdout=subprocess.DEVNULL)
    badging = output(tool_command(bt, "aapt2") + ["dump", "badging", str(apk)])
    print(badging)
    def badge(field):
        m = re.search(r"^" + re.escape(field) + r":\s*(?:name=)?'([^']*)'", badging, re.MULTILINE)
        return m.group(1) if m else None
    def require(value, expected, label):
        if value != expected:
            detail = "%s: expected %r, got %r" % (label, expected, value)
            if os.environ.get("GITHUB_ACTIONS") == "true":
                print("::error::" + detail + "\nBadging header:\n" + "\n".join(badging.splitlines()[:12]), flush=True)
            raise SystemExit("APK verification failed: " + detail)
    require(badge("package"), "org.tmnt.port.private" if embedded_mode else "org.tmnt.port", "application id")
    require(badge("sdkVersion") or badge("minSdkVersion"), "26", "minSdk")
    require(badge("targetSdkVersion"), "35", "targetSdk")
    require(badge("launchable-activity"), "org.tmnt.port.LauncherActivity", "launcher activity")
    check("uses-permission: name='android.permission.INTERNET'" not in badging, "APK must not request INTERNET")
    abi = badge("native-code")
    check(abi in android.ABIS, "unsupported native ABI: " + repr(abi))
    if embedded_mode:
        check(manifest["abi"] == abi, "embedded manifest ABI does not match APK")
    if abi == "arm64-v8a":
        # Android 15+ 16 KiB-page devices require uncompressed native libraries to be 16 KiB aligned.
        run(tool_command(bt, "zipalign") + ["-c", "-P", "16", "-v", "4", str(apk)], stdout=subprocess.DEVNULL)

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        check("classes.dex" in names, "classes.dex missing")
        check("AndroidManifest.xml" in names and "resources.arsc" in names, "manifest/resources missing")
        libs = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        expected_libs = ["lib/" + abi + "/libSDL2.so", "lib/" + abi + "/libtmnt_runtime.so"]
        if embedded_mode:
            expected_libs.insert(1, "lib/" + abi + "/libtmnt_code.so")
        check(libs == expected_libs, "unexpected native libraries: " + repr(libs))
        forbidden = {".exe", ".dat", ".afs"}
        check(not any(Path(n).suffix.lower() in forbidden for n in names), "PC game data found inside APK")
        bin_files = [n for n in names if Path(n).suffix.lower() == ".bin"]
        if embedded_mode:
            check(bin_files == ["assets/tmnt/image.bin"], "unexpected .bin assets: " + repr(bin_files))
        else:
            check(not bin_files, "game image found inside clean APK")
        check("pack.json" not in names, "importable pack manifest must not be inside the APK")
        check(any(n.startswith("assets/licenses/") for n in names), "license assets missing")
        for name in libs:
            info = z.getinfo(name)
            check(info.compress_type == zipfile.ZIP_STORED, name + " must be stored uncompressed")
        libdir = args.work / "apk-lib"
        libdir.mkdir(exist_ok=True)
        for name in libs:
            target = libdir / Path(name).name
            target.write_bytes(z.read(name))
            elf_android(target, abi)
            if abi == "arm64-v8a":
                segments = output([readelf, "-l", str(target)])
                loads = [line.split() for line in segments.splitlines() if line.strip().startswith("LOAD")]
                check(loads and all(parts[-1] == "0x4000" for parts in loads),
                      name + " is not linked for 16 KiB pages")
        if embedded_mode:
            size, digest = zip_hash(z, "assets/tmnt/image.bin")
            check(size == manifest["image"]["size"], "embedded image size mismatch")
            check(digest == manifest["image"]["sha256"], "embedded image SHA-256 mismatch")
            size, digest = zip_hash(z, "lib/" + abi + "/libtmnt_code.so")
            check(size == manifest["code"]["size"], "embedded code size mismatch")
            check(digest == manifest["code"]["sha256"], "embedded code SHA-256 mismatch")

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

    if embedded_mode:
        check(embedded.digest(runtime) == manifest["runtime_sha256"], "packaged runtime does not match embedded manifest")
        code = libdir / "libtmnt_code.so"
        code_dynamic = output([readelf, "-d", str(code)])
        check("SONAME" in code_dynamic and "libtmnt_code.so" in code_dynamic, "embedded code SONAME missing/wrong")
        check("libtmnt_runtime.so" in code_dynamic, "embedded code does not depend on libtmnt_runtime.so")
        check("tmnt_code" in output([nm, "-D", str(code)]), "embedded code does not export tmnt_code")
        print("OK: private APK is signed/aligned, embeds matching code/image, and contains no PC data files.")
    else:
        with tempfile.TemporaryDirectory(prefix="tmnt-apk-verify-", dir=args.work) as tmp:
            work = Path(tmp)
            module = synthetic_module(clang, runtime, work, abi=abi)
            module_dynamic = output([readelf, "-d", str(module)])
            check("libtmnt_runtime.so" in module_dynamic, "synthetic module does not link against APK runtime")
            check("tmnt_code" in output([nm, "-D", str(module)]), "synthetic module does not export tmnt_code")
            stage = work / "pack"
            stage.mkdir()
            shutil.copyfile(module, stage / "tmnt_code.so")
            for name in ["image.bin", *android.vita.DATA_FILES.values()]:
                (stage / name).write_bytes(("synthetic-no-game-data:" + name).encode() * 32)
            pack = args.work / "synthetic-pack.tmnt.zip"
            manifest = android.write_pack(stage, pack, abi)
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
