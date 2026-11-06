@echo off
setlocal
@REM This script builds the Windows package for Bearit using jpackage, generates (or reuses)
@REM a self-signed code-signing certificate, installs the certificate into the Windows
@REM Trusted Root Certification Authorities and Trusted Publishers stores, and signs both
@REM the inner bearit.exe application launcher and the outer bearit-<version>.exe installer.

@REM Wix 3.14.1 can be installed from admin console: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314.exe" -OutFile "wix314.exe" ; start -Wait -FilePath "wix314.exe" -ArgumentList "/quiet"
@REM Or download the zip: powershell iwr -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile "wix314-binaries.zip" ; expand-archive -Path "wix314-binaries.zip" -DestinationPath "wix314-binaries" -Force

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

echo [1/6] Building Maven project...
call mvn clean package
if errorlevel 1 (
    echo ERROR: Maven build failed.
    exit /b 1
)

set "jar_file="
for /f "delims=" %%i in ('dir /b /a-d /o-d target\bearit*.jar') do (
    if not defined jar_file set "jar_file=%%i"
)
if not defined jar_file (
    echo ERROR: Could not find target\bearit*.jar
    exit /b 1
)

set "inputs_app_version=%jar_file:*-=%"
set "inputs_app_version=%inputs_app_version:.jar=%"

echo Building signed Windows package for %jar_file% (version %inputs_app_version%)...

set "win_dist=%current_dir%distribution_payload\windows"
set "app_image_dir=%win_dist%\app-image"
set "exe_installer=%win_dist%\bearit-%inputs_app_version%.exe"
set "cer_file=%win_dist%\bearit-codesign.cer"
set "cert_email=EdWaresApp@gmail.com"
set "cert_subject=CN=EdWares Bearit Code Signing, O=EdWares, E=%cert_email%"

if not exist "%win_dist%" mkdir "%win_dist%"
if exist "%app_image_dir%" rmdir /s /q "%app_image_dir%"
if exist "%exe_installer%" del /f /q "%exe_installer%"

echo.
echo [2/6] Preparing self-signed code-signing certificate and Windows trust store...
"%ps_exe%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "Import-Module Microsoft.PowerShell.Security, PKI -ErrorAction Stop; " ^
    "$subject = '%cert_subject%'; " ^
    "$cerPath = '%cer_file%'; " ^
    "$cert = Get-ChildItem -Path Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $subject -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) } | Sort-Object NotAfter -Descending | Select-Object -First 1; " ^
    "if (-not $cert) { " ^
    "    Write-Host 'Generating new self-signed code-signing certificate: ' $subject; " ^
    "    $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject $subject -TextExtension @('2.5.29.17={text}email=%cert_email%') -FriendlyName 'Bearit Self-Signed Code Signing' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddYears(10); " ^
    "} else { " ^
    "    Write-Host 'Reusing existing self-signed certificate (Thumbprint: ' $cert.Thumbprint ')'; " ^
    "} " ^
    "Export-Certificate -Cert $cert -FilePath $cerPath -Force | Out-Null; " ^
    "Write-Host 'Exported public certificate to: ' $cerPath; " ^
    "foreach ($storeName in @('TrustedPublisher', 'Root')) { " ^
    "    $storePath = 'Cert:\CurrentUser\' + $storeName; " ^
    "    $exists = Get-ChildItem $storePath | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }; " ^
    "    if (-not $exists) { " ^
    "        Write-Host ('Adding certificate to Current User store: ' + $storePath + ' ...'); " ^
    "        Import-Certificate -FilePath $cerPath -CertStoreLocation $storePath | Out-Null; " ^
    "    } else { " ^
    "        Write-Host ('Certificate already trusted in ' + $storePath); " ^
    "    } " ^
    "}"
if errorlevel 1 (
    echo ERROR: Failed to create or trust the self-signed certificate.
    exit /b 1
)

echo.
echo [3/6] Building application image with jpackage...
call jpackage --type app-image --input target/ --main-jar bearit-%inputs_app_version%.jar --main-class com.edwares.BearitApp ^
--name bearit --app-version %inputs_app_version% --dest "%app_image_dir%" --icon src/main/resources/Bearit.ico ^
--vendor "EdWares"
if errorlevel 1 (
    echo ERROR: jpackage app-image creation failed.
    exit /b 1
)

