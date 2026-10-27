; Instalador do PelotaVision Windows (Inno Setup 6), gerado no CI a partir da mesma pasta do pacote portátil.
; Instala só para o usuário atual (sem administrador): %LOCALAPPDATA%\Programs\PelotaVision.
; Uso: iscc /DAppVersion=0.5.3 /DSourceDir=..\..\pkg\dist\PelotaVision /DOutputDir=..\..\pkg\installer PelotaVision.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\pkg\dist\PelotaVision"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\pkg\installer"
#endif

[Setup]
; AppId fixo: atualizações reinstalam sobre a versão anterior e o desinstalador é único.
AppId={{BF452E92-7C64-459E-8DD5-34A65C0DA74D}
AppName=PelotaVision
AppVersion={#AppVersion}
AppVerName=PelotaVision {#AppVersion}
AppPublisher=PelotaVision
DefaultDirName={localappdata}\Programs\PelotaVision
DefaultGroupName=PelotaVision
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=PelotaVision_Windows_{#AppVersion}_instalador
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\PelotaVision.exe
UninstallDisplayName=PelotaVision {#AppVersion}
CloseApplications=yes
SetupLogging=yes

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na área de trabalho"; GroupDescription: "Atalhos:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\PelotaVision"; Filename: "{app}\PelotaVision.exe"
Name: "{autoprograms}\PelotaVision - Leia-me"; Filename: "{app}\LEIA-ME.txt"
Name: "{autodesktop}\PelotaVision"; Filename: "{app}\PelotaVision.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\PelotaVision.exe"; Description: "Abrir o PelotaVision"; Flags: nowait postinstall skipifsilent
