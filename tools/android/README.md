# Android build helpers

- `fetch_deps.py` downloads pinned SDL2/gl4es sources into `build/android-deps/` and verifies SHA-256.
- `../make_android_files.py` translates the user's supported EXE and builds a private `.tmnt.zip` game pack.
- `test_android.py` tests pack manifest/order/hash metadata and ARM ELF validation without game data.
- `test_touch.c` tests the atomic touch/pause/perf contract on the host.

The Gradle project is `../../android`. Generated game code/data must never be committed or bundled into the APK.
