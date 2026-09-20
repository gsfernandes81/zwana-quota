// The COM class factory the widgets board activates us through.
//
// Source-generated COM interop rather than the [ComImport] of the Windows App
// SDK sample: built-in COM marshalling is unavailable under Native AOT, and
// AOT is what takes the download from tens of megabytes to a few. The
// generated path works the same on the ordinary runtime, so this is one code
// path rather than two.

using System.Runtime.InteropServices;
using System.Runtime.InteropServices.Marshalling;
using Microsoft.Windows.Widgets.Providers;
using WinRT;
using ZwanaQuotaWidget;

namespace COM;

[GeneratedComInterface]
[Guid("00000001-0000-0000-C000-000000000046")]
internal partial interface IClassFactory
{
    [PreserveSig]
    int CreateInstance(IntPtr pUnkOuter, in Guid riid, out IntPtr ppvObject);

    [PreserveSig]
    int LockServer([MarshalAs(UnmanagedType.Bool)] bool fLock);
}

/// <summary>The class object COM hands the host when it activates our CLSID.</summary>
///
/// <remarks>
/// <c>[GeneratedComClass]</c> is not decoration. StrategyBasedComWrappers builds
/// the COM vtables for a managed object from this attribute; without it the
/// wrapper exposes IUnknown and nothing else, so the host's very first call —
/// CoGetClassObject for IClassFactory — comes back E_NOINTERFACE, CreateInstance
/// is never reached, and the board draws an empty widget having said nothing.
/// That was this provider's bug, and ComSelfTest is what now catches it.
/// </remarks>
[GeneratedComClass]
internal sealed partial class WidgetProviderFactory : IClassFactory
{
    private const int E_NOINTERFACE = unchecked((int)0x80004002);
    private const int CLASS_E_NOAGGREGATION = unchecked((int)0x80040110);

    private static readonly Guid IUnknownId = new("00000000-0000-0000-C000-000000000046");

    public int CreateInstance(IntPtr pUnkOuter, in Guid riid, out IntPtr ppvObject)
    {
        ppvObject = IntPtr.Zero;

        if (pUnkOuter != IntPtr.Zero)
        {
            return CLASS_E_NOAGGREGATION;
        }

        // The host asks for IUnknown in practice; IWidgetProvider is accepted
        // so that a host asking for what it actually wants is not refused.
        if (riid != IUnknownId && riid != typeof(IWidgetProvider).GUID)
        {
            Log.Write($"CreateInstance: refused {riid}");
            return E_NOINTERFACE;
        }

        try
        {
            ppvObject = MarshalInspectable<IWidgetProvider>.FromManaged(new WidgetProvider());
            Log.Write("CreateInstance: handed the host a provider");
            return 0;
        }
        catch (Exception ex)
        {
            // Returning a failure the host can see beats throwing across COM.
            Log.Write($"CreateInstance: FAILED {ex.GetType().Name}: {ex.Message}");
            return E_NOINTERFACE;
        }
    }

    public int LockServer(bool fLock) => 0;
}
