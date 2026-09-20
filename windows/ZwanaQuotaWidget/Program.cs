// Register the provider with OLE and wait. The widgets board launches this on
// demand and it exits once nothing is pinned — there is no long-running
// process here, which is why every bit of state the widget needs lives in the
// host's custom state rather than in memory.

using System.Runtime.InteropServices;
using COM;
using ZwanaQuotaWidget;

const uint CLSCTX_LOCAL_SERVER = 0x4;
const uint REGCLS_MULTIPLEUSE = 0x1;

CoRegisterClassObject(
    Guid.Parse(WidgetProvider.ClassId),
    new WidgetProviderFactory<WidgetProvider>(),
    CLSCTX_LOCAL_SERVER,
    REGCLS_MULTIPLEUSE,
    out var cookie);

// Wait until the host has taken the last widget away, then stand down.
using (var emptyWidgetListEvent = WidgetProvider.GetEmptyWidgetListEvent())
{
    emptyWidgetListEvent.WaitOne();
}

CoRevokeClassObject(cookie);

[DllImport("ole32.dll")]
static extern int CoRegisterClassObject(
    [MarshalAs(UnmanagedType.LPStruct)] Guid rclsid,
    [MarshalAs(UnmanagedType.IUnknown)] object pUnk,
    uint dwClsContext,
    uint flags,
    out uint lpdwRegister);

[DllImport("ole32.dll")]
static extern int CoRevokeClassObject(uint dwRegister);
