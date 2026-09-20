// The widget provider itself. Phase 1 of docs/windows-widget.md: it pins,
// it draws, it survives the COM server being torn down between activations.
// It does not touch the portal — that is phase 2, and this build exists to
// answer the question that comes first, which is whether the widgets board on
// this machine will show a sideloaded provider at all.

using Microsoft.Windows.Widgets.Providers;

namespace ZwanaQuotaWidget;

/// <summary>What we keep about one pinned widget.</summary>
///
/// <remarks>
/// The host hands these back to us after a restart through
/// <c>GetWidgetInfos</c>, so this is the only state that survives; the COM
/// server is launched on demand and is not a long-running process.
/// </remarks>
internal sealed class PinnedWidget
{
    public required string Id { get; init; }
    public required string DefinitionId { get; init; }
    public string CustomState { get; set; } = "";
    public bool IsActive { get; set; }
}

internal sealed class WidgetProvider : IWidgetProvider
{
    /// <summary>The CLSID the package manifest activates us by.</summary>
    ///
    /// <remarks>
    /// This string appears three times and all three must agree: here, in
    /// <c>com:Class Id</c>, and in <c>CreateInstance ClassId</c>. Changing it
    /// orphans every pinned widget, so it does not change.
    /// </remarks>
    public static readonly Guid ClassId = new("96f8b4b8-68d6-42a0-bdeb-36c4b9fd75a2");

    /// <summary>The widget's <c>Definition Id</c> in the package manifest.</summary>
    public const string QuotaWidgetId = "Zwana_Quota";

    private static readonly ManualResetEvent EmptyWidgetList = new(false);

    private readonly Dictionary<string, PinnedWidget> _running = new();

    /// <summary>Signalled once nothing is pinned, which is when we may exit.</summary>
    public static ManualResetEvent GetEmptyWidgetListEvent() => EmptyWidgetList;

    public WidgetProvider()
    {
        // A reboot, a sign-out, or our own process being reclaimed all land
        // here: the host still has the widgets, so we ask it what we are
        // serving rather than assuming we are serving nothing.
        foreach (var info in WidgetManager.GetDefault().GetWidgetInfos())
        {
            var context = info.WidgetContext;
            if (_running.ContainsKey(context.Id))
            {
                continue;
            }

            _running[context.Id] = new PinnedWidget
            {
                Id = context.Id,
                DefinitionId = context.DefinitionId,
                CustomState = info.CustomState ?? "",
            };
        }
    }

    public void CreateWidget(WidgetContext widgetContext)
    {
        var widget = new PinnedWidget
        {
            Id = widgetContext.Id,
            DefinitionId = widgetContext.DefinitionId,
        };
        _running[widget.Id] = widget;
        UpdateWidget(widget);
    }

    public void DeleteWidget(string widgetId, string customState)
    {
        _running.Remove(widgetId);
        if (_running.Count == 0)
        {
            EmptyWidgetList.Set();
        }
    }

    public void OnActionInvoked(WidgetActionInvokedArgs actionInvokedArgs)
    {
        var widgetId = actionInvokedArgs.WidgetContext.Id;
        if (_running.TryGetValue(widgetId, out var widget))
        {
            UpdateWidget(widget);
        }
    }

    public void OnWidgetContextChanged(WidgetContextChangedArgs contextChangedArgs)
    {
        var widgetId = contextChangedArgs.WidgetContext.Id;
        if (_running.TryGetValue(widgetId, out var widget))
        {
            UpdateWidget(widget);
        }
    }

    /// <summary>The board is showing this widget and wants current content.</summary>
    ///
    /// <remarks>
    /// This is the whole of the refresh policy's hook: the phone fetches when
    /// someone taps the tile and never otherwise, and this is the equivalent
    /// moment. The window between here and <see cref="Deactivate"/> can be
    /// very short, so whatever phase 2 puts here draws the cached reading
    /// first and fetches after, never the other way round.
    /// </remarks>
    public void Activate(WidgetContext widgetContext)
    {
        if (_running.TryGetValue(widgetContext.Id, out var widget))
        {
            widget.IsActive = true;
            UpdateWidget(widget);
        }
    }

    public void Deactivate(string widgetId)
    {
        if (_running.TryGetValue(widgetId, out var widget))
        {
            widget.IsActive = false;
        }
    }

    private static void UpdateWidget(PinnedWidget widget)
    {
        if (widget.DefinitionId != QuotaWidgetId)
        {
            return;
        }

        var options = new WidgetUpdateRequestOptions(widget.Id)
        {
            Template = Cards.Template,
            Data = Cards.Data("— GiB", "skeleton build · no portal reading yet"),
            CustomState = widget.CustomState,
        };
        WidgetManager.GetDefault().UpdateWidget(options);
    }
}
