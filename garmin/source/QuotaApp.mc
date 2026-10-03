import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// The watch app: a glance for the glance loop, the pages behind it
// (Pages.mc), and a background service that catches the phone's message
// when neither is on screen -- which is nearly always.
(:background, :glance)
class QuotaApp extends Application.AppBase {
    // Whether this is the app itself -- not the glance or the background,
    // which run this same class without the pages.
    var app as Boolean = false;

    function initialize() {
        AppBase.initialize();
    }

    // Opening the app is what registers for the phone's messages, so it must
    // be opened once after it is installed (README.md), and it tells the
    // phone so (Ask.hello). The registration outlives the app; opening it
    // again is harmless.
    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        Background.registerForPhoneAppMessageEvent();
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        Ask.hello();
        Quota.remember = true;
        app = true;
        return Pages.view(0);
    }

    (:glance)
    function getGlanceView() as [WatchUi.GlanceView] or [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null {
        return [new QuotaGlance()];
    }

    function getServiceDelegate() as [System.ServiceDelegate] {
        return [new QuotaService()];
    }

    // What the background service passed on: now if the app is running, or
    // at its next start if not -- and to the glance, when it is the one on
    // screen. The app redraws for a message it kept; the glance redraws
    // whether or not it could store the message itself: the background
    // service stored it too where it could, and the glance reads from
    // storage, so it draws the newest copy that was kept. A page drawn for a
    // count of pages the message changed works out its page again
    // (QuotaView.current).
    function onBackgroundData(data as Application.PersistableType) as Void {
        var kept = Quota.store(data);
        if (kept || !app) {
            WatchUi.requestUpdate();
        }
        if (app) {
            Ask.heard();
        }
    }

    // A message that arrived while the app itself was open.
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        if (Quota.store(msg.data)) {
            WatchUi.requestUpdate();
        }
        Ask.heard();
    }
}
