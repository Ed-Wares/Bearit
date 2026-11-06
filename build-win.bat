@echo off
setlocal
@REM This script builds the Windows package for the Bearit application using jpackage.

@REM Wix 3.14.1 can be installed from admin console: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314.exe" -OutFile "wix314.exe" ; start -Wait -FilePath "wix314.exe" -ArgumentList "/quiet"
@REM Or download the zip: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile "wix314-binaries.zip" ; expand-archive -Path "wix314-binaries.zip" -DestinationPath "wix314-binaries" -Force
@REM set PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin
@REM candle.exe -?

set "current_dir=%~dp0"
set "wix_dir=%current_dir%wix314-binaries"
set "path=%path%;%wix_dir%"
set "PSModulePath="
set "ps_exe=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%ps_exe%" set "ps_exe=powershell"

if exist "%wix_dir%\candle.exe" if exist "%wix_dir%\light.exe" goto :wix_ready

echo Required WiX Toolset binaries (candle.exe, light.exe) were not found in "%wix_dir%".
set "download_wix="
set /p "download_wix=Would you like to download WiX 3.14.1 binaries to %wix_dir% now? (Y/N): "
if /i "%download_wix%"=="Y" goto :download_wix
if /i "%download_wix%"=="YES" goto :download_wix

where candle.exe >nul 2>&1
if not errorlevel 1 (
    where light.exe >nul 2>&1
    if not errorlevel 1 (
        echo Continuing using WiX binaries found in system PATH...
        goto :wix_ready
    )
)
echo Aborting build: WiX Toolset binaries (candle.exe, light.exe) are required by jpackage.
exit /b 1

:download_wix
echo Downloading and extracting WiX 3.14.1 binaries...
set "wix_zip=%current_dir%wix314-binaries.zip"
"%ps_exe%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$ProgressPreference = 'SilentlyContinue'; " ^
    "Import-Module Microsoft.PowerShell.Archive -ErrorAction Stop; " ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; " ^
    "$zipPath = '%wix_zip%'; " ^
    "$destDir = '%wix_dir%'; " ^
    "try { " ^
    "    Write-Host 'Downloading https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip ...'; " ^
    "    Invoke-WebRequest -Uri 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' -OutFile $zipPath -UseBasicParsing; " ^
    "    Write-Host ('Extracting to ' + $destDir + ' ...'); " ^
    "    Expand-Archive -LiteralPath $zipPath -DestinationPath $destDir -Force; " ^
    "} finally { " ^
    "    Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue; " ^
    "}"
if errorlevel 1 (
    echo ERROR: Failed to download or extract WiX 3.14.1 binaries.
    exit /b 1
)
if not exist "%wix_dir%\candle.exe" (
    echo ERROR: WiX binaries were extracted, but candle.exe was not found in "%wix_dir%".
    exit /b 1
)
if not exist "%wix_dir%\light.exe" (
    echo ERROR: WiX binaries were extracted, but light.exe was not found in "%wix_dir%".
    exit /b 1
)
echo WiX 3.14.1 binaries installed to "%wix_dir%".
echo.

:wix_ready

call mvn clean package

set jar_file=
for /f "delims=" %%i in ('dir /b /a-d /o-d target\bearit*.jar') do set jar_file=%%i
set "inputs_app_version=%jar_file:*-=%"
set "inputs_app_version=%inputs_app_version:.jar=%"

echo building windows package for %jar_file% ...
echo app version is %inputs_app_version%

mkdir distribution_payload\windows

echo "wix_dir: %wix_dir%"

call jpackage --type exe --input target/ --main-jar bearit-%inputs_app_version%.jar --main-class com.edwares.BearitApp ^
--name bearit --app-version %inputs_app_version% --dest distribution_payload/windows/ --icon src/main/resources/Bearit.ico ^
--vendor "EdWares" --win-menu-group "EdWares" --win-per-user-install --win-dir-chooser --win-menu --win-shortcut

echo "windows package built at distribution_payload/windows/bearit-%inputs_app_version%.exe"
endlocal
