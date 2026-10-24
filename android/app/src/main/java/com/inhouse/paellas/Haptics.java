package com.inhouse.paellas;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Android's own touch feedback for the app. It follows the system "touch feedback"
 * setting, so it never buzzes for someone who turned vibration off. The page asks
 * for it with {type:"haptic", kind}; the native bar and sheet call it directly.
 */
final class Haptics {
    private Haptics() {
    }

    static void tap(View view, String kind) {
        if (view == null) return;
        int constant;
        switch (kind == null ? "" : kind) {
            case "tap":
                // Tabs and other navigation.
                constant = HapticFeedbackConstants.VIRTUAL_KEY;
                break;
            case "confirm":
                constant = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
                break;
            case "reject":
                constant = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
                break;
            default:
                // Every other button: the lightest tick.
                constant = HapticFeedbackConstants.KEYBOARD_TAP;
                break;
        }
        view.performHapticFeedback(constant);
    }
}
