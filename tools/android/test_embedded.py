#!/usr/bin/env python3
"""Host tests for private embedded-APK manifests; no game, SDK or network required."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools/android"))
import make_vita_files as vita
spec = importlib.util.spec_from_file_location("embedded_game", ROOT / "tools/android/embedded_game.py")
embedded = importlib.util.module_from_spec(spec)
spec.loader.exec_module(embedded)


class EmbeddedTests(unittest.TestCase):
    def stage(self, root):
        game = root / "game"
        game.mkdir()
        for source in vita.DATA_FILES:
            (game / source).write_bytes(("data:" + source).encode() * 16)
        code = root / "libtmnt_code.so"
        image = root / "image.bin"
        runtime = root / "libtmnt_runtime.so"
        code.write_bytes(b"synthetic-code" * 32)
        image.write_bytes(b"synthetic-image" * 32)
        runtime.write_bytes(b"synthetic-runtime" * 32)
        return game, code, image, runtime

    def manifest(self, root):
        game, code, image, runtime = self.stage(root)
        return embedded.make_manifest(code, image, runtime, vita.EXE_SHA1, game), game, code, image, runtime

    def test_manifest_and_staged_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest, game, code, image, runtime = self.manifest(root)
            self.assertEqual(manifest["abi"], "armeabi-v7a")
            self.assertEqual(set(manifest["data"]), set(vita.DATA_FILES.values()))
            self.assertEqual(manifest["data"]["TMNT.DAT"]["names"], ["TMNT_EN.DAT", "TMNT.DAT"])
            out = root / "embedded"
            staged = embedded.write_directory(out, code, image, manifest)
            self.assertTrue((out / "jniLibs/armeabi-v7a/libtmnt_code.so").is_file())
            self.assertTrue((out / "assets/tmnt/image.bin").is_file())
            stored = json.loads((out / "assets/tmnt/embedded.json").read_text())
            self.assertEqual(stored, staged)
            embedded.validate_manifest(stored)
            self.assertEqual(staged["code"]["sha256"], embedded.digest(out / "jniLibs/armeabi-v7a/libtmnt_code.so"))
            self.assertEqual(staged["image"]["sha256"], embedded.digest(out / "assets/tmnt/image.bin"))

    def test_id_binds_code_image_runtime_and_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest, game, code, image, runtime = self.manifest(root)
            original = manifest["id"]
            (game / "bgm.afs").write_bytes(b"different data")
            changed = embedded.make_manifest(code, image, runtime, vita.EXE_SHA1, game)
            self.assertNotEqual(original, changed["id"])
            tampered = json.loads(json.dumps(manifest))
            tampered["image"]["sha256"] = "0" * 64
            with self.assertRaises(embedded.EmbeddedGameError):
                embedded.validate_manifest(tampered)

    def test_rejects_unsafe_source_names_and_missing_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest, *_ = self.manifest(root)
            bad = json.loads(json.dumps(manifest))
            bad["data"]["bgm.afs"]["names"] = ["../bgm.afs"]
            bad["id"] = embedded.compute_id(bad)
            with self.assertRaises(embedded.EmbeddedGameError):
                embedded.validate_manifest(bad)
            bad = json.loads(json.dumps(manifest))
            del bad["data"]["voice.afs"]
            bad["id"] = embedded.compute_id(bad)
            with self.assertRaises(embedded.EmbeddedGameError):
                embedded.validate_manifest(bad)

    def test_missing_pc_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game, code, image, runtime = self.stage(root)
            (game / "se.afs").unlink()
            with self.assertRaises(embedded.EmbeddedGameError):
                embedded.make_manifest(code, image, runtime, vita.EXE_SHA1, game)


if __name__ == "__main__":
    unittest.main()
