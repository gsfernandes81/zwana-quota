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
/// So: a file, in the user's profile root rather than under AppData, because
/// MSIX redirects AppData writes into the package's own store and the point of
/// this file is that a person can find it without being told a hashed path.
/// Logging never throws; a diagnostic that can take the process down is worse
/// than no diagnostic.
/// </remarks>
internal static class Log
{
    private static readonly object Gate = new();

    private static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        "zwana-quota-widget.log");

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                File.AppendAllText(
                    Path,
                    $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}");
            }
        }
        catch
        {
            // A widget that cannot write its log still has a widget to draw.
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
