// The two OLE entry points a COM server needs, as source-generated P/Invokes:
// LibraryImport rather than DllImport, because the marshalling stubs have to
// exist at compile time for a Native AOT build.

using System.Runtime.InteropServices;

namespace COM;

internal static partial class Ole32
{
    /// <summary>Out-of-process server.</summary>
    public const uint CLSCTX_LOCAL_SERVER = 0x4;

    /// <summary>One registration serves every activation request.</summary>
    public const uint REGCLS_MULTIPLEUSE = 0x1;

    [LibraryImport("ole32.dll")]
    public static partial int CoRegisterClassObject(
        in Guid rclsid,
        IntPtr pUnk,
        uint dwClsContext,
        uint flags,
        out uint lpdwRegister);

    [LibraryImport("ole32.dll")]
    public static partial int CoRevokeClassObject(uint dwRegister);
}
