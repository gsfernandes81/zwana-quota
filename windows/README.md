# The quota on the Windows 11 widgets board

The build half. What is being built and why is `../docs/windows-widget.md`;
this file is how to get an installable package and put it on the machine.

**Status: the phase 1 skeleton.** It pins, it draws, and it says on its own
face that it has no portal reading yet — the point of this build is to answer
the question that comes before the code: whether this machine's widgets board
will show a sideloaded provider at all.

## Getting a build

Nothing is built on the target machine. It has no SDK, no Visual Studio and a
metered link, so **GitHub Actions is the compiler**:
[`.github/workflows/windows-msix.yml`](../.github/workflows/windows-msix.yml)
runs on `windows-latest` whenever `windows/**` changes, and can also be
started by hand from the Actions tab.

Download the **`zwana-quota-widget-msix`** artifact from the run. It holds
three files:

| file | what it is |
|---|---|
| `zwana-quota-widget.msix` | the package |
| `zwana-quota.cer` | the public half of the certificate that signed it |
| `install.ps1` | trusts the certificate and installs the package |

Then, from an **elevated** PowerShell in the folder you unzipped it into:

```powershell
.\install.ps1
```

Everything that script uses ships with Windows — `Import-Certificate`,
`Add-AppxPackage`. There is nothing further to download. Open the widgets
board, choose **Add widgets**, and look for *zwana quota* at the bottom.

## Two things worth knowing before the first install

**The package is about 81 MB.** It bundles the .NET runtime and the Windows
App SDK runtime, because the alternative is the target machine fetching both
over the satellite link. If this machine already has .NET 8 and the Windows
App Runtime, start the workflow by hand from the Actions tab with
**self_contained** unticked and the package drops to a couple of megabytes.

Where those megabytes go, measured rather than assumed — the build prints this
table at the end of every run:

| MB | | |
|---|---|---|
| 23.7 | `Microsoft.Windows.SDK.NET.dll` | the WinRT projection. This is how the widget APIs are called at all |
| 20.7 | `onnxruntime.dll` | |
| 17.8 | `DirectML.dll` | |
| 14.4 + 7.0 + 6.3 | `Microsoft.ui.xaml.dll`, `Microsoft.WinUI.dll`, `Microsoft.UI.Xaml.Controls.dll` | |
| 12.6 | `System.Private.CoreLib.dll` | |

**Roughly 66 MB of that is machinery this widget never calls**: a
self-contained Windows App SDK brings its whole runtime, including the ML
stack and the whole of XAML, and this provider renders an Adaptive Card and
never loads either. Deleting those files from the staged layout is the obvious
next saving and it is **deliberately not done yet**: if the widget then failed
to appear, there would be no way to tell a trimmed-too-far package from a
widgets board that does not take sideloaded providers. That is phase 0's
question and it gets asked on an untouched package. Once it is answered, this
is where the 66 MB is.

Two things measured and rejected on the way here, so they are not tried again:
localised WinUI resources (1.8 MB, kept), and `InvariantGlobalization`
(nothing at all — .NET on Windows uses the in-box ICU).

**Each run signs with a different certificate unless you pin one.** Windows
treats a package signed by a new key as a different signer and refuses to
upgrade in place, which is why `install.ps1` removes the installed copy first.
That is fine for iterating and annoying for anything else. To pin one — again
with no downloads, from an elevated PowerShell on any Windows machine:

```powershell
$cert = New-SelfSignedCertificate -Type Custom -Subject "CN=zwana-quota sideload" `
  -KeyUsage DigitalSignature -FriendlyName "zwana-quota sideload" `
  -CertStoreLocation "Cert:\CurrentUser\My" `
  -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}")

$password = Read-Host -AsSecureString "A password for the .pfx"
Export-PfxCertificate -Cert $cert -FilePath "$HOME\zwana-quota.pfx" -Password $password | Out-Null
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\zwana-quota.pfx")) | Set-Clipboard
```

Put the clipboard into the repository secret **`MSIX_PFX_BASE64`** and the
password into **`MSIX_PFX_PASSWORD`** (Settings → Secrets and variables →
Actions). Every build then signs with that certificate and installs over the
last one. **This repository is public: the `.pfx` and its password are
secrets, never files in the tree.** The workflow rewrites the manifest's
`Publisher` to match whatever certificate signs the build, so the subject you
choose above does not have to be the one in `AppxManifest.xml`.

## What is in here

| path | |
|---|---|
| `ZwanaQuotaWidget/Program.cs` | registers the COM class object and waits; the board launches this on demand and it exits once nothing is pinned |
| `ZwanaQuotaWidget/WidgetProvider.cs` | the `IWidgetProvider` callbacks, and the state recovery that makes a reboot survivable |
| `ZwanaQuotaWidget/FactoryHelper.cs` | the COM class factory. Boilerplate, kept close to the Windows App SDK sample |
| `ZwanaQuotaWidget/Cards.cs` | the Adaptive Card. Follows the board's theme rather than hardcoding one |
| `ZwanaQuotaWidget/AppxManifest.xml` | the package manifest: the COM server, and the registration that puts the widget in the picker |
| `install.ps1` | shipped inside the artifact, not run from the checkout |

The CLSID `96f8b4b8-68d6-42a0-bdeb-36c4b9fd75a2` appears in `WidgetProvider.cs`
and twice in `AppxManifest.xml`. All three must agree, and changing it orphans
every pinned widget.

## If it does not appear in the picker

That is a real possibility rather than a formality — it is the most commonly
reported failure with sideloaded providers, and the reason this skeleton
exists before any portal code. Worth checking in order:

1. `Get-AppxPackage -Name zwana-quota.QuotaWidget` — is it installed at all?
2. Is the certificate in `Cert:\LocalMachine\TrustedPeople` (not CurrentUser)?
3. Does the board show **Add widgets** at all on this build?
4. Widgets board restarted: `taskkill /f /im widgets.exe` (it comes back).

If it is installed, trusted, and still absent from the picker, that is the
phase 0 answer and it changes the plan rather than the code — say so in
`../docs/windows-widget.md` and stop.
