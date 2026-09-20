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

Each run builds **two** variants, because nothing here can drive the widgets
board and the smaller one gets there by a route that can fail at runtime:

| artifact | size | what it is |
|---|---|---|
| **`zwana-quota-widget-aot`** | **1.7 MB** | compiled ahead of time. No .NET runtime at all: the package is the native exe, the Widgets DLL, and the images. **Start here.** |
| `zwana-quota-widget-slim` | 38.7 MB | the same code on the ordinary .NET runtime. The fallback if the AOT build misbehaves once it is actually on the board |

Each artifact holds three files:

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

## How it got from 80 MB to 1.7

Worth writing down, because every one of these was measured and two of the
obvious answers were wrong.

The first working build was **80.2 MB**. Three things fixed it:

1. **Reference the component package, not the metapackage** — 80.2 → 38.7 MB.
   `Microsoft.WindowsAppSDK` is the whole SDK: WinUI, the XAML renderer, and
   from 1.8 onwards `onnxruntime.dll` (21 MB) and `DirectML.dll` (18 MB).
   Others hit the same 40 MB on 1.8 ([WindowsAppSDK#5969][ml]);
   the component packages are the SDK's own answer.
   `Microsoft.WindowsAppSDK.Widgets` is one 2.4 MB DLL, its projection, and
   `Base`, which is half a megabyte of MSBuild targets.
2. **Native AOT** — 38.7 → 1.7 MB. What was left after (1) was all .NET:
   23.7 MB of Windows metadata projection, 12.6 of `CoreLib`, 4.8 of
   `coreclr`, against 2.4 MB of the Widgets runtime the provider actually
   calls. Compiled ahead of time there is no runtime to ship, and the staged
   package is 4.6 MB: `ZwanaQuotaWidget.exe` (2.2), the Widgets DLL (2.4), and
   the images.
3. **Drop the localised WinUI resources** — 1.8 MB, back when there was still
   WinUI to localise.

Two that were tried and bought nothing, so that nobody tries them twice:
`InvariantGlobalization` (**zero** — .NET on Windows uses the in-box ICU
rather than shipping its own), and `PublishTrimmed`, which **died on startup**
and was caught by the smoke test below.

[ml]: https://github.com/microsoft/WindowsAppSDK/issues/5969

## When it pins but draws nothing

The provider writes **`C:\Users\<you>\zwana-quota-widget.log`** — profile
root rather than AppData, because MSIX redirects AppData writes into the
package's own store and a log nobody can find is not a log. Every callback
from the board is in it, and so is anything that threw.

What the lines mean:

| the log says | what it means |
|---|---|
| nothing at all, no file | the board never launched the provider: COM registration, or the package's `com:ExeServer` entry |
| `starting` then nothing | the host launched us but never asked for a provider |
| `CreateInstance: handed the host a provider` | activation works |
| `CreateWidget` then `UpdateWidget: sent N chars` | the card went to the board. A blank widget after this is the card, not the plumbing |
| `FAILED <exception>` | there it is |

## Two things the build checks, because they fail silently

**Registration-free WinRT.** A self-contained Windows App SDK app does not
register its WinRT classes through the package manifest. The Widgets package
carries a `package.appxfragment` declaring `WidgetManager` and friends, and
`Microsoft.WindowsAppSDK.Base`'s `SelfContained.targets` compiles it into a
side-by-side manifest **embedded in the exe**. Lose it — to a project change,
a packaging shortcut — and the widget pins and never draws, with
`WidgetManager.GetDefault()` throwing class-not-registered where nobody sees
it. The build greps the exe for it.

**A provider that dies on startup.** It would also pin and never draw. The
build runs it for five seconds and keeps whatever it said on the way out.
That is what caught `PublishTrimmed`.

**A card that cannot be built.** The build runs the exe with
`--render-selftest` and parses what comes back, because the drawing path is
the one part of a provider that works without a widgets board. This is not
hypothetical: the first AOT build serialised the card with
`JsonSerializer.Serialize(new { … })`, reflection-based serialisation is off
under Native AOT, and it threw inside the callback that draws. Startup was
clean, the widget pinned, and it showed nothing. Three checks now stand
between that and the sofa.

## Signing

Each run signs with its own self-signed certificate, so `install.ps1` removes
the installed copy before installing: Windows treats a package signed by a new
key as a different signer and refuses to upgrade in place. That is fine for
iterating and annoying for anything else. To pin one — again with no
downloads, from an elevated PowerShell on any Windows machine:

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
| `ZwanaQuotaWidget/FactoryHelper.cs` | the COM class factory, on source-generated interop rather than the sample's `[ComImport]` — Native AOT has no built-in COM marshalling |
| `ZwanaQuotaWidget/Ole32.cs` | the two OLE entry points, as `LibraryImport` P/Invokes |
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
