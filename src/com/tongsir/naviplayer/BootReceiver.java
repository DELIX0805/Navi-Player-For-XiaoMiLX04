package com.tongsir.naviplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        Prefs p = new Prefs(c);
        if (!p.configured() || !p.autostart()) return;
        Intent s = new Intent(c, PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            // ignore
        }
    }
}