echo.
echo [4/6] Signing inner application binary (bearit.exe)...
attrib -R "%app_image_dir%\bearit\bearit.exe"
"%ps_exe%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "Import-Module Microsoft.PowerShell.Security, PKI -ErrorAction Stop; " ^
    "$subject = '%cert_subject%'; " ^
    "$appExe = '%app_image_dir%\bearit\bearit.exe'; " ^
    "Set-ItemProperty -LiteralPath $appExe -Name IsReadOnly -Value $false; " ^
    "$cert = Get-ChildItem -Path Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $subject -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) } | Sort-Object NotAfter -Descending | Select-Object -First 1; " ^
    "if (-not $cert) { throw 'Code-signing certificate not found in Cert:\CurrentUser\My' }; " ^
    "$sig = $null; " ^
    "for ($attempt = 1; $attempt -le 5; $attempt++) { " ^
    "    try { " ^
    "        Set-ItemProperty -LiteralPath $appExe -Name IsReadOnly -Value $false; " ^
    "        $sig = Set-AuthenticodeSignature -FilePath $appExe -Certificate $cert -HashAlgorithm SHA256; " ^
    "        break; " ^
    "    } catch { " ^
    "        if ($attempt -eq 5) { throw }; " ^
    "        Start-Sleep -Milliseconds 500; " ^
    "    } " ^
    "} " ^
    "Write-Host 'Signed inner binary: ' $appExe ' -> Status:' $sig.Status; " ^
    "if ($sig.Status -ne 'Valid' -and $sig.Status -ne 'UnknownError') { " ^
    "    if ($sig.Status -eq 'NotSigned' -or $sig.Status -eq 'HashMismatch') { throw ('Failed to sign ' + $appExe + ': ' + $sig.StatusMessage) } " ^
    "}"
if errorlevel 1 (
    echo ERROR: Failed to sign inner bearit.exe binary.
    exit /b 1
)

echo.
echo [5/6] Packaging Windows EXE installer with jpackage...
call jpackage --type exe --app-image "%app_image_dir%\bearit" ^
--name bearit --app-version %inputs_app_version% --dest "%win_dist%" --icon src/main/resources/Bearit.ico ^
--vendor "EdWares" --win-menu-group "EdWares" --win-per-user-install --win-dir-chooser --win-menu --win-shortcut
if errorlevel 1 (
    echo ERROR: jpackage exe installer creation failed.
    if exist "%app_image_dir%" rmdir /s /q "%app_image_dir%"
    exit /b 1
)

if exist "%app_image_dir%" rmdir /s /q "%app_image_dir%"

echo.
echo [6/6] Signing Windows EXE installer (%exe_installer%)...
attrib -R "%exe_installer%"
"%ps_exe%" -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "Import-Module Microsoft.PowerShell.Security, PKI -ErrorAction Stop; " ^
    "$subject = '%cert_subject%'; " ^
    "$installerExe = '%exe_installer%'; " ^
    "Set-ItemProperty -LiteralPath $installerExe -Name IsReadOnly -Value $false; " ^
    "$cert = Get-ChildItem -Path Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $subject -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) } | Sort-Object NotAfter -Descending | Select-Object -First 1; " ^
    "if (-not $cert) { throw 'Code-signing certificate not found in Cert:\CurrentUser\My' }; " ^
    "$sig = $null; " ^
    "for ($attempt = 1; $attempt -le 5; $attempt++) { " ^
    "    try { " ^
    "        Set-ItemProperty -LiteralPath $installerExe -Name IsReadOnly -Value $false; " ^
    "        $sig = Set-AuthenticodeSignature -FilePath $installerExe -Certificate $cert -HashAlgorithm SHA256; " ^
    "        break; " ^
    "    } catch { " ^
    "        if ($attempt -eq 5) { throw }; " ^
    "        Start-Sleep -Milliseconds 500; " ^
    "    } " ^
    "} " ^
    "Unblock-File -Path $installerExe -ErrorAction SilentlyContinue; " ^
    "$verify = Get-AuthenticodeSignature -FilePath $installerExe; " ^
    "Write-Host 'Signed installer:    ' $installerExe; " ^
    "Write-Host 'Signature Status:    ' $verify.Status ' - ' $verify.StatusMessage; " ^
    "Write-Host 'Signer Subject:      ' $verify.SignerCertificate.Subject; " ^
    "Write-Host 'Signer Thumbprint:   ' $verify.SignerCertificate.Thumbprint; " ^
    "if ($verify.Status -ne 'Valid') { " ^
    "    Write-Warning ('Signature status is ' + $verify.Status + '. Ensure ' + '%cer_file%' + ' is imported into Trusted Root Certification Authorities and Trusted Publishers.'); " ^
    "}"
if errorlevel 1 (
    echo ERROR: Failed to sign Windows installer EXE.
    exit /b 1
)

echo.
echo Signed Windows package built at: %exe_installer%
echo Public certificate exported to:  %cer_file%
endlocal
