// Register the provider with OLE and wait. The widgets board launches this on
// demand and it exits once nothing is pinned — there is no long-running
// process here, which is why every bit of state the widget needs lives in the
// host's custom state rather than in memory.

using System.Runtime.InteropServices;
using System.Runtime.InteropServices.Marshalling;
using COM;
using ZwanaQuotaWidget;

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
Marshal.ThrowExceptionForHR(hr);

// Wait until the host has taken the last widget away, then stand down.
using (var emptyWidgetListEvent = WidgetProvider.GetEmptyWidgetListEvent())
{
    emptyWidgetListEvent.WaitOne();
}

Ole32.CoRevokeClassObject(cookie);
Marshal.Release(factory);
