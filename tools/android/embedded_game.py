#!/usr/bin/env python3
"""Manifest and staging helpers for private Android APKs with embedded game code/image.

The generated directory is a Gradle input, not a repository artifact:
  jniLibs/<abi>/libtmnt_code.so
  assets/tmnt/image.bin
  assets/tmnt/embedded.json
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import make_vita_files as vita

FORMAT = 1
ABI = "armeabi-v7a"
SUPPORTED_ABIS = {"armeabi-v7a", "arm64-v8a"}
MAX_TOTAL = 3 * 1024 * 1024 * 1024
CHUNK = 1 << 20
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
HEX40 = re.compile(r"[0-9a-f]{40}\Z")


class EmbeddedGameError(ValueError):
    pass


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def file_meta(path, name=None):
    path = Path(path)
    size = path.stat().st_size
    if size <= 0 or size > MAX_TOTAL:
        raise EmbeddedGameError("invalid embedded file size: " + str(path))
    meta = {"size": size, "sha256": digest(path)}
    if name:
        meta["name"] = name
    return meta


def compute_id(manifest):
    core = {k: v for k, v in manifest.items() if k != "id"}
    text = json.dumps(core, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def make_manifest(code, image, runtime, exe_sha1, game_folder, abi=ABI):
    if abi not in SUPPORTED_ABIS:
        raise EmbeddedGameError("unsupported Android ABI: " + str(abi))
    game_folder = Path(game_folder)
    data = {}
    total = 0
    for source, dest in vita.DATA_FILES.items():
        path = vita.find_file(str(game_folder), source)
        if not path:
            raise EmbeddedGameError("missing PC game file: " + source)
        meta = file_meta(path)
        names = [source]
        if dest.casefold() != source.casefold():
            names.append(dest)
        meta["source"] = source
        meta["names"] = names
        data[dest] = meta
        total += meta["size"]
        if total > MAX_TOTAL:
            raise EmbeddedGameError("PC game data exceeds 3 GiB")
    manifest = {
        "format": FORMAT,
        "abi": abi,
        "exe_sha1": exe_sha1,
        "image": file_meta(image, "image.bin"),
        "code": file_meta(code, "libtmnt_code.so"),
        "runtime_sha256": digest(runtime),
        "data": data,
    }
    manifest["id"] = compute_id(manifest)
    validate_manifest(manifest)
    return manifest


def validate_manifest(manifest, check_supported_exe=True):
    if not isinstance(manifest, dict):
        raise EmbeddedGameError("embedded manifest is not an object")
    if type(manifest.get("format")) is not int or manifest["format"] != FORMAT:
        raise EmbeddedGameError("wrong embedded manifest format")
    if manifest.get("abi") not in SUPPORTED_ABIS:
        raise EmbeddedGameError("wrong embedded manifest ABI")
    for key, pattern in (("id", HEX64), ("exe_sha1", HEX40), ("runtime_sha256", HEX64)):
        value = manifest.get(key)
        if not isinstance(value, str) or not pattern.fullmatch(value):
            raise EmbeddedGameError("invalid " + key)
    if check_supported_exe and manifest["exe_sha1"] != vita.EXE_SHA1:
        raise EmbeddedGameError("embedded manifest does not describe the supported TMNT_EN.EXE")
    if compute_id(manifest) != manifest["id"]:
        raise EmbeddedGameError("embedded manifest id mismatch")
    for key in ("image", "code"):
        meta = manifest.get(key)
        if not isinstance(meta, dict):
            raise EmbeddedGameError("missing " + key + " metadata")
        _validate_file_meta(meta)
    data = manifest.get("data")
    if not isinstance(data, dict) or set(data) != set(vita.DATA_FILES.values()):
        raise EmbeddedGameError("embedded data file list mismatch")
    total = 0
    for dest, meta in data.items():
        if not isinstance(meta, dict):
            raise EmbeddedGameError("invalid data metadata for " + dest)
        _validate_file_meta(meta)
        names = meta.get("names")
        if not isinstance(names, list) or not names:
            raise EmbeddedGameError("missing source names for " + dest)
        seen = set()
        for name in names:
            if not isinstance(name, str) or not name or "/" in name or "\\" in name or name.casefold() in seen:
                raise EmbeddedGameError("invalid source name for " + dest)
            seen.add(name.casefold())
        if meta.get("source") not in names:
            raise EmbeddedGameError("source name missing from aliases for " + dest)
        total += meta["size"]
        if total > MAX_TOTAL:
            raise EmbeddedGameError("embedded data exceeds 3 GiB")
    return manifest


def _validate_file_meta(meta):
    size = meta.get("size")
    sha = meta.get("sha256")
    if type(size) is not int or size <= 0 or size > MAX_TOTAL:
        raise EmbeddedGameError("invalid embedded file size")
    if not isinstance(sha, str) or not HEX64.fullmatch(sha):
        raise EmbeddedGameError("invalid embedded file SHA-256")


def write_directory(directory, module, image, manifest):
    validate_manifest(manifest)
    directory = Path(directory)
    libs = directory / "jniLibs" / manifest["abi"]
    assets = directory / "assets" / "tmnt"
    libs.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(module, libs / "libtmnt_code.so")
    shutil.copyfile(image, assets / "image.bin")
    (assets / "embedded.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    # The staged copies, not the temporary compiler outputs, are what Gradle packages.
    staged = json.loads((assets / "embedded.json").read_text(encoding="utf-8"))
    staged["code"] = file_meta(libs / "libtmnt_code.so", "libtmnt_code.so")
    staged["image"] = file_meta(assets / "image.bin", "image.bin")
    staged["id"] = compute_id(staged)
    validate_manifest(staged)
    (assets / "embedded.json").write_text(json.dumps(staged, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return staged
