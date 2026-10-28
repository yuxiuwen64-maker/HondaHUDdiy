/*
 * Copyright (C) 2026 yuxiuwen64-maker
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.hudfree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.enabled(context)) {
            return;
        }
        Intent svc = new Intent(context, HudService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
