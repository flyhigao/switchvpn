package com.local.deactivatevpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.local.vpncommon.Controller;

/**
 * 打开本应用 = 确保 v2rayNG 的代理处于关闭状态。
 * 窗口是透明的：先登记 10 秒后的动作就立即退出，实际上只闪一条 Toast。
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_MODE = Controller.MODE_STOP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        start(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 自动化可能在我们还没退干净时又拉一次：按新的触发重新计时
        setIntent(intent);
        Log.i("VpnSwitch", "onNewIntent, re-schedule");
        start(intent);
    }

    private void start(final Intent intent) {
        final String mode = Controller.resolveMode(intent, DEFAULT_MODE);
        final int settle = Controller.resolveSettleSeconds(intent);
        // 兜底：万一回调没来，别让这个透明界面一直赖着
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, (settle + 20) * 1000L);

        Controller.scheduleSettled(this, intent, mode, settle, new Controller.Callback() {
            @Override
            public void onResult(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        // “⏳ 10 秒后确认…”只是登记成功，真正干完才退出，
                        // 这样等待期间进程还挂在前台，不会被系统冻结
                        if (message == null || !message.startsWith("⏳")) {
                            finish();
                        }
                    }
                });
            }
        });
    }
}
