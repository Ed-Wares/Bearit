@echo off
setlocal
@REM This script checks if the Bearit self-signed code-signing certificate is already
@REM installed in the Current User certificate stores (TrustedPublisher and Root),
@REM and installs it if not already present.

set "current_dir=%~dp0"
set "cer_file=%current_dir%distribution_payload\windows\bearit-codesign.cer"
if not exist "%cer_file%" (
    if exist "%current_dir%bearit-codesign.cer" (
        set "cer_file=%current_dir%bearit-codesign.cer"
    ) else (
        echo ERROR: Certificate file not found at "%cer_file%"
        exit /b 1
    )
)

echo Checking Bearit code-signing certificate in Current User stores...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$cerPath = (Resolve-Path -LiteralPath '%cer_file%').Path; " ^
    "$cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($cerPath); " ^
    "Write-Host ('Certificate: ' + $cert.Subject + ' (Thumbprint: ' + $cert.Thumbprint + ')'); " ^
    "foreach ($storeName in @('Root', 'TrustedPublisher')) { " ^
    "    $storePath = 'Cert:\CurrentUser\' + $storeName; " ^
    "    $exists = Get-ChildItem $storePath -ErrorAction SilentlyContinue | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }; " ^
    "    if ($exists) { " ^
    "        Write-Host ('Certificate is already installed in ' + $storePath); " ^
    "    } else { " ^
    "        Write-Host ('Installing certificate into ' + $storePath + ' ...'); " ^
    "        Import-Certificate -FilePath $cerPath -CertStoreLocation $storePath | Out-Null; " ^
    "        Write-Host ('Successfully installed certificate into ' + $storePath); " ^
    "    } " ^
    "}"
if errorlevel 1 (
    echo ERROR: Failed to install the code-signing certificate.
    exit /b 1
)

endlocal
