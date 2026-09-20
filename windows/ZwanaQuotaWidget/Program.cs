// Register the provider with OLE and wait. The widgets board launches this on
// demand and it exits once nothing is pinned — there is no long-running
// process here, which is why every bit of state the widget needs lives in the
// host's custom state rather than in memory.

using System.Runtime.InteropServices;
using System.Runtime.InteropServices.Marshalling;
using COM;
using ZwanaQuotaWidget;

// Build the card and write it out, without a widgets board or a COM host
// anywhere. The build runs this and parses what comes back, because the
// drawing path is the one part of a provider that can be exercised on a
// machine that has no widgets board — and it is where the first version of
// this went wrong, silently, on the sofa rather than in CI.
if (args.Length >= 2 && args[0] == "--render-selftest")
{
    File.WriteAllText(args[1], Cards.Compose("1.68 GiB", "95%, +762 MiB 05:30"));
    return 0;
}

Log.Write($"starting ({RuntimeInformation.ProcessArchitecture}, {RuntimeInformation.FrameworkDescription})");

var wrappers = new StrategyBasedComWrappers();
var factory = wrappers.GetOrCreateComInterfaceForObject(
    new WidgetProviderFactory(),
    CreateComInterfaceFlags.None);

var hr = Ole32.CoRegisterClassObject(
    WidgetProvider.ClassId,
    factory,
    Ole32.CLSCTX_LOCAL_SERVER,
    Ole32.REGCLS_MULTIPLEUSE,
    out var cookie);

if (hr < 0)
{
    Log.Write($"CoRegisterClassObject failed: 0x{hr:X8}");
    return hr;
}

Log.Write("registered; waiting for the host");

// Wait until the host has taken the last widget away, then stand down.
using (var emptyWidgetListEvent = WidgetProvider.GetEmptyWidgetListEvent())
{
    emptyWidgetListEvent.WaitOne();
}

Ole32.CoRevokeClassObject(cookie);
Marshal.Release(factory);
Log.Write("exiting: nothing pinned");
return 0;
