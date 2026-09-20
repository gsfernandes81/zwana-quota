#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Trust the build's certificate and install the widget. Run from an elevated
    PowerShell, in the folder this script was downloaded into.

.DESCRIPTION
    Everything here is built into Windows: no SDK, no Visual Studio, nothing to
    download beyond the artifact itself.

    The certificate goes into LocalMachine\TrustedPeople, which is what lets a
    self-signed package install at all. An already-installed copy is removed
    first, because a build signed by a different key than the one on disk is a
    different signer to Windows and it will refuse to upgrade in place — which
    is exactly what happens every run unless a certificate is pinned in the
    repository secrets (see windows/README.md).

    The certificate from the LAST install is removed too. Every run mints a new
    key under the same name, so importing without pruning leaves one more trust
    anchor on the machine every time, all of them called the same thing and
    none of them distinguishable by eye. Their private keys died with the build
    runner, so a stale one signs nothing — but a trust store that only grows is
    the wrong default, and nobody is going to weed it by hand later.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$msix = Get-ChildItem -Path $here -Filter *.msix | Select-Object -First 1
$cer = Get-ChildItem -Path $here -Filter *.cer | Select-Object -First 1
if (-not $msix) { throw "no .msix beside this script ($here)" }
if (-not $cer) { throw "no .cer beside this script ($here)" }

$store = 'Cert:\LocalMachine\TrustedPeople'
$incoming = Get-PfxCertificate -FilePath $cer.FullName

# Same subject, different key: the previous builds' certificates.
$stale = @(Get-ChildItem -Path $store |
    Where-Object { $_.Subject -eq $incoming.Subject -and $_.Thumbprint -ne $incoming.Thumbprint })
if ($stale) {
    Write-Host "Removing $($stale.Count) certificate(s) from earlier builds"
    foreach ($old in $stale) {
        Remove-Item -Path $old.PSPath -Force
    }
}

Write-Host "Trusting $($cer.Name) ($($incoming.Thumbprint))"
Import-Certificate -FilePath $cer.FullName -CertStoreLocation $store | Out-Null

$installed = Get-AppxPackage -Name 'zwana-quota.QuotaWidget' -ErrorAction SilentlyContinue
if ($installed) {
    Write-Host "Removing the installed copy ($($installed.Version))"
    $installed | Remove-AppxPackage
}

Write-Host "Installing $($msix.Name)"
Add-AppxPackage -Path $msix.FullName

Write-Host ""
Write-Host "Done. Open the widgets board, choose Add widgets, and look for" -ForegroundColor Green
Write-Host "'zwana quota' at the bottom of the list." -ForegroundColor Green
