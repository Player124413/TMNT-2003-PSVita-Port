# Instalări pentru portul TMNT 2003

- 2026-10-19: `.venv/` (Python local al proiectului) cu `pefile` și `capstone`, pentru inventarul exe-ului. Se șterge cu `rm -rf .venv`.
- 2026-10-19: Rosetta 2 (sistem, `softwareupdate --install-rosetta`); necesară pentru Wine. Rămâne instalată.
- 2026-10-19: Wine Devel 11.18 (build Gcenx de pe GitHub, carantina scoasă) în `tools/wine/`, prefix în `wineprefix/`. Homebrew a dezactivat cask-urile Wine pe 2026-09-01. Se șterge cu `rm -rf tools/wine wineprefix`.
