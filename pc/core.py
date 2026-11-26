"""Motor de descarga de "Descargar Video" para PC.

Mismo orden de métodos que la app de Android:
  1. TikTok sin cuenta (tikwm / tiklydown), X (vxtwitter / fxtwitter) y Ok.ru propio.
  2. yt-dlp (se actualiza solo una vez al día).
  3. TikTok por id (si yt-dlp reconoció el video pero no pudo bajarlo).
  4. Servidores públicos de cobalt.
  5. yt-dlp con la sesión del navegador elegido (si la red pidió cuenta).
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, List, Optional

APP_NAME = "Descargar Video"

UA_MOBILE = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
)
UA_DESKTOP = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

IS_WINDOWS = os.name == "nt"
NO_WINDOW = 0x08000000 if IS_WINDOWS else 0  # CREATE_NO_WINDOW: sin ventanas negras de consola

Progress = Callable[[str, float], None]  # (texto, porcentaje 0-100 o -1 si no se sabe)


class Cancelled(Exception):
    pass


# ---------------------------------------------------------------- carpetas

def app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") if IS_WINDOWS else None
    d = Path(base) / "DescargarVideo" if base else Path.home() / ".descargar-video"
    d.mkdir(parents=True, exist_ok=True)
    return d


def downloads_dir() -> Path:
    """Carpeta Descargas real de Windows (aunque el usuario la haya movido) + subcarpeta propia."""
    base = None
    if IS_WINDOWS:
        try:
            import ctypes
            from ctypes import wintypes
            import uuid

            guid = uuid.UUID("{374DE290-123F-4565-9164-39C4925E467B}")  # FOLDERID_Downloads

            class GUID(ctypes.Structure):
                _fields_ = [("d1", wintypes.DWORD), ("d2", wintypes.WORD),
                            ("d3", wintypes.WORD), ("d4", ctypes.c_ubyte * 8)]

            g = GUID(guid.fields[0], guid.fields[1], guid.fields[2],
                     (ctypes.c_ubyte * 8).from_buffer_copy(guid.bytes[8:]))
            ptr = ctypes.c_wchar_p()
            if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(ptr)) == 0:
                base = Path(ptr.value)
                ctypes.windll.ole32.CoTaskMemFree(ptr)
        except Exception:
            base = None
    if base is None:
        base = Path.home() / "Downloads"
    d = base / APP_NAME
    d.mkdir(parents=True, exist_ok=True)
    return d


def load_config() -> dict:
    try:
        return json.loads((data_dir() / "config.json").read_text("utf-8"))
    except Exception:
        return {}


def save_config(cfg: dict) -> None:
    try:
        (data_dir() / "config.json").write_text(json.dumps(cfg, indent=2), "utf-8")
    except Exception:
        pass


# ---------------------------------------------------------------- HTTP

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_opener = urllib.request.build_opener()
_no_redirect = urllib.request.build_opener(_NoRedirect)


def _request(url, data=None, headers=None, method=None):
    h = {"User-Agent": UA_MOBILE, "Accept": "*/*", "Accept-Language": "es,en;q=0.8"}
    if headers:
        h.update(headers)
    return urllib.request.Request(url, data=data, headers=h, method=method)


def http_get(url: str, headers: Optional[dict] = None, timeout=20) -> str:
    with _opener.open(_request(url, headers=headers), timeout=timeout) as r:
        return r.read(5_000_000).decode("utf-8", "replace")


def http_post_form(url: str, form: str, headers: Optional[dict] = None) -> str:
    h = {"Content-Type": "application/x-www-form-urlencoded"}
    h.update(headers or {})
    with _opener.open(_request(url, form.encode(), h, "POST"), timeout=20) as r:
        return r.read(5_000_000).decode("utf-8", "replace")


def http_post_json(url: str, body: dict) -> str:
    h = {"Content-Type": "application/json", "Accept": "application/json", "User-Agent": UA_DESKTOP}
    req = _request(url, json.dumps(body).encode(), h, "POST")
    try:
        with _opener.open(req, timeout=30) as r:
            return r.read(2_000_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:  # cobalt responde errores con JSON y código 4xx
        body = e.read(200_000).decode("utf-8", "replace")
        if body.strip().startswith("{"):
            return body
        raise


def http_save(url: str, path: Path, progress: Progress, cancel: threading.Event,
              headers: Optional[dict] = None, label="Descargando") -> Path:
    with _opener.open(_request(url, headers=headers), timeout=30) as r:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        last = 0.0
        with open(path, "wb") as f:
            while True:
                if cancel.is_set():
                    raise Cancelled()
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                now = time.time()
                if now - last > 0.25:
                    last = now
                    progress(label, done * 100 / total if total else -1)
    if path.stat().st_size < 1024:
        raise IOError("el archivo bajado está vacío")
    return path


def safe_name(text: str, fallback: str) -> str:
    text = re.sub(r"[\\/:*?\"<>|\r\n\t]+", " ", text or "")
    text = re.sub(r"#\S+", "", text)
    text = re.sub(r"\s+", " ", text).strip(" .")
    return (text[:70].strip() or fallback)


def guess_ext(url: str, kind: str) -> str:
    path = urllib.parse.urlparse(url).path.lower()
    for ext in ("mp4", "jpg", "jpeg", "png", "webp", "gif", "mp3", "m4a", "webm", "mov"):
        if path.endswith("." + ext):
            return ext
    return "jpg" if kind in ("photo", "image") else "mp4"


# ---------------------------------------------------------------- enlaces

URL_IN_TEXT = re.compile(r"https?://\S+")
STORES = {"play.google.com", "apps.apple.com", "itunes.apple.com", "market.android.com"}
SHORTENERS = {
    "vm.tiktok.com", "vt.tiktok.com", "t.tiktok.com", "lite.tiktok.com", "tt.site", "t.co", "fb.watch",
    "bit.ly", "tinyurl.com", "pin.it", "redd.it", "on.soundcloud.com", "ok.me",
}
TIKTOK_DOMAINS = ("tiktok.com", "tt.site", "tiktokv.com", "tiktok.us")
TIKTOK_ID = re.compile(r"/(video|photo|v)/(\d{8,})")
VIDEO_IN_HTML = re.compile(r"https://(?:www|m)\.tiktok\.com/@[^\"'\s/?]+/(?:video|photo)/\d{8,}")
AWEME_ID = re.compile(r"aweme/detail/(\d{8,})")

# Redes para las que la descarga automática al copiar un enlace tiene sentido.
SOCIAL_HOSTS = (
    "tiktok.com", "tt.site", "instagram.com", "facebook.com", "fb.watch", "fb.com", "x.com", "twitter.com",
    "youtube.com", "youtu.be", "ok.ru", "ok.me", "vk.com", "vkvideo.ru", "reddit.com", "redd.it", "pinterest.com",
    "pin.it", "threads.net", "threads.com", "vimeo.com", "dailymotion.com", "twitch.tv", "kwai.com",
    "likee.video", "snapchat.com", "bilibili.com", "soundcloud.com", "streamable.com", "tumblr.com",
    "bsky.app", "rumble.com", "loom.com",
)


def host_of(url: str) -> str:
    try:
        return (urllib.parse.urlparse(url).hostname or "").lower()
    except Exception:
        return ""


def _bare(host: str) -> str:
    return host[4:] if host.startswith("www.") else host


def host_matches(host: str, domains) -> bool:
    h = _bare(host)
    return any(h == d or h.endswith("." + d) for d in domains)


def extract_url(text: Optional[str]) -> Optional[str]:
    if not text:
        return None
    m = URL_IN_TEXT.search(text.strip())
    return m.group(0).rstrip(".,)\"'") if m else None


def is_social_link(text: Optional[str]) -> bool:
    url = extract_url(text)
    return bool(url) and host_matches(host_of(url), SOCIAL_HOSTS)


def is_tiktok(url: str) -> bool:
    return host_matches(host_of(url), TIKTOK_DOMAINS)


def app_link_to_web(link: str) -> Optional[str]:
    if link.startswith("http"):
        return None
    m = AWEME_ID.search(link)
    return f"https://www.tiktok.com/@i/video/{m.group(1)}" if m else None


def _needs_resolving(url: str) -> bool:
    host = _bare(host_of(url))
    if not host:
        return False
    if host in SHORTENERS:
        return True
    return is_tiktok(url) and not TIKTOK_ID.search(urllib.parse.urlparse(url).path)


def _next_hop(url: str) -> Optional[str]:
    for ua in (UA_DESKTOP, UA_MOBILE):
        try:
            try:
                r = _no_redirect.open(_request(url, headers={"User-Agent": ua}), timeout=10)
            except urllib.error.HTTPError as e:
                if 300 <= e.code < 400:
                    raw = e.headers.get("Location")
                    if raw:
                        web = app_link_to_web(raw)
                        if web:
                            return web
                        loc = urllib.parse.urljoin(url, raw)
                        if host_of(loc) not in STORES:
                            return loc
                continue
            with r:
                html = r.read(300_000).decode("utf-8", "replace")
            html = html.replace("\\u002F", "/").replace("\\/", "/")
            m = VIDEO_IN_HTML.search(html)
            if m:
                return m.group(0)
        except Exception:
            pass
    return None


def normalize(url: str) -> str:
    if is_tiktok(url):
        path = urllib.parse.urlparse(url).path
        m = TIKTOK_ID.search(path)
        if m:
            u = re.search(r"/@([^/]+)", path)
            kind = "photo" if m.group(1) == "photo" else "video"
            return f"https://www.tiktok.com/@{u.group(1) if u else 'i'}/{kind}/{m.group(2)}"
    return url


def resolve(url: str) -> str:
    """Enlaces cortos (vt.tiktok.com, tt.site, t.co, fb.watch...) -> enlace real, sin pasar por tiendas de apps."""
    current = url
    if _needs_resolving(current):
        for _ in range(8):
            nxt = _next_hop(current)
            if not nxt:
                break
            if not nxt.startswith("http") or host_of(nxt) in STORES:
                break
            current = nxt
            if not _needs_resolving(current):
                break
    return normalize(current)


# ---------------------------------------------------------------- yt-dlp y ffmpeg

def bin_dir() -> Path:
    return app_dir() / "bin"


def ytdlp_cmd() -> List[str]:
    exe = bin_dir() / ("yt-dlp.exe" if IS_WINDOWS else "yt-dlp")
    if exe.exists():
        return [str(exe)]
    found = shutil.which("yt-dlp")
    if found:
        return [found]
    return [sys.executable, "-m", "yt_dlp"]


def ffmpeg_path() -> Optional[str]:
    exe = bin_dir() / ("ffmpeg.exe" if IS_WINDOWS else "ffmpeg")
    if exe.exists():
        return str(exe)
    return shutil.which("ffmpeg")


def _run(cmd: List[str], timeout=None) -> subprocess.CompletedProcess:
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=timeout, creationflags=NO_WINDOW, env=env)


_update_lock = threading.Lock()


def ytdlp_version() -> str:
    try:
        return _run(ytdlp_cmd() + ["--version"], timeout=60).stdout.strip() or "?"
    except Exception as e:
        return f"no disponible ({e})"


def update_ytdlp(force=False) -> str:
    """Las redes cambian seguido: actualiza yt-dlp como mucho una vez al día. Si falla, sigue con la actual."""
    cfg = load_config()
    if not force and time.time() - cfg.get("ultima_actualizacion", 0) < 24 * 3600:
        return ""
    with _update_lock:
        try:
            cmd = ytdlp_cmd()
            if len(cmd) > 1:  # modo desarrollo (python -m yt_dlp): no se autoactualiza
                return "yt-dlp en modo desarrollo"
            out = _run(cmd + ["-U"], timeout=180)
            cfg = load_config()
            cfg["ultima_actualizacion"] = time.time()
            save_config(cfg)
            return (out.stdout + out.stderr).strip().splitlines()[-1] if (out.stdout + out.stderr).strip() else ""
        except Exception as e:
            return f"no se pudo actualizar: {e}"


PROGRESS_RE = re.compile(r"\[PROG\]\s*([\d.]+)%")

QUALITIES = {
    "compatible": "Mejor calidad (compatible)",
    "max": "Máxima calidad (4K si hay)",
    "1080": "Hasta 1080p",
    "720": "Hasta 720p",
    "480": "Hasta 480p (más liviano)",
    "audio": "Solo audio (MP3)",
}


def _quality_args(quality: str) -> List[str]:
    if quality == "audio":
        return ["-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"]
    args = ["-f", "bv*+ba/b", "--merge-output-format", "mp4"]
    if quality == "max":
        return args + ["-S", "res,fps"]
    if quality in ("1080", "720", "480"):
        return args + ["-S", f"res:{quality},vcodec:h264,acodec:m4a"]
    # H.264 + AAC se reproduce en cualquier reproductor de Windows, celular o TV.
    return args + ["-S", "vcodec:h264,res,acodec:m4a"]


class YtDlpError(Exception):
    pass


def run_ytdlp(url: str, quality: str, out_dir: Path, progress: Progress, cancel: threading.Event,
              referer: Optional[str] = None, browser: Optional[str] = None, log=None) -> None:
    with _update_lock:  # no usar yt-dlp mientras se está reemplazando el ejecutable
        pass
    cmd = ytdlp_cmd() + [
        "--newline", "--progress", "--no-playlist", "--no-mtime", "--windows-filenames",
        "--no-colors", "--encoding", "utf-8",
        "--progress-template", "download:[PROG]%(progress._percent_str)s",
        "-o", str(out_dir / "%(title).70B [%(id)s].%(ext)s"),
    ] + _quality_args(quality)
    ff = ffmpeg_path()
    if ff:
        cmd += ["--ffmpeg-location", ff]
    if referer:
        p = urllib.parse.urlparse(referer)
        cmd += ["--referer", referer, "--add-header", f"Origin:{p.scheme}://{p.netloc}"]
    if browser:
        cmd += ["--cookies-from-browser", browser]
    cookies = data_dir() / "cookies.txt"
    if not browser and cookies.exists():
        cmd += ["--cookies", str(cookies)]
    cmd.append(url)

    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            encoding="utf-8", errors="replace", creationflags=NO_WINDOW, env=env)
    lines: List[str] = []
    try:
        for line in proc.stdout:
            if cancel.is_set():
                proc.kill()
                raise Cancelled()
            line = line.rstrip()
            m = PROGRESS_RE.search(line)
            if m:
                progress("Descargando", float(m.group(1)))
                continue
            lines.append(line)
            if "[Merger]" in line or "[ExtractAudio]" in line or "[VideoConvertor]" in line:
                progress("Uniendo audio y video" if "Merger" in line else "Convirtiendo", -1)
        proc.wait()
    finally:
        if proc.poll() is None:
            proc.kill()
    if log is not None:
        log.extend(lines[-40:])
    if proc.returncode != 0:
        errors = [l for l in lines if l.startswith("ERROR")]
        raise YtDlpError((errors[-1] if errors else "\n".join(lines[-5:])) or f"yt-dlp terminó con código {proc.returncode}")


def to_mp3(files: List[Path]) -> List[Path]:
    """Para "Solo audio": si un método bajó un video, se extrae el audio en MP3 con ffmpeg."""
    ff = ffmpeg_path()
    out = []
    for f in files:
        if f.suffix.lower() in (".mp3", ".m4a", ".opus", ".jpg", ".jpeg", ".png", ".webp"):
            out.append(f)
            continue
        if not ff:
            out.append(f)
            continue
        mp3 = f.with_suffix(".mp3")
        r = _run([ff, "-y", "-loglevel", "error", "-i", str(f), "-vn", "-q:a", "0", str(mp3)], timeout=600)
        if r.returncode == 0 and mp3.exists():
            f.unlink(missing_ok=True)
            out.append(mp3)
        else:
            out.append(f)
    return out


# ---------------------------------------------------------------- TikTok

TIKWM = "https://www.tikwm.com"


def tiktok_from_ytdlp_error(msg: str) -> Optional[str]:
    m = re.search(r"\[TikTok\] (\d{8,})", msg or "")
    return f"https://www.tiktok.com/@i/video/{m.group(1)}" if m else None


def _save_many(items, out_dir: Path, base: str, progress: Progress, cancel, headers=None) -> List[Path]:
    files = []
    n = len(items)
    for i, (u, ext) in enumerate(items):
        suffix = f" {i + 1}" if n > 1 else ""
        f = out_dir / f"{base}{suffix}.{ext}"

        def p(text, pct, i=i):
            progress(text, (i * 100 + max(pct, 0)) / n if pct >= 0 else -1)

        http_save(u, f, p, cancel, headers)
        files.append(f)
    return files


def _from_tikwm(body: str, out_dir, progress, cancel) -> List[Path]:
    res = json.loads(body)
    if res.get("code", -1) != 0:
        raise IOError(res.get("msg") or "respuesta inválida")
    data = res["data"]
    vid = data.get("id") or "tiktok"
    title = safe_name(data.get("title", ""), "TikTok")
    absu = lambda u: u if u.startswith("http") else TIKWM + u
    images = data.get("images") or []
    if images:
        return _save_many([(absu(u), "jpg") for u in images], out_dir, f"{title} [{vid}]", progress, cancel)
    video = data.get("hdplay") or data.get("play")
    if not video:
        raise IOError("TikTok: no se encontró el video")
    return _save_many([(absu(video), "mp4")], out_dir, f"{title} [{vid}]", progress, cancel)


def _from_tiklydown(url, out_dir, progress, cancel) -> List[Path]:
    j = json.loads(http_get("https://api.tiklydown.eu.org/api/download?url=" + urllib.parse.quote(url, safe="")))
    vid = j.get("id") or "tiktok"
    title = safe_name(j.get("title", ""), "TikTok")
    images = j.get("images") or []
    if images:
        items = [((it.get("url") if isinstance(it, dict) else str(it)), "jpg") for it in images]
        return _save_many(items, out_dir, f"{title} [{vid}]", progress, cancel)
    video = j.get("video") or {}
    u = video.get("noWatermark") or video.get("watermark")
    if not u:
        raise IOError(j.get("message") or "sin video")
    return _save_many([(u, "mp4")], out_dir, f"{title} [{vid}]", progress, cancel)


def tiktok_download(url, out_dir: Path, progress, cancel) -> List[Path]:
    enc = urllib.parse.quote(url, safe="")
    sources = [
        ("tikwm", lambda: _from_tikwm(http_get(f"{TIKWM}/api/?hd=1&url={enc}"), out_dir, progress, cancel)),
        ("tikwm2", lambda: _from_tikwm(http_post_form(f"{TIKWM}/api/", f"url={enc}&hd=1"), out_dir, progress, cancel)),
        ("tiklydown", lambda: _from_tiklydown(url, out_dir, progress, cancel)),
    ]
    errors = []
    for name, fn in sources:
        try:
            return fn()
        except Cancelled:
            raise
        except Exception as e:
            errors.append(f"{name}: {str(e)[:80]}")
            _clear(out_dir)
    raise IOError(" | ".join(errors))


# ---------------------------------------------------------------- X / Twitter

X_HOSTS = {"x.com", "twitter.com", "mobile.twitter.com", "mobile.x.com", "www.x.com", "www.twitter.com",
           "fixupx.com", "fxtwitter.com", "vxtwitter.com"}
X_STATUS = re.compile(r"/([^/]+)/status(?:es)?/(\d+)")


def is_x(url: str) -> bool:
    return host_of(url) in X_HOSTS and bool(X_STATUS.search(urllib.parse.urlparse(url).path))


def x_download(url, out_dir: Path, progress, cancel) -> List[Path]:
    m = X_STATUS.search(urllib.parse.urlparse(url).path)
    if not m:
        raise IOError("X: enlace sin publicación")
    user, tid = m.group(1), m.group(2)
    try:
        j = json.loads(http_get(f"https://api.vxtwitter.com/{user}/status/{tid}"))
        media = [(x["url"], x.get("type", "video")) for x in j.get("media_extended") or []]
    except Exception:
        j = json.loads(http_get(f"https://api.fxtwitter.com/{user}/status/{tid}"))
        media = [(x["url"], x.get("type", "video")) for x in ((j.get("tweet") or {}).get("media") or {}).get("all") or []]
    if not media:
        raise IOError("X: la publicación no tiene videos ni fotos")
    return _save_many([(u, guess_ext(u, t)) for u, t in media], out_dir, f"X {user} [{tid}]", progress, cancel)


# ---------------------------------------------------------------- Ok.ru

OK_ID = re.compile(r"/(?:video|videoembed|live)/(\d+)")
OK_CDN = re.compile(r"https://[a-z0-9.-]+\.(?:okcdn\.ru|mycdn\.me)/[^\"'\s<>\\]+")


class HlsOnly(Exception):
    def __init__(self, url):
        super().__init__("solo HLS")
        self.url = url


def is_okru(url: str) -> bool:
    h = _bare(host_of(url))
    h = h[2:] if h.startswith("m.") else h
    return h in ("ok.ru", "odnoklassniki.ru") and bool(OK_ID.search(url))


def _ok_clean(html: str) -> str:
    return (html.replace("&quot;", '"').replace("&amp;", "&")
            .replace("\\\\u0026", "&").replace("\\u0026", "&")
            .replace("\\\\/", "/").replace("\\/", "/")
            .replace('\\"', '"').replace("\\\\", "\\"))


def _ok_quality(u: str) -> int:
    m = re.search(r"[?&]type=(\d+)", u)
    return int(m.group(1)) if m else -1


def okru_download(url, out_dir: Path, progress, cancel) -> List[Path]:
    m = OK_ID.search(url)
    if not m:
        raise IOError("Ok.ru: enlace sin id de video")
    vid = m.group(1)
    pages = [(f"https://ok.ru/videoembed/{vid}", UA_DESKTOP),
             (f"https://m.ok.ru/video/{vid}", UA_MOBILE),
             (f"https://ok.ru/video/{vid}", UA_DESKTOP)]
    errors, hls = [], None
    hls_re = re.compile(r'"(?:hlsManifestUrl|ondemandHls|hlsMasterPlaylistUrl)"\s*:\s*"([^"]+)"')
    for page, ua in pages:
        try:
            html = _ok_clean(http_get(page, {"User-Agent": ua}))
            urls = list(dict.fromkeys(u.rstrip(",;") for u in OK_CDN.findall(html)))
            if not hls:
                hls = next((u for u in urls if ".m3u8" in u), None)
                if not hls:
                    mm = hls_re.search(html)
                    hls = mm.group(1) if mm else None
            direct = [u for u in urls if ".m3u8" not in u and ".mpd" not in u
                      and ("type=" in u or re.match(r"^https://vd[0-9a-z-]*\.", u))]
            direct += re.findall(r"https://m\.ok\.ru/dk\?st\.cmd=moviePlaybackRedirect[^\"'\s<>]+", html)
            if not direct:
                meta = re.search(r'"metadataUrl"\s*:\s*"([^"]+)"', html)
                if meta:
                    mu = meta.group(1)
                    mhtml = _ok_clean(http_post_form(mu if mu.startswith("http") else "https://ok.ru" + mu, ""))
                    direct += [u for u in OK_CDN.findall(mhtml) if "type=" in u or "vd" in u]
                    if not hls:
                        mm = hls_re.search(mhtml)
                        hls = mm.group(1) if mm else None
            if direct:
                best = max(direct, key=_ok_quality)
                t = re.search(r"<title>([^<]*)</title>", html)
                f = out_dir / f"{safe_name(t.group(1) if t else '', 'Ok.ru')} [{vid}].mp4"
                http_save(best, f, progress, cancel, {"Referer": "https://ok.ru/", "User-Agent": ua})
                return [f]
            errors.append(f"{host_of(page)}: sin video ({len(urls)} enlaces)")
        except Cancelled:
            raise
        except Exception as e:
            errors.append(f"{host_of(page)}: {str(e)[:80]}")
    if hls:
        raise HlsOnly(hls)
    raise IOError("Ok.ru: " + " | ".join(errors))


# ---------------------------------------------------------------- cobalt

COBALT_LISTS = ["https://instances.cobalt.best/api/instances.json",
                "https://cobalt.directory/api/working?type=api"]


def _cobalt_parse(text: str) -> List[str]:
    data = json.loads(text)
    if isinstance(data, dict):
        data = data.get("instances") or data.get("data") or data.get("results") or []
    out = []
    items = [o for o in data if isinstance(o, dict)]

    def online(o):
        v = o.get("online", True)
        return v.get("api", True) if isinstance(v, dict) else bool(v)

    items = [o for o in items if online(o) and not (o.get("turnstile") or o.get("auth") or o.get("apiKey"))]
    items.sort(key=lambda o: -float(o.get("score") or 0))
    for o in items:
        api = next((o.get(k) for k in ("api", "api_url", "url", "host") if o.get(k)), None)
        if not api or not isinstance(api, str):
            continue
        api = api.rstrip("/") + "/" if api.startswith("http") else f"{o.get('protocol') or 'https'}://{api.rstrip('/')}/"
        if api not in out:
            out.append(api)
    return out


def cobalt_instances() -> List[str]:
    cache = data_dir() / "cobalt-instancias.txt"
    if cache.exists() and time.time() - cache.stat().st_mtime < 24 * 3600:
        lst = [l for l in cache.read_text("utf-8").splitlines() if l.strip()]
        if lst:
            return lst
    errors = []
    for src in COBALT_LISTS:
        try:
            lst = _cobalt_parse(http_get(src, {"User-Agent": UA_DESKTOP, "Accept": "application/json"}))
            if lst:
                cache.write_text("\n".join(lst), "utf-8")
                return lst
            errors.append(f"{host_of(src)}: vacía")
        except Exception as e:
            errors.append(f"{host_of(src)}: {str(e)[:100]}")
    raise IOError("lista de servidores: " + " | ".join(errors))


def cobalt_download(url, quality, out_dir: Path, progress, cancel) -> List[Path]:
    audio = quality == "audio"
    vq = {"max": "max", "1080": "1080", "720": "720", "480": "480"}.get(quality, "1080")
    body = {"url": url, "videoQuality": vq, "youtubeVideoCodec": "h264", "filenameStyle": "basic",
            "downloadMode": "audio" if audio else "auto", "audioFormat": "mp3"}
    last = None
    for api in cobalt_instances()[:6]:
        try:
            res = json.loads(http_post_json(api, body))
            st = res.get("status")
            if st in ("tunnel", "redirect"):
                name = res.get("filename") or ("audio.mp3" if audio else "video.mp4")
                stem, _, ext = name.rpartition(".")
                f = out_dir / f"{safe_name(stem or name, 'video')}.{ext or ('mp3' if audio else 'mp4')}"
                http_save(res["url"], f, progress, cancel)
                return [f]
            if st == "picker":
                items = [(p["url"], guess_ext(p["url"], p.get("type", ""))) for p in res.get("picker", [])]
                return _save_many(items, out_dir, f"media {int(time.time())}", progress, cancel)
            if st == "error":
                raise IOError((res.get("error") or {}).get("code") or "error")
            raise IOError("respuesta desconocida")
        except Cancelled:
            raise
        except Exception as e:
            last = e
            _clear(out_dir)
    raise IOError(f"Servidores de respaldo: {last}")


# ---------------------------------------------------------------- orquestación

def _clear(d: Path):
    for f in d.iterdir():
        try:
            if f.is_file():
                f.unlink()
            else:
                shutil.rmtree(f, ignore_errors=True)
        except Exception:
            pass


def _finished_files(d: Path) -> List[Path]:
    skip = (".part", ".ytdl", ".temp", ".tmp")
    return sorted(f for f in d.iterdir() if f.is_file() and not f.name.endswith(skip) and f.stat().st_size > 0)


def _move_out(files: List[Path], dest: Path) -> List[Path]:
    out = []
    for f in files:
        target = dest / f.name
        n = 2
        while target.exists():
            target = dest / f"{f.stem} ({n}){f.suffix}"
            n += 1
        shutil.move(str(f), str(target))
        out.append(target)
    return out


def friendly_error(msg: str) -> str:
    line = msg.strip().splitlines()[-1] if msg.strip() else msg
    line = line.replace("ERROR:", "").strip()
    low = line.lower()
    hint = None
    if any(w in low for w in ("login", "cookies", "sign in", "private", "authentication")):
        hint = ("La red pidió iniciar sesión. Abre Opciones y elige el navegador donde tienes la cuenta abierta "
                "(Firefox funciona mejor).")
    elif "unsupported url" in low:
        hint = "Ese enlace no tiene un video que se pueda descargar."
    elif "getaddrinfo" in low or "name resolution" in low or "urlopen error" in low:
        hint = "Sin conexión a internet."
    detail = line[:400] or "Error desconocido"
    return f"{hint}\n\nDetalle: {detail}" if hint else detail


def download(text: str, quality: str, progress: Progress, cancel: threading.Event,
             browser: Optional[str] = None, dest: Optional[Path] = None) -> List[Path]:
    """Descarga el primer enlace del texto probando los métodos en orden. Devuelve los archivos guardados."""
    shared = extract_url(text)
    if not shared:
        raise IOError("No encontré ningún enlace. Copia el enlace del video y vuelve a intentar.")
    dest = dest or downloads_dir()
    report = [f"Enlace: {shared}", f"Calidad: {quality}"]
    progress("Abriendo enlace", -1)
    try:
        url = resolve(shared)
    except Exception:
        url = shared
    if url != shared:
        report.append(f"Enlace real: {url}")

    tiktok_hint: List[Optional[str]] = [None]
    ytlog: List[str] = []
    tmp = Path(tempfile.mkdtemp(prefix=".parcial-", dir=dest))

    def ytdlp_step():
        try:
            run_ytdlp(url, quality, tmp, progress, cancel, log=ytlog)
        except YtDlpError as e:
            tiktok_hint[0] = tiktok_from_ytdlp_error(str(e))
            raise

    def tiktok_by_id():
        if not tiktok_hint[0]:
            raise IOError("no aplica")
        tiktok_download(tiktok_hint[0], tmp, progress, cancel)

    def okru_step():
        try:
            okru_download(url, tmp, progress, cancel)
        except HlsOnly as h:  # solo hay HLS: yt-dlp lo baja y une con ffmpeg
            run_ytdlp(h.url, quality, tmp, progress, cancel, referer="https://ok.ru/", log=ytlog)

    steps = []
    tiktok_url = url if is_tiktok(url) else (shared if is_tiktok(shared) else None)
    if tiktok_url:
        steps.append(("TikTok", lambda: tiktok_download(tiktok_url, tmp, progress, cancel)))
    if is_x(url):
        steps.append(("X", lambda: x_download(url, tmp, progress, cancel)))
    if is_okru(url):
        steps.append(("Ok.ru", okru_step))
    steps.append(("yt-dlp", ytdlp_step))
    if not tiktok_url:
        steps.append(("TikTok por id", tiktok_by_id))
    steps.append(("respaldo", lambda: cobalt_download(url, quality, tmp, progress, cancel)))
    if browser:
        steps.append((f"yt-dlp con sesión de {browser}",
                      lambda: run_ytdlp(url, quality, tmp, progress, cancel, browser=browser, log=ytlog)))

    errors = []
    try:
        for name, fn in steps:
            if cancel.is_set():
                raise Cancelled()
            try:
                progress(f"Probando {name}", -1)
                fn()
                files = _finished_files(tmp)
                if not files:
                    raise IOError("no se bajó ningún archivo")
                if quality == "audio":
                    progress("Convirtiendo a MP3", -1)
                    files = to_mp3(files)
                saved = _move_out(files, dest)
                report.append(f"OK con {name}: " + ", ".join(f.name for f in saved))
                _write_report(report)
                return saved
            except Cancelled:
                raise
            except Exception as e:
                if str(e) != "no aplica":
                    errors.append((name, str(e)))
                    report.append(f"{name}: {str(e)[:500]}")
                _clear(tmp)
        main = next((m for n, m in errors if n == "yt-dlp"), errors[0][1] if errors else "No se encontró ningún archivo")
        report.append("--- yt-dlp ---")
        report.extend(ytlog[-30:])
        _write_report(report)
        raise IOError(friendly_error(main))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _write_report(lines: List[str]):
    try:
        header = [f"{APP_NAME} para PC", time.strftime("%Y-%m-%d %H:%M:%S"), f"yt-dlp {ytdlp_version()}", ""]
        (data_dir() / "ultimo-informe.txt").write_text("\n".join(header + lines), "utf-8")
    except Exception:
        traceback.print_exc()


def last_report() -> str:
    try:
        return (data_dir() / "ultimo-informe.txt").read_text("utf-8")
    except Exception:
        return "Todavía no hay informe."
