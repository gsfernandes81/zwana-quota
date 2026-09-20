// The handshake the widgets board performs, performed here instead.
//
// The board's side of activation is four steps: get the class object for our
// CLSID, ask it for IClassFactory, call CreateInstance, and ask what comes
// back for IWidgetProvider. Every one of them happens across a COM boundary,
// none of them is visible from inside the provider, and if any fails the board
// says nothing at all — it launches the process, gets no object, and draws an
// empty widget. That is precisely the failure this has been stuck on, and the
// whole sequence runs perfectly well in a process with no widgets board in it.
//
// So the build runs it. If the source-generated COM plumbing ever stops
// exposing IClassFactory, or MarshalInspectable stops exposing IWidgetProvider
// — which is exactly the kind of thing Native AOT changes — this fails in CI
// rather than on a widgets board nobody can attach a debugger to.

using System.Runtime.InteropServices;
using System.Runtime.InteropServices.Marshalling;
using COM;
using Microsoft.Windows.Widgets.Providers;

namespace ZwanaQuotaWidget;

internal static unsafe class ComSelfTest
{
    private static readonly Guid IClassFactoryId = new("00000001-0000-0000-C000-000000000046");
    private static readonly Guid IUnknownId = new("00000000-0000-0000-C000-000000000046");

    /// <summary>0 if the board would have got a provider, 1 if it would not.</summary>
    public static int Run()
    {
        var failures = 0;

        bool Step(string what, int hr)
        {
            var ok = hr >= 0;
            Console.WriteLine($"{(ok ? "ok  " : "FAIL")}  {what}  0x{hr:X8}");
            if (!ok)
            {
                failures++;
            }
            return ok;
        }

        Step("CoInitializeEx", Ole32.CoInitializeEx(IntPtr.Zero, Ole32.COINIT_MULTITHREADED));

        var wrappers = new StrategyBasedComWrappers();
        var factoryObject = wrappers.GetOrCreateComInterfaceForObject(
            new WidgetProviderFactory(),
            CreateComInterfaceFlags.None);

        if (!Step("CoRegisterClassObject", Ole32.CoRegisterClassObject(
                WidgetProvider.ClassId,
                factoryObject,
                Ole32.CLSCTX_LOCAL_SERVER,
                Ole32.REGCLS_MULTIPLEUSE,
                out var cookie)))
        {
            return 1;
        }

        // 1. What the host does first: ask COM for our class object, as
        //    IClassFactory. A registration the host cannot query this way is a
        //    provider it will never call.
        var clsid = WidgetProvider.ClassId;
        var classFactoryId = IClassFactoryId;
        if (!Step("CoGetClassObject(IClassFactory)", Ole32.CoGetClassObject(
                in clsid,
                Ole32.CLSCTX_INPROC_OR_LOCAL,
                IntPtr.Zero,
                in classFactoryId,
                out var pFactory)))
        {
            return 1;
        }

        // 2. IClassFactory::CreateInstance, called through the vtable rather
        //    than through a managed wrapper: ComWrappers would hand back our
        //    own object and prove nothing about what a caller in another
        //    process sees. Slot 3 is CreateInstance — QueryInterface, AddRef
        //    and Release come first.
        var vtable = *(IntPtr**)pFactory;
        var createInstance =
            (delegate* unmanaged[Stdcall]<IntPtr, IntPtr, Guid*, IntPtr*, int>)vtable[3];

        var unknownId = IUnknownId;
        IntPtr provider;
        if (!Step("IClassFactory::CreateInstance", createInstance(pFactory, IntPtr.Zero, &unknownId, &provider))
            || provider == IntPtr.Zero)
        {
            Console.WriteLine("FAIL  CreateInstance returned no object");
            return 1;
        }

        // 3. And what the host does with it: ask for IWidgetProvider. This is
        //    the one MarshalInspectable is responsible for, and the one an AOT
        //    build can quietly stop satisfying.
        var providerId = typeof(IWidgetProvider).GUID;
        Step($"QueryInterface(IWidgetProvider {providerId})",
            Marshal.QueryInterface(provider, in providerId, out var pProvider));

        if (pProvider != IntPtr.Zero)
        {
            Marshal.Release(pProvider);
        }
        Marshal.Release(provider);
        Marshal.Release(pFactory);
        Ole32.CoRevokeClassObject(cookie);

        Console.WriteLine(failures == 0
            ? "the board would have got a provider"
            : $"{failures} step(s) the board depends on failed");
        return failures == 0 ? 0 : 1;
    }
}
