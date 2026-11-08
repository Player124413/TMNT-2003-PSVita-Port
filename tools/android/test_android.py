#!/usr/bin/env python3
"""Host-side tests for the Android pack pipeline; no game copy or Android SDK required."""
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("make_android_files", ROOT / "tools/make_android_files.py")
android = importlib.util.module_from_spec(spec)
spec.loader.exec_module(android)


class PackTests(unittest.TestCase):
    def stage(self, root):
        stage = root / "stage"
        stage.mkdir()
        for name in ["image.bin", "tmnt_code.so", *android.vita.DATA_FILES.values()]:
            (stage / name).write_bytes(name.encode() * 8)
        return stage

    def test_manifest_is_first_and_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.stage(root)
            out = root / "game.tmnt.zip"
            manifest = android.write_pack(stage, out)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(z.namelist()[0], "pack.json")
                self.assertEqual(len(z.namelist()), 10)
                stored = json.loads(z.read("pack.json"))
            self.assertEqual(stored, manifest)
            self.assertEqual(stored["abi"], "armeabi-v7a")
            self.assertEqual(set(stored["files"]), set(android.vita.DATA_FILES.values()) | {"image.bin", "tmnt_code.so"})
            for name, meta in stored["files"].items():
                self.assertEqual(meta["size"], (stage / name).stat().st_size)
                self.assertEqual(meta["sha256"], android.digest(stage / name))

    def test_missing_file_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.stage(root)
            (stage / "voice.afs").unlink()
            with self.assertRaises(SystemExit):
                android.write_pack(stage, root / "bad.zip")
            self.assertFalse((root / "bad.zip").exists())

    def test_module_must_be_arm_elf32(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "module.so"
            header = bytearray(52)
            header[:6] = b"\x7fELF\x01\x01"
            struct.pack_into("<H", header, 16, 3)
            struct.pack_into("<H", header, 18, 40)
            path.write_bytes(header)
            android.validate_module(path)
            struct.pack_into("<H", header, 18, 62)  # x86-64 is not an Android ARM ABI.
            path.write_bytes(header)
            with self.assertRaises(SystemExit):
                android.validate_module(path)

    def test_module_must_match_requested_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "tmnt_code.so"
            arm64 = bytearray(64)
            arm64[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", arm64, 16, 3)
            struct.pack_into("<H", arm64, 18, 183)
            path.write_bytes(arm64)
            android.validate_module(path, "arm64-v8a")
            with self.assertRaises(SystemExit):
                android.validate_module(path, "armeabi-v7a")
            x86 = bytearray(arm64)
            struct.pack_into("<H", x86, 18, 62)
            path.write_bytes(x86)
            with self.assertRaises(SystemExit):
                android.validate_module(path, "arm64-v8a")

    def test_write_pack_records_requested_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage = self.stage(Path(tmp))
            output = Path(tmp) / "pack.tmnt.zip"
            manifest = android.write_pack(stage, output, "arm64-v8a")
            self.assertEqual(manifest["abi"], "arm64-v8a")
            with zipfile.ZipFile(output) as z:
                self.assertEqual(json.loads(z.read("pack.json")), manifest)


if __name__ == "__main__":
    unittest.main()
