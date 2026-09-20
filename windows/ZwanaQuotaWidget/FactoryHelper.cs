// The COM class factory the widgets board activates us through. Boilerplate,
// and deliberately kept as close to the Windows App SDK sample as it can be:
// nothing here is ours to be clever about.

using Microsoft.Windows.Widgets.Providers;
using System.Runtime.InteropServices;
using WinRT;

namespace COM;

internal static class Guids
{
    public const string IClassFactory = "00000001-0000-0000-C000-000000000046";
    public const string IUnknown = "00000000-0000-0000-C000-000000000046";
}

[ComImport]
[ComVisible(false)]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
[Guid(Guids.IClassFactory)]
internal interface IClassFactory
{
    [PreserveSig]
    int CreateInstance(IntPtr pUnkOuter, ref Guid riid, out IntPtr ppvObject);

    [PreserveSig]
    int LockServer(bool fLock);
}

[ComVisible(true)]
internal class WidgetProviderFactory<T> : IClassFactory
    where T : IWidgetProvider, new()
{
    private const int CLASS_E_NOAGGREGATION = -2147221232;
    private const int E_NOINTERFACE = -2147467262;

    public int CreateInstance(IntPtr pUnkOuter, ref Guid riid, out IntPtr ppvObject)
    {
        ppvObject = IntPtr.Zero;

        if (pUnkOuter != IntPtr.Zero)
        {
            Marshal.ThrowExceptionForHR(CLASS_E_NOAGGREGATION);
        }

        // The host asks for IUnknown in practice; the other two are accepted so
        // that a host which asks for what it actually wants is not refused.
        if (riid == typeof(T).GUID
            || riid == Guid.Parse(Guids.IUnknown)
            || riid == typeof(IWidgetProvider).GUID)
        {
            ppvObject = MarshalInspectable<IWidgetProvider>.FromManaged(new T());
        }
        else
        {
            Marshal.ThrowExceptionForHR(E_NOINTERFACE);
        }

        return 0;
    }

    int IClassFactory.LockServer(bool fLock) => 0;
}
