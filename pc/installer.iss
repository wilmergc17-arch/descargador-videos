; Instalador de "Descargar Video" para Windows (se compila en GitHub Actions con Inno Setup).
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{8F3B2C1A-5D4E-4F6A-9B7C-1E2D3F4A5B6C}
AppName=Descargar Video
AppVersion={#AppVersion}
AppVerName=Descargar Video {#AppVersion}
AppPublisher=Wilmer
; Se instala solo para el usuario (sin pedir permiso de administrador) para que yt-dlp pueda actualizarse solo.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\DescargarVideo
DisableProgramGroupPage=yes
DisableDirPage=yes
OutputDir=salida
OutputBaseFilename=DescargarVideo-Instalador
SetupIconFile=icono.ico
UninstallDisplayIcon={app}\DescargarVideo.exe
UninstallDisplayName=Descargar Video
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Languages]
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "dist\DescargarVideo\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Descargar Video"; Filename: "{app}\DescargarVideo.exe"
Name: "{autodesktop}\Descargar Video"; Filename: "{app}\DescargarVideo.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\DescargarVideo.exe"; Description: "{cm:LaunchProgram,Descargar Video}"; Flags: nowait postinstall skipifsilent
