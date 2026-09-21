// The widget provider itself. Phase 1 of docs/windows-widget.md: it pins,
// it draws, it survives the COM server being torn down between activations.
// It does not touch the portal — that is phase 2, and this build exists to
// answer the question that comes first, which is whether the widgets board on
// this machine will show a sideloaded provider at all.

using System.Collections.Concurrent;
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

/// <remarks>
/// <c>partial</c> and <c>[GeneratedWinRTExposedType]</c> are both load-bearing,
/// and only under Native AOT. CsWinRT builds the WinRT vtable for a managed
/// class that implements a projected interface, and for an AOT build it must do
/// so at compile time — by adding a generated attribute to this class, which it
/// can only do if the class is partial. Without it the object the board
/// receives answers QueryInterface(IWidgetProvider) with E_NOINTERFACE: it
/// pins, it never draws, and nothing anywhere says why. The ordinary runtime
/// builds the same vtable at run time and does not care, which is what makes
/// this a difference between two builds of identical source.
/// </remarks>
[global::WinRT.GeneratedWinRTExposedType]
internal sealed partial class WidgetProvider : IWidgetProvider
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

    /// <summary>The widgets this process is serving.</summary>
    ///
    /// <remarks>
    /// Static and concurrent, both deliberately. The host may create more than
    /// one provider object against a single server, and this is an MTA server:
    /// callbacks arrive on whichever pool thread COM has free, so a plain
    /// Dictionary here is a data race that would show up as a widget that
    /// sometimes does not draw.
    /// </remarks>
    private static readonly ConcurrentDictionary<string, PinnedWidget> Running = new();

    /// <summary>How many widgets we are serving, for the idle check.</summary>
    public static int RunningCount => Running.Count;

    /// <summary>Signalled once nothing is pinned, which is when we may exit.</summary>
    public static ManualResetEvent GetEmptyWidgetListEvent() => EmptyWidgetList;

    public WidgetProvider()
    {
        Log.Write("provider constructed");

        // A reboot, a sign-out, or our own process being reclaimed all land
        // here: the host still has the widgets, so we ask it what we are
        // serving rather than assuming we are serving nothing.
        //
        // Guarded, because this is the one call in the constructor that talks
        // to the widget host — and a constructor that throws is a provider the
        // factory cannot hand over at all, which is a widget that never draws
        // for a reason two layers away from where it shows.
        Log.Guard("recover pinned widgets", () =>
        {
            foreach (var info in WidgetManager.GetDefault().GetWidgetInfos())
            {
                var context = info.WidgetContext;
                if (Running.ContainsKey(context.Id))
                {
                    continue;
                }

                Running[context.Id] = new PinnedWidget
                {
                    Id = context.Id,
                    DefinitionId = context.DefinitionId,
                    CustomState = info.CustomState ?? "",
                };
            }
        });
    }

    public void CreateWidget(WidgetContext widgetContext)
    {
        Log.Guard("CreateWidget", () =>
        {
            var widget = new PinnedWidget
            {
                Id = widgetContext.Id,
                DefinitionId = widgetContext.DefinitionId,
            };
            Log.Write($"CreateWidget {widget.DefinitionId} {widget.Id}");
            Running[widget.Id] = widget;
            UpdateWidget(widget);
        });
    }

    public void DeleteWidget(string widgetId, string customState)
    {
        Log.Write($"DeleteWidget {widgetId}");
        Running.TryRemove(widgetId, out _);
        if (Running.IsEmpty)
        {
            EmptyWidgetList.Set();
        }
    }

    public void OnActionInvoked(WidgetActionInvokedArgs actionInvokedArgs)
    {
        Log.Guard("OnActionInvoked", () =>
        {
            var widgetId = actionInvokedArgs.WidgetContext.Id;
            if (Running.TryGetValue(widgetId, out var widget))
            {
                UpdateWidget(widget);
            }
        });
    }

    public void OnWidgetContextChanged(WidgetContextChangedArgs contextChangedArgs)
    {
        Log.Guard("OnWidgetContextChanged", () =>
        {
            var widgetId = contextChangedArgs.WidgetContext.Id;
            Log.Write($"OnWidgetContextChanged {widgetId} size={contextChangedArgs.WidgetContext.Size}");
            if (Running.TryGetValue(widgetId, out var widget))
            {
                UpdateWidget(widget);
            }
        });
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
        Log.Guard("Activate", () =>
        {
            Log.Write($"Activate {widgetContext.Id}");
            if (Running.TryGetValue(widgetContext.Id, out var widget))
            {
                widget.IsActive = true;
                UpdateWidget(widget);
            }
            else
            {
                // The host knows about a widget we do not. Recovering here
                // rather than ignoring it is the difference between a blank
                // widget and a drawn one after the provider is restarted.
                Log.Write($"Activate: {widgetContext.Id} was not in the running set; adding it");
                var recovered = new PinnedWidget
                {
                    Id = widgetContext.Id,
                    DefinitionId = widgetContext.DefinitionId,
                    IsActive = true,
                };
                Running[recovered.Id] = recovered;
                UpdateWidget(recovered);
            }
        });
    }

    public void Deactivate(string widgetId)
    {
        Log.Write($"Deactivate {widgetId}");
        if (Running.TryGetValue(widgetId, out var widget))
        {
            widget.IsActive = false;
        }
    }

    private static void UpdateWidget(PinnedWidget widget)
    {
        if (widget.DefinitionId != QuotaWidgetId)
        {
            Log.Write($"UpdateWidget: ignoring unknown definition {widget.DefinitionId}");
            return;
        }

        var card = Cards.Compose("— GiB", "skeleton build · no portal reading yet");
        var options = new WidgetUpdateRequestOptions(widget.Id)
        {
            Template = card,
            Data = "{}",
            CustomState = widget.CustomState,
        };
        WidgetManager.GetDefault().UpdateWidget(options);
        Log.Write($"UpdateWidget: sent {card.Length} chars for {widget.Id}");
    }
}
