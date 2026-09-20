namespace ZwanaQuotaWidget;

/// <summary>The widget's face, as an Adaptive Card.</summary>
///
/// <remarks>
/// Not the terminal face. The 35 x 5 budget and the tile string budgets
/// constrain the Android surfaces and nothing here (docs/windows-widget.md).
///
/// Colours are left to the board: a widget card inherits the host's own
/// light/dark handling, and following it is both less work and less wrong
/// than hardcoding a palette the host may re-theme underneath us.
///
/// **Nothing in here may use reflection.** The card is built by hand rather
/// than serialised, because <c>JsonSerializer</c> over an anonymous type is
/// reflection-based, and reflection-based serialisation is off under Native
/// AOT — where it throws inside the callback that draws, so the widget pins
/// and shows nothing. That is precisely the bug this file used to have.
/// <see cref="Compose"/> is exercised by the build for the same reason.
/// </remarks>
internal static class Cards
{
    /// <summary>The card, with the two values already in it.</summary>
    public static string Compose(string figure, string note) =>
        $$"""
        {
            "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
            "type": "AdaptiveCard",
            "version": "1.5",
            "body": [
                {
                    "type": "TextBlock",
                    "text": "zwana quota",
                    "size": "small",
                    "isSubtle": true,
                    "wrap": true
                },
                {
                    "type": "TextBlock",
                    "text": "{{Escape(figure)}}",
                    "size": "extraLarge",
                    "weight": "bolder",
                    "spacing": "none",
                    "wrap": true
                },
                {
                    "type": "TextBlock",
                    "text": "{{Escape(note)}}",
                    "size": "small",
                    "isSubtle": true,
                    "spacing": "none",
                    "wrap": true
                }
            ]
        }
        """;

    /// <summary>The five escapes a JSON string value can need.</summary>
    ///
    /// <remarks>
    /// Everything drawn here is ours — a size, a unit, a clock time — so this
    /// is not a general-purpose encoder. It exists so that a value which one
    /// day arrives with a quote in it produces a wrong figure rather than a
    /// card the board cannot parse, which would be another blank widget.
    /// </remarks>
    private static string Escape(string value) => value
        .Replace("\\", "\\\\")
        .Replace("\"", "\\\"")
        .Replace("\n", "\\n")
        .Replace("\r", "\\r")
        .Replace("\t", "\\t");
}
