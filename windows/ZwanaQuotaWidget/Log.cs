namespace ZwanaQuotaWidget;

/// <summary>A log file, because every failure mode here is a silent one.</summary>
///
/// <remarks>
/// The widgets board launches this process, talks to it over COM, and shows a
/// blank widget whatever goes wrong: a provider that throws, one that is never
/// activated, and one drawing a card the board cannot parse all look identical
/// from the sofa. There is no console — it is a <c>WinExe</c> — and the
/// machine that runs it has no debugger and no way to fetch one.
///
/// It writes to **every** location it can rather than one, because the first
/// version wrote to the profile root alone and came back empty, which could
/// mean either "never ran" or "could not write there" — and those want
/// opposite investigations. MSIX redirects some writes into the package's own
/// store, so one of these is the package-local path on purpose.
///
/// Logging never throws. A diagnostic that can take the process down is worse
/// than no diagnostic.
/// </remarks>
internal static class Log
{
    private static readonly object Gate = new();

    private static readonly string[] Paths = BuildPaths();

    private static string[] BuildPaths()
    {
        var candidates = new List<string>();

        void Add(Environment.SpecialFolder folder)
        {
            try
            {
                var root = Environment.GetFolderPath(folder);
                if (!string.IsNullOrEmpty(root))
                {
                    candidates.Add(System.IO.Path.Combine(root, "zwana-quota-widget.log"));
                }
            }
            catch
            {
                // A folder we cannot name is a folder we cannot write to.
            }
        }

        Add(Environment.SpecialFolder.UserProfile);
        // Redirected into the package's own store under MSIX, which is exactly
        // why it is worth having as well as the profile root.
        Add(Environment.SpecialFolder.LocalApplicationData);

        try
        {
            candidates.Add(System.IO.Path.Combine(
                System.IO.Path.GetTempPath(), "zwana-quota-widget.log"));
        }
        catch
        {
        }

        return candidates.ToArray();
    }

    public static void Write(string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{Environment.ProcessId}] {message}{Environment.NewLine}";
        lock (Gate)
        {
            foreach (var path in Paths)
            {
                try
                {
                    File.AppendAllText(path, line);
                }
                catch
                {
                    // Try the next one; a widget that cannot write its log
                    // still has a widget to draw.
                }
            }
        }
    }

    /// <summary>Run <paramref name="action"/>, logging anything it throws.</summary>
    ///
    /// <remarks>
    /// Every callback from the widget host goes through here. An exception
    /// crossing the COM boundary tells the board nothing it will show anyone,
    /// so it is caught, written down, and swallowed.
    /// </remarks>
    public static void Guard(string what, Action action)
    {
        try
        {
            action();
        }
        catch (Exception ex)
        {
            Write($"{what}: FAILED {ex.GetType().Name}: {ex.Message}");
        }
    }
}
