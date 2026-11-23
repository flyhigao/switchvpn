package com.local.deactivatevpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.local.vpncommon.Controller;

/**
 * 打开本应用 = 确保 v2rayNG 的代理关闭。
 *
 * 完全不显示任何界面（Theme.NoDisplay），也不弹 Toast：
 * 只登记 10 秒后的动作，然后立刻结束自己，屏幕上不会有任何痕迹。
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_MODE = Controller.MODE_STOP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        start(getIntent());
        // Theme.NoDisplay 要求进入 onResume 前结束，否则会抛异常；这同时保证了零界面
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i("VpnSwitch", "onNewIntent, re-schedule");
        start(intent);
        finish();
    }

    private void start(Intent intent) {
        final String mode = Controller.resolveMode(intent, DEFAULT_MODE);
        final int settle = Controller.resolveSettleSeconds(intent);
        Log.i("VpnSwitch", "launch mode=" + mode + " settle=" + settle + "s");
        Controller.scheduleSettled(this, intent, mode, settle, new Controller.Callback() {
            @Override
            public void onResult(boolean ok, String message) {
                // 只写日志（adb logcat -s VpnSwitch 可见），不打扰屏幕
                Log.i("VpnSwitch", "result ok=" + ok + " msg=" + message);
            }
        });
    }
}
