# Descargar Video

App Android personal que replica el atajo de iOS "Snap Video": comparte un video desde TikTok, Instagram, Facebook, X o YouTube, elige **Descargar video** y se guarda en la galería.

- Motor: [yt-dlp](https://github.com/yt-dlp/yt-dlp) incluido en la app (vía youtubedl-android), se actualiza solo una vez al día.
- Videos en `Movies/Descargar Video`, fotos en `Pictures/Descargar Video`, audio en `Music/Descargar Video`.
- Si una red pide cuenta, abre la app y usa "Iniciar sesión".

## Instalar
Cada push a `main` compila el APK en GitHub Actions y lo publica en **Releases** (`DescargarVideo.apk`). Ábrelo desde el teléfono y permite "instalar apps desconocidas".

## Para PC (Windows)
La carpeta `pc/` tiene la versión de escritorio (Python + yt-dlp + ffmpeg, mismos métodos de respaldo que en Android).
GitHub Actions la compila y la publica en **Releases** como `pc-v1.0.N`:
- `DescargarVideo-Instalador.exe` (recomendado) o `DescargarVideo-Portable.zip`.
- Copia el enlace de un video con la app abierta y se descarga solo en `Descargas\Descargar Video`.
