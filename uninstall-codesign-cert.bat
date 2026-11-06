@echo off
setlocal
@REM This script removes the Bearit self-signed code-signing certificate from the
@REM Current User certificate stores (TrustedPublisher, Root, and Personal/My).

echo Removing Bearit self-signed code-signing certificates from Current User stores...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$removedCount = 0; " ^
    "foreach ($storeName in @('TrustedPublisher', 'Root', 'My')) { " ^
    "    $storePath = 'Cert:\CurrentUser\' + $storeName; " ^
    "    $certs = Get-ChildItem $storePath -ErrorAction SilentlyContinue | Where-Object { $_.Subject -like '*CN=EdWares Bearit Code Signing*' -or $_.FriendlyName -eq 'Bearit Self-Signed Code Signing' }; " ^
    "    foreach ($cert in $certs) { " ^
    "        Write-Host ('Removing certificate from ' + $storePath + ' -> Subject: ' + $cert.Subject + ' (Thumbprint: ' + $cert.Thumbprint + ')'); " ^
    "        $store = New-Object System.Security.Cryptography.X509Certificates.X509Store($storeName, 'CurrentUser'); " ^
    "        $store.Open('ReadWrite'); " ^
    "        $store.Remove($cert); " ^
    "        $store.Close(); " ^
    "        $removedCount++; " ^
    "    } " ^
    "} " ^
    "if ($removedCount -eq 0) { " ^
    "    Write-Host 'No Bearit code-signing certificates were found in Current User stores.'; " ^
    "} else { " ^
    "    Write-Host ('Successfully removed ' + $removedCount + ' certificate entry/entries.'); " ^
    "}"

endlocal

