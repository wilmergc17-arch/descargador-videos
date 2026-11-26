"""Prueba con internet real (solo informativa en GitHub Actions: las redes suelen bloquear esos servidores)."""
import sys
import tempfile
import threading
from pathlib import Path

import core

LINKS = sys.argv[1:] or ["https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://vimeo.com/76979871"]
print("yt-dlp", core.ytdlp_version(), "| ffmpeg", core.ffmpeg_path())
for link in LINKS:
    d = Path(tempfile.mkdtemp())
    try:
        files = core.download(link, "720", lambda t, p: None, threading.Event(), dest=d)
        print("OK  ", link, [f"{f.name} ({f.stat().st_size // 1024} KB)" for f in files])
    except Exception as e:
        print("FALLA", link, str(e).replace("\n", " ")[:300])
    print(core.last_report())
    print("-" * 60)
