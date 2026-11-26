"""Ventana de "Descargar Video" para PC: pega (o copia) un enlace y se guarda en Descargas."""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

import core

VERSION = os.environ.get("DV_VERSION", "dev")
try:
    from version import VERSION  # generado al compilar
except Exception:
    pass

BROWSERS = {
    "": "No usar",
    "firefox": "Firefox",
    "edge": "Microsoft Edge",
    "chrome": "Google Chrome",
    "brave": "Brave",
    "opera": "Opera",
}


class App:
    def __init__(self, root: tk.Tk, initial: str | None):
        self.root = root
        self.cfg = core.load_config()
        self.events: "queue.Queue[tuple]" = queue.Queue()
        self.jobs: "queue.Queue[tuple]" = queue.Queue()
        self.cancel = threading.Event()
        self.pending = 0
        self.last_clip = ""
        self.last_files: list[Path | None] = []
        self.recent: dict[str, float] = {}

        root.title(f"{core.APP_NAME}")
        root.minsize(560, 430)
        root.geometry("620x480")
        self._icon()
        style = ttk.Style()
        try:
            style.theme_use("vista" if core.IS_WINDOWS else "clam")
        except tk.TclError:
            pass
        style.configure("Big.TButton", font=("Segoe UI", 12, "bold"), padding=10)
        style.configure("Title.TLabel", font=("Segoe UI", 15, "bold"))

        main = ttk.Frame(root, padding=16)
        main.pack(fill="both", expand=True)

        ttk.Label(main, text="Descargar Video", style="Title.TLabel").pack(anchor="w")
        ttk.Label(main, text="TikTok, Instagram, Facebook, X, YouTube, Ok.ru y muchas más. "
                             "Copia el enlace del video y pégalo aquí.", wraplength=580).pack(anchor="w", pady=(2, 10))

        row = ttk.Frame(main)
        row.pack(fill="x")
        self.url = tk.StringVar()
        self.entry = ttk.Entry(row, textvariable=self.url, font=("Segoe UI", 11))
        self.entry.pack(side="left", fill="x", expand=True, ipady=4)
        self.entry.bind("<Return>", lambda e: self.start())
        ttk.Button(row, text="Pegar", command=self.paste).pack(side="left", padx=(6, 0))

        row2 = ttk.Frame(main)
        row2.pack(fill="x", pady=(10, 0))
        ttk.Label(row2, text="Calidad:").pack(side="left")
        self.quality_keys = list(core.QUALITIES)
        self.quality = ttk.Combobox(row2, state="readonly", width=28,
                                    values=[core.QUALITIES[k] for k in self.quality_keys])
        q = self.cfg.get("calidad", "compatible")
        self.quality.current(self.quality_keys.index(q) if q in self.quality_keys else 0)
        self.quality.bind("<<ComboboxSelected>>", lambda e: self._save())
        self.quality.pack(side="left", padx=6)

        self.btn = ttk.Button(main, text="⬇  Descargar", style="Big.TButton", command=self.start)
        self.btn.pack(fill="x", pady=12)

        self.auto = tk.BooleanVar(value=self.cfg.get("auto", True))
        ttk.Checkbutton(main, text="Descargar solo al copiar un enlace (como el atajo de iPhone)",
                        variable=self.auto, command=self._save).pack(anchor="w")

        self.status = ttk.Label(main, text="Listo.", wraplength=580)
        self.status.pack(anchor="w", pady=(12, 4))
        self.bar = ttk.Progressbar(main, mode="determinate", maximum=100)
        self.bar.pack(fill="x")

        ttk.Label(main, text="Últimas descargas:").pack(anchor="w", pady=(12, 2))
        self.history = tk.Listbox(main, height=5, activestyle="none", font=("Segoe UI", 9))
        self.history.pack(fill="both", expand=True)
        self.history.bind("<Double-Button-1>", lambda e: self.open_selected())

        bottom = ttk.Frame(main)
        bottom.pack(fill="x", pady=(10, 0))
        ttk.Button(bottom, text="Abrir carpeta", command=self.open_folder).pack(side="left")
        self.cancel_btn = ttk.Button(bottom, text="Cancelar", command=self.cancel_job, state="disabled")
        self.cancel_btn.pack(side="left", padx=6)
        ttk.Button(bottom, text="Opciones", command=self.options).pack(side="right")
        ttk.Button(bottom, text="Ver informe", command=self.show_report).pack(side="right", padx=6)

        threading.Thread(target=self._worker, daemon=True).start()
        threading.Thread(target=self._update_engine, daemon=True).start()
        root.after(100, self._poll)

        try:
            self.last_clip = root.clipboard_get()
        except tk.TclError:
            self.last_clip = ""
        if initial:
            self.url.set(initial)
            self.start()
        elif core.is_social_link(self.last_clip):
            self.url.set(core.extract_url(self.last_clip))
        root.after(1000, self._watch_clipboard)
        self.entry.focus_set()

    # ------------------------------------------------------------ interfaz

    def _icon(self):
        ico = core.app_dir() / "icono.ico"
        if ico.exists():
            try:
                self.root.iconbitmap(default=str(ico))
            except tk.TclError:
                pass

    def _save(self):
        self.cfg = core.load_config()
        self.cfg["calidad"] = self.quality_keys[self.quality.current()]
        self.cfg["auto"] = bool(self.auto.get())
        core.save_config(self.cfg)

    def paste(self):
        try:
            text = self.root.clipboard_get()
        except tk.TclError:
            return
        self.url.set(core.extract_url(text) or text.strip())
        self.last_clip = text

    def start(self, text: str | None = None):
        text = (text or self.url.get()).strip()
        if not core.extract_url(text):
            self._set_status("Pega primero el enlace del video (Ctrl+V).", None)
            return
        quality = self.quality_keys[self.quality.current()]
        key = (core.extract_url(text) or "") + quality
        now = time.time()
        if now - self.recent.get(key, 0) < 30:  # mismo enlace pegado y copiado a la vez
            self._set_status("Ese enlace ya se está descargando.", None if self.pending == 0 else -1)
            self.url.set("")
            return
        self.recent[key] = now
        self.pending += 1
        self.jobs.put((text, quality))
        self.url.set("")
        self._set_status("En cola…" if self.pending > 1 else "Empezando…", -1)

    def cancel_job(self):
        self.cancel.set()

    def open_folder(self):
        _open(core.downloads_dir())

    def open_selected(self):
        sel = self.history.curselection()
        if sel and sel[0] < len(self.last_files) and self.last_files[sel[0]]:
            _open(self.last_files[sel[0]])

    def show_report(self):
        win = tk.Toplevel(self.root)
        win.title("Informe de la última descarga")
        win.geometry("640x420")
        txt = tk.Text(win, wrap="word", font=("Consolas", 9))
        txt.insert("1.0", core.last_report())
        txt.pack(fill="both", expand=True)
        row = ttk.Frame(win, padding=6)
        row.pack(fill="x")

        def copy():
            self.root.clipboard_clear()
            self.root.clipboard_append(txt.get("1.0", "end"))
            self.last_clip = txt.get("1.0", "end")

        ttk.Button(row, text="Copiar informe", command=copy).pack(side="left")
        ttk.Label(row, text="Pégalo en el chat si algo falló.").pack(side="left", padx=8)

    def options(self):
        win = tk.Toplevel(self.root)
        win.title("Opciones")
        win.resizable(False, False)
        f = ttk.Frame(win, padding=16)
        f.pack(fill="both")
        ttk.Label(f, text="Videos privados o que piden cuenta", font=("Segoe UI", 10, "bold")).pack(anchor="w")
        ttk.Label(f, wraplength=380, text=(
            "Si una red pide iniciar sesión, la app puede usar la sesión que ya tienes abierta en tu navegador. "
            "Solo se usa cuando lo demás falla. Firefox es el que mejor funciona; con Chrome o Edge "
            "a veces hay que cerrar el navegador antes.")).pack(anchor="w", pady=(2, 6))
        keys = list(BROWSERS)
        combo = ttk.Combobox(f, state="readonly", values=[BROWSERS[k] for k in keys])
        cur = self.cfg.get("navegador", "")
        combo.current(keys.index(cur) if cur in keys else 0)
        combo.pack(anchor="w")

        ttk.Label(f, text="Motor de descarga", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(14, 0))
        eng = ttk.Label(f, text="yt-dlp: consultando…")
        eng.pack(anchor="w")
        threading.Thread(target=lambda: self.events.put(("label", eng, "yt-dlp " + core.ytdlp_version())),
                         daemon=True).start()

        def upd():
            eng.configure(text="Actualizando…")
            threading.Thread(target=lambda: self.events.put(
                ("label", eng, (core.update_ytdlp(force=True) or "Actualizado") + f" (yt-dlp {core.ytdlp_version()})")),
                daemon=True).start()

        ttk.Button(f, text="Actualizar ahora", command=upd).pack(anchor="w", pady=4)
        ttk.Label(f, text=f"Versión de la app: {VERSION}\nCarpeta: {core.downloads_dir()}",
                  foreground="#666").pack(anchor="w", pady=(10, 0))

        def ok():
            self.cfg = core.load_config()
            self.cfg["navegador"] = keys[combo.current()]
            core.save_config(self.cfg)
            win.destroy()

        ttk.Button(f, text="Guardar", command=ok).pack(anchor="e", pady=(12, 0))

    def _set_status(self, text, pct):
        self.status.configure(text=text)
        if pct is None:
            self.bar.stop()
            self.bar.configure(mode="determinate", value=0)
        elif pct < 0:
            if str(self.bar.cget("mode")) != "indeterminate":
                self.bar.configure(mode="indeterminate")
                self.bar.start(12)
        else:
            if str(self.bar.cget("mode")) != "determinate":
                self.bar.stop()
                self.bar.configure(mode="determinate")
            self.bar.configure(value=pct)

    def _watch_clipboard(self):
        try:
            clip = self.root.clipboard_get()
        except tk.TclError:
            clip = ""
        if clip and clip != self.last_clip:
            self.last_clip = clip
            if core.is_social_link(clip):
                if self.auto.get():
                    self.start(clip)
                else:
                    self.url.set(core.extract_url(clip))
        self.root.after(1000, self._watch_clipboard)

    def _poll(self):
        try:
            while True:
                ev = self.events.get_nowait()
                kind = ev[0]
                if kind == "progress":
                    _, text, pct = ev
                    waiting = self.pending - 1
                    if waiting > 0:
                        text += f"  ({waiting} en cola)"
                    self._set_status(text + (f" {pct:.0f}%" if pct >= 0 else "…"), pct)
                elif kind == "busy":
                    self.cancel_btn.configure(state="normal" if ev[1] else "disabled")
                elif kind == "done":
                    self.pending -= 1
                    files = ev[1]
                    for f in reversed(files):
                        self.last_files.insert(0, f)
                        self.history.insert(0, "✔ " + f.name)
                    self._set_status(f"Guardado en Descargas\\{core.APP_NAME}: {files[0].name}"
                                     + (f" y {len(files) - 1} más" if len(files) > 1 else ""), 100)
                    self._flash()
                elif kind == "error":
                    self.pending -= 1
                    self.last_files.insert(0, None)
                    self.history.insert(0, "✖ " + ev[2][:90])
                    self._set_status("No se pudo descargar.", None)
                    messagebox.showerror("No se pudo descargar", ev[1] + "\n\nEn \"Ver informe\" está el detalle.",
                                         parent=self.root)
                elif kind == "cancelled":
                    self.pending -= 1
                    self._set_status("Cancelado.", None)
                elif kind == "label":
                    try:
                        ev[1].configure(text=ev[2])
                    except tk.TclError:
                        pass
        except queue.Empty:
            pass
        self.root.after(100, self._poll)

    def _flash(self):
        try:
            self.root.bell()
        except tk.TclError:
            pass

    # ------------------------------------------------------------ trabajo en segundo plano

    def _update_engine(self):
        core.update_ytdlp()

    def _worker(self):
        while True:
            text, quality = self.jobs.get()
            self.cancel.clear()
            self.events.put(("busy", True))
            prog = lambda t, p: self.events.put(("progress", t, p))
            try:
                browser = core.load_config().get("navegador") or None
                files = core.download(text, quality, prog, self.cancel, browser=browser)
                self.events.put(("done", files))
            except core.Cancelled:
                self.events.put(("cancelled",))
            except Exception as e:
                self.events.put(("error", str(e), core.extract_url(text) or text))
            finally:
                self.events.put(("busy", False))


