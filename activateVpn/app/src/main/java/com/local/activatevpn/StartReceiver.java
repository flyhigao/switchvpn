package com.local.activatevpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.local.vpncommon.Controller;


/**
 * 无界面的入口，方便用 adb / 自动化发广播：
 *
 *   adb shell am broadcast -a com.local.activatevpn.action.ON -n com.local.activatevpn/.StartReceiver
 *   adb shell am broadcast -a com.local.activatevpn.action.ON -n com.local.activatevpn/.StartReceiver --es mode status
 *   adb shell am broadcast -a com.local.activatevpn.action.ON -n com.local.activatevpn/.StartReceiver --es mode stop
 */
public class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final String mode = Controller.resolveMode(intent, Controller.MODE_START);
        Log.i("VpnSwitch", "StartReceiver mode=" + mode);
        final int settle = Controller.resolveSettleSeconds(intent);
        Log.i("VpnSwitch", "settle=" + settle + "s");
        Controller.scheduleSettled(context, intent, mode, settle, new Controller.Callback() {
            @Override
            public void onResult(boolean ok, String message) {
                Log.i("VpnSwitch", "receiver result ok=" + ok + " msg=" + message);
                pending.finish();
            }
        });
    }
}
