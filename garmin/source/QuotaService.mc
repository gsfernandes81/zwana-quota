import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

// Woken by a message from the phone while the app is not running.
//
// It stores the message itself, so the glance has it the next time it is
// drawn, and also hands it on through Background.exit, which reaches the app
// when it next runs. Either alone would do on most firmware; both together
// cover a background process that cannot write storage and an app that is not
// started again for a while.
(:background)
class QuotaService extends System.ServiceDelegate {
    function initialize() {
        ServiceDelegate.initialize();
    }

    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        try {
            Quota.store(data);
        } catch (e) {
            // Storage refused from the background: Background.exit still carries it.
        }
        Background.exit(data as Application.PersistableType);
    }
}
