#define MyAppName "题眼"
#define MyAppVersion "1.0-20260514"
#define MyAppPublisher "孙启然、杨一旭"
#define MyAppExeName "Open-QSY.exe"

[Setup]
AppId={{F3E8B2A1-4C5D-4E6F-8A9B-0C1D2E3F4A5B}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\题眼
LicenseFile=license.txt
DisableProgramGroupPage=yes
OutputDir=Output
OutputBaseFilename=题眼-Setup-{#MyAppVersion}
SetupIconFile=resources\icon.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest

[Languages]
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "dist\Open-QSY\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\Open-QSY\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
