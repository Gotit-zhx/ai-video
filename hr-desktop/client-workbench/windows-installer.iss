; Own per-user installer. The account and recruiter directories are external.
[Setup]
AppId={#AppIdentity}
AppName=Ai boss
AppVersion={#AppVersion}
AppVerName=Ai boss {#AppVersion}
AppPublisher=赫逸
DefaultDirName={localappdata}\Programs\AiBoss
DefaultGroupName=Ai boss
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir={#OutputDirectory}
OutputBaseFilename={#OutputName}
SetupIconFile={#BrandIcon}
UninstallDisplayIcon={app}\Ai boss.exe
UninstallDisplayName=Ai boss
AppMutex={#RunningMutex}
SetupMutex={#SetupMutex}
CloseApplications=no
RestartApplications=no
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
AllowNoIcons=no
UsePreviousTasks=yes

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Messages]
WelcomeLabel2=安装 Ai boss 到本机。%n%n安装前请先正常退出旧助手，无需退出 BOSS 或赫逸账号。更新和卸载都会保留登录资料与招聘记录。

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式："

[Files]
#include "payload.iss"

[Icons]
Name: "{autoprograms}\Ai boss"; Filename: "{app}\Ai boss.exe"; WorkingDir: "{app}"; AppUserModelID: "{#AppIdentity}"
Name: "{autodesktop}\Ai boss"; Filename: "{app}\Ai boss.exe"; WorkingDir: "{app}"; AppUserModelID: "{#AppIdentity}"; Tasks: desktopicon

[Code]
function Overlaps(const Left, Right: String): Boolean;
var L, R: String;
begin
  L := AddBackslash(Lowercase(ExpandFileName(Left)));
  R := AddBackslash(Lowercase(ExpandFileName(Right)));
  Result := (Pos(L, R) = 1) or (Pos(R, L) = 1);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if CheckForMutexes('{#RunningMutex}') then
    Result := '请先正常退出 Ai boss，再继续更新。无需退出 BOSS 或赫逸账号。'
  else if Overlaps(ExpandConstant('{app}'), ExpandConstant('{localappdata}\HeyiBossWorkbench')) or
          Overlaps(ExpandConstant('{app}'), ExpandConstant('{userappdata}\heyi-recruiter')) then
    Result := '请选择独立的程序目录，不能安装到登录资料或招聘记录目录。';
end;

function InitializeUninstall(): Boolean;
begin
  CreateMutex('{#SetupMutex}');
  Result := not CheckForMutexes('{#RunningMutex}');
  if not Result then
    SuppressibleMsgBox('请先正常退出 Ai boss，再卸载。登录资料和招聘记录会保留。',
      mbInformation, MB_OK, IDOK);
end;
