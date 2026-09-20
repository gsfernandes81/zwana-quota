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
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$msix = Get-ChildItem -Path $here -Filter *.msix | Select-Object -First 1
$cer = Get-ChildItem -Path $here -Filter *.cer | Select-Object -First 1
if (-not $msix) { throw "no .msix beside this script ($here)" }
if (-not $cer) { throw "no .cer beside this script ($here)" }

Write-Host "Trusting $($cer.Name)"
Import-Certificate -FilePath $cer.FullName -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null

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
