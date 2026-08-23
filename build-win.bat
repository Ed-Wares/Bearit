@echo off
@REM This script builds the Windows package for the Bearit application using jpackage.

@REM Wix 3.14.1 can be installed from admin console: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314.exe" -OutFile "wix314.exe" ; start -Wait -FilePath "wix314.exe" -ArgumentList "/quiet"
@REM Or download the zip: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile "wix314-binaries.zip" ; expand-archive -Path "wix314-binaries.zip" -DestinationPath "wix314-binaries" -Force
@REM set PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin
@REM candle.exe -?

set "current_dir=%~dp0"
set "wix_dir=%current_dir%wix314-binaries"
set "path=%path%;%wix_dir%"

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
