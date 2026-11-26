"""Pruebas sin internet de la lógica de enlaces y del orden de métodos."""

import json
import threading
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

import core


class Links(unittest.TestCase):
    def test_extract(self):
        self.assertEqual(core.extract_url("Mira este video https://vt.tiktok.com/ZSabc/ ."),
                         "https://vt.tiktok.com/ZSabc/")
        self.assertIsNone(core.extract_url("sin enlace"))

    def test_social(self):
        self.assertTrue(core.is_social_link("https://www.tt.site/t/ZT123/"))
        self.assertTrue(core.is_social_link("https://youtu.be/abc"))
        self.assertTrue(core.is_social_link("https://m.facebook.com/watch?v=1"))
        self.assertFalse(core.is_social_link("https://google.com/search?q=x"))
        self.assertFalse(core.is_social_link("notatiktok.com"))

    def test_normalize_tiktok(self):
        self.assertEqual(core.normalize("https://m.tiktok.com/v/7300000000000000000.html?x=1"),
                         "https://www.tiktok.com/@i/video/7300000000000000000")
        self.assertEqual(core.normalize("https://www.tiktok.com/@ana/photo/7300000000000000001?lang=es"),
                         "https://www.tiktok.com/@ana/photo/7300000000000000001")

    def test_needs_resolving(self):
        self.assertTrue(core._needs_resolving("https://www.tt.site/t/ZT123/"))
        self.assertTrue(core._needs_resolving("https://www.tiktok.com/t/ZT123/"))
        self.assertFalse(core._needs_resolving("https://www.tiktok.com/@a/video/7300000000000000000"))
        self.assertFalse(core._needs_resolving("https://www.youtube.com/watch?v=x"))

    def test_app_link(self):
        self.assertEqual(core.app_link_to_web("snssdk1233://aweme/detail/7300000000000000000?x"),
                         "https://www.tiktok.com/@i/video/7300000000000000000")

    def test_x_okru(self):
        self.assertTrue(core.is_x("https://x.com/user/status/123456"))
        self.assertFalse(core.is_x("https://x.com/user"))
        self.assertTrue(core.is_okru("https://ok.ru/video/123456"))
        self.assertTrue(core.is_okru("https://m.ok.ru/video/123456"))
        self.assertFalse(core.is_okru("https://ok.ru/group/1"))

    def test_tiktok_id_from_error(self):
        self.assertEqual(core.tiktok_from_ytdlp_error("ERROR: [TikTok] 7300000000000000000: login"),
                         "https://www.tiktok.com/@i/video/7300000000000000000")

    def test_cobalt_parse(self):
        text = json.dumps([
            {"api": "a.example", "online": {"api": True}, "score": 50},
            {"api": "b.example", "online": {"api": True}, "score": 90},
            {"api": "c.example", "online": {"api": False}, "score": 99},
            {"api": "d.example", "online": True, "turnstile": True, "score": 99},
        ])
        self.assertEqual(core._cobalt_parse(text), ["https://b.example/", "https://a.example/"])

    def test_quality_args(self):
        self.assertIn("-x", core._quality_args("audio"))
        self.assertIn("res:720,vcodec:h264,acodec:m4a", core._quality_args("720"))


class Order(unittest.TestCase):
    """yt-dlp falla -> se usa el respaldo; TikTok usa primero su API."""

    def run_download(self, url, fake):
        with TemporaryDirectory() as d, mock.patch.object(core, "resolve", lambda u: u), \
                mock.patch.object(core, "data_dir", lambda: Path(d)), \
                mock.patch.object(core, "ytdlp_version", lambda: "test"):
            calls = []
            patches = [mock.patch.object(core, n, (lambda _n, _fn: (lambda *a, **k: (calls.append(_n), _fn(*a, **k))[1]))(n, f))
                       for n, f in fake.items()]
            for p in patches:
                p.start()
            try:
                files = core.download(url, "compatible", lambda t, p: None, threading.Event(), dest=Path(d))
                return calls, [f.name for f in files]
            finally:
                for p in patches:
                    p.stop()

    @staticmethod
    def writes(name):
        def f(*a, **k):
            out = next(x for x in a if isinstance(x, Path))
            (out / name).write_bytes(b"x" * 2000)
        return f

    @staticmethod
    def fails(*a, **k):
        raise IOError("falló")

    def test_tiktok_first(self):
        calls, files = self.run_download("https://www.tiktok.com/@a/video/7300000000000000000",
                                         {"tiktok_download": self.writes("t.mp4"), "run_ytdlp": self.fails})
        self.assertEqual(calls, ["tiktok_download"])
        self.assertEqual(files, ["t.mp4"])

    def test_fallback_to_cobalt(self):
        calls, files = self.run_download("https://www.instagram.com/reel/abc/",
                                         {"run_ytdlp": self.fails, "cobalt_download": self.writes("c.mp4")})
        self.assertEqual(calls, ["run_ytdlp", "cobalt_download"])
        self.assertEqual(files, ["c.mp4"])

    def test_all_fail(self):
        with self.assertRaises(IOError):
            self.run_download("https://www.instagram.com/reel/abc/",
                              {"run_ytdlp": self.fails, "cobalt_download": self.fails})


if __name__ == "__main__":
    unittest.main()
