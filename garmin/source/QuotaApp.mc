import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// The watch app: a glance for the glance loop, a full view behind it, and a
// background service that catches the phone's message when neither is on
// screen -- which is nearly always.
(:background, :glance)
class QuotaApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    // Opening the app is what registers for the phone's messages, so it must
    // be opened once after it is installed (README.md). The registration
    // outlives the app; opening it again is harmless.
    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        if (Background has :registerForPhoneAppMessageEvent) {
            Background.registerForPhoneAppMessageEvent();
        }
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        }
        return [new QuotaView()];
    }

    (:glance)
    function getGlanceView() as [WatchUi.GlanceView] or [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null {
        return [new QuotaGlance()];
    }

    function getServiceDelegate() as [System.ServiceDelegate] {
        return [new QuotaService()];
    }

    // What the background service passed on: now if the app is running, or
    // at its next start if not.
    function onBackgroundData(data as Application.PersistableType) as Void {
        if (Quota.store(data)) {
            WatchUi.requestUpdate();
        }
    }

    // A message that arrived while the app itself was open.
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        if (Quota.store(msg.data)) {
            WatchUi.requestUpdate();
        }
    }
}