def _open(path: Path):
    try:
        if core.IS_WINDOWS:
            os.startfile(str(path))  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", str(path)])
    except Exception:
        pass


def self_test(out: Path) -> int:
    """Comprobación usada al compilar: el motor y ffmpeg están incluidos y funcionan."""
    lines, ok = [], True
    v = core.ytdlp_version()
    lines.append(f"yt-dlp: {v}")
    ok &= v[:1].isdigit()
    ff = core.ffmpeg_path()
    lines.append(f"ffmpeg: {ff}")
    ok &= bool(ff)
    lines.append(f"descargas: {core.downloads_dir()}")
    lines.append("OK" if ok else "FALLA")
    out.write_text("\n".join(lines), "utf-8")
    return 0 if ok else 1


def main():
    # En el .exe sin consola no hay stdout/stderr; cualquier print fallaría.
    for name in ("stdout", "stderr"):
        if getattr(sys, name) is None:
            setattr(sys, name, open(os.devnull, "w", encoding="utf-8"))
    args = sys.argv[1:]
    if args and args[0] == "--selftest":
        sys.exit(self_test(Path(args[1] if len(args) > 1 else "selftest.txt")))
    if core.IS_WINDOWS:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # texto nítido en pantallas con escala
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Wilmer.DescargarVideo")
        except Exception:
            pass
    root = tk.Tk()
    App(root, " ".join(args) if args else None)
    root.mainloop()


if __name__ == "__main__":
    main()
