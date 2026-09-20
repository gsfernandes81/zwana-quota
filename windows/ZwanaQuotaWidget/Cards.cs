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
/// This is the skeleton card. It draws no portal data and says so on its
/// face, because a placeholder that looks like a reading is the one thing
/// worse than no reading at all.
/// </remarks>
internal static class Cards
{
    public const string Template = """
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
            "text": "${figure}",
            "size": "extraLarge",
            "weight": "bolder",
            "spacing": "none",
            "wrap": true
        },
        {
            "type": "TextBlock",
            "text": "${note}",
            "size": "small",
            "isSubtle": true,
            "spacing": "none",
            "wrap": true
        }
    ]
}
""";

    public static string Data(string figure, string note) =>
        System.Text.Json.JsonSerializer.Serialize(new { figure, note });
}
