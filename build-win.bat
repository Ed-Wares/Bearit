@echo off
@REM This script builds the Windows package for the Bearit application using jpackage.

@REM Wix 3.14.1 can be installed from admin console: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314.exe" -OutFile "wix314.exe" ; start -Wait -FilePath "wix314.exe" -ArgumentList "/quiet"
@REM set PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin
@REM candle.exe -?


mvn clean package

set jar_file=
for /f "delims=" %%i in ('dir /b /a-d /o-d target\bearit*.jar') do set jar_file=%%i
set "inputs_app_version=%jar_file:*-=%"
set "inputs_app_version=%inputs_app_version:.jar=%"

echo building windows package for %jar_file% ...
echo app version is %inputs_app_version%

mkdir distribution_payload\windows

jpackage --type exe --input target/ --main-jar bearit-%inputs_app_version%.jar --main-class com.edwares.BearitApp ^
--name bearit --app-version %inputs_app_version% --dest distribution_payload/windows/ --icon src/main/resources/Bearit.ico ^
--vendor "EdWares" --win-menu-group "EdWares" --win-per-user-install --win-dir-chooser --win-menu --win-shortcut

echo "windows package built at distribution_payload/windows/bearit-%inputs_app_version%.exe"
