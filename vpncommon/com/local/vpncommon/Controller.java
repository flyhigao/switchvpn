package com.local.vpncommon;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Bundle;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * activateVpn / deactivateVpn 共用的核心逻辑：
 * 探测 v2rayNG 状态 -> 通过 v2rayNG 官方 Tasker/Locale 插件广播启动/停止代理。
 *
 * v2rayNG 暴露的对外接口（源码 com.v2ray.ang.receiver.TaskerReceiver，exported=true）：
 *   action : com.twofortyfouram.locale.intent.action.FIRE_SETTING
 *   extra  : com.twofortyfouram.locale.intent.extra.BUNDLE (Bundle)
 *            tasker_extra_bundle_switch = true/false   // true=启动 false=停止
 *            tasker_extra_bundle_guid   = "Default"    // 用当前选中的服务器
 *
 * 停止接口（CoreServiceManager 运行时动态注册的 exported receiver）：
 *   action : <pkg>.action.service   extra : key = 4  (MSG_STATE_STOP)
 *
 * 防抖（重要）：网络刚变（离开/连上 WiFi 的瞬间）不要立即执行。
 * 做法：启动后先记住当时的网络指纹，等待 settle 秒（默认 10），再检查：
 *   - 期间网络指纹变了（离开/连上 WiFi 的抖动）→ 放弃，不动作；
 *   - 期间来了新的触发（同一 app 再次启动）→ 放弃，让新的那次去处理。
 * 注意：等待期间进程必须活着。ColorOS 会把退到后台的进程丢进 freezer 冻结
 *   （实测 cgroup.freeze=1，连前台服务也挡不住），所以：
 *   - 屏幕亮着：透明 Activity 留在前台等，稳；
 *   - 屏幕熄灭：需要给这两个 app 开“允许后台运行”，否则等待会被打断。
 * 曾试过、不行的方案：AlarmManager 精确闹钟 —— ColorOS 的 OplusAlarmAdjustment
 *   把后台应用的闹钟直接推迟到 +2 天 23 小时（dumpsys alarm 可见 adjustment=+2d23h...）。
 *   可用 extra "settle"（秒）为单次调用覆盖，0 = 不等待立即执行。
 */
public final class Controller {
    private static final String TAG = "VpnSwitch";

    public static final String MODE_START = "start";
    public static final String MODE_STOP = "stop";
    public static final String MODE_TOGGLE = "toggle";
    public static final String MODE_STATUS = "status";

    private static final String ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING";
    private static final String EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE";
    private static final String EXTRA_SWITCH = "tasker_extra_bundle_switch";
    private static final String EXTRA_GUID = "tasker_extra_bundle_guid";
    private static final String GUID_DEFAULT = "Default";
    private static final int MSG_STATE_STOP = 4;

    private static final String[] V2RAY_PKGS = {"com.v2ray.ang", "com.v2ray.ang.fdroid"};
    private static final int[] SOCKS_PORTS = {10808, 10809};
    private static final int PROBE_TIMEOUT_MS = 300;
    private static final int WAIT_START_MS = 8000;
    private static final int WAIT_STOP_MS = 4000;
    private static final int FALLBACK_UI_DELAY_MS = 1500;

    /** 网络稳定等待时间（秒）。想改默认值就改这里重新构建。 */
    private static final int DEFAULT_SETTLE_SECONDS = 10;
    private static final String EXTRA_SETTLE = "settle";
    private static final String PREFS = "vpnswitch";
    private static final String KEY_SEQ = "seq";
    private static final String KEY_PENDING_MODE = "pending_mode";
    private static final String KEY_PENDING_FP = "pending_fp";
    private static final String KEY_PENDING_SEQ = "pending_seq";
    private static final String KEY_PENDING_AT = "pending_at";
    private static final String KEY_PENDING_SETTLE = "pending_settle";
    private static final String KEY_PENDING_HOPS = "pending_hops";
    private static final String KEY_PENDING_NONCE = "pending_nonce";
    public static final int JOB_ID = 0x5652;
    private static final Object LOCK = new Object();


    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private Controller() {
    }

    /**
     * 从 Intent 里取 mode，优先级：extra mode > URI（vpnswitch://start|stop|status）> action 名
     * （...action.START / .STOP / .TOGGLE / .STATUS）> 调用方给的默认值。
     */
    public static String resolveMode(Intent intent, String defaultMode) {
        if (intent == null) return defaultMode;
        String m = intent.getStringExtra("mode");
        if (m != null && m.length() > 0) return normalizeMode(m);
        if (intent.getData() != null) {
            String d = intent.getData().getHost();
            if (d == null || d.length() == 0) {
                String path = intent.getData().getPath();
                d = path != null ? path.replace("/", "") : null;
            }
            if (d != null && d.length() > 0) {
                String n = normalizeMode(d);
                if (n != null) return n;
            }
        }
        String a = intent.getAction();
        if (a != null) {
            if (a.endsWith(".STOP") || a.endsWith(".OFF")) return MODE_STOP;
            if (a.endsWith(".START") || a.endsWith(".ON")) return MODE_START;
            if (a.endsWith(".TOGGLE")) return MODE_TOGGLE;
            if (a.endsWith(".STATUS")) return MODE_STATUS;
        }
        return defaultMode;
    }

    private static String normalizeMode(String raw) {
        String v = raw.trim().toLowerCase();
        if (v.equals("start") || v.equals("on") || v.equals("open") || v.equals("1")) return MODE_START;
        if (v.equals("stop") || v.equals("off") || v.equals("close") || v.equals("0")) return MODE_STOP;
        if (v.equals("toggle")) return MODE_TOGGLE;
        if (v.equals("status")) return MODE_STATUS;
        return MODE_TOGGLE;
    }

    /** 网络稳定等待秒数：intent extra "settle" 优先，否则用默认值。 */
    public static int resolveSettleSeconds(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_SETTLE)) {
            try {
                return Math.max(0, intent.getIntExtra(EXTRA_SETTLE, DEFAULT_SETTLE_SECONDS));
            } catch (Throwable ignored) {
            }
        }
        return DEFAULT_SETTLE_SECONDS;
    }

    /** 工作线程里执行：探测状态 → 开/关代理（settleSeconds 已不再使用，等待由 job 负责）。 */
    public static void run(final Context ctx, final String mode, final int settleSeconds, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String msg;
                try {
                    String pkg = findV2rayPackage(ctx);
                    if (pkg == null) {
                        cb.onResult(false, "未找到 v2rayNG（com.v2ray.ang）");
                        return;
                    }
                    boolean running = isRunning(ctx);
                    Log.i(TAG, "mode=" + mode + " pkg=" + pkg + " running=" + running);

                    if (MODE_STOP.equals(mode)) {
                        if (!running) {
                            ok = true;
                            msg = "代理本来就没在运行";
                        } else {
                            ok = stop(ctx, pkg);
                            msg = ok ? "⛔ 代理已关闭" : "❌ 关闭失败：v2rayNG 没响应停止指令";
                        }
                    } else if (MODE_TOGGLE.equals(mode)) {
                        if (running) {
                            ok = stop(ctx, pkg);
                            msg = ok ? "⛔ 代理已关闭" : "❌ 关闭失败";
                        } else {
                            ok = start(ctx, pkg);
                            msg = ok ? "✅ 代理已启动" : failStartMsg();
                        }
                    } else if (MODE_STATUS.equals(mode)) {
                        ok = running;
                        msg = running ? "v2rayNG 正在运行" : "v2rayNG 未运行";
                    } else {
                        if (running) {
                            ok = true;
                            msg = "代理已在运行，无需操作";
                        } else {
                            ok = start(ctx, pkg);
                            msg = ok ? "✅ 代理已启动" : failStartMsg();
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "failed", t);
                    ok = false;
                    msg = "异常：" + t.getClass().getSimpleName() + " " + t.getMessage();
                }
                cb.onResult(ok, msg);
            }
        }, "vpnswitch-worker").start();
    }

    /**
     * 延迟执行入口：settleSeconds>0 时登记一个 10 秒后的 Job，立刻返回；
     * Job 到点时（系统会临时唤醒/解冻本进程）再比对网络指纹，没变才真正执行。
     * settleSeconds<=0 或 status 模式则立即执行。
     */
    public static void scheduleSettled(Context ctx, Intent src, String defaultMode, int settleSeconds,
                                       final Callback cb) {
        final String mode = resolveMode(src, defaultMode);
        if (settleSeconds <= 0 || MODE_STATUS.equals(mode)) {
            run(ctx, mode, 0, cb);
            return;
        }
        long now = System.currentTimeMillis();
        final String nonce = now + "-" + Math.abs(mode.hashCode() % 1000);
        String fp = netFingerprint(ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_SEQ, now)
                .putString(KEY_PENDING_MODE, mode)
                .putString(KEY_PENDING_FP, fp)
                .putLong(KEY_PENDING_SEQ, now)
                .putLong(KEY_PENDING_AT, now)
                .putInt(KEY_PENDING_SETTLE, settleSeconds)
                .putInt(KEY_PENDING_HOPS, 0)
                .putString(KEY_PENDING_NONCE, nonce)
                .apply();
        Log.i(TAG, "schedule mode=" + mode + " settle=" + settleSeconds + "s net=" + fp);

        boolean ok = scheduleJob(ctx, mode, settleSeconds);
        if (!ok) {
            // job 排不上就尽快干，至少别什么都不做
            Log.w(TAG, "job not scheduled, run now");
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(KEY_PENDING_MODE).apply();
            run(ctx, mode, 0, cb);
            return;
        }
        // 双保险：屏幕亮着时进程不会被冻结，就地等满 settle 秒后先下手；
        // 息屏/被冻结时这条线程会被挂起，那时就靠上面的 job 兜底。先到先做，另一个自动空转。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(settleSeconds * 1000L);
                } catch (InterruptedException ignored) {
                }
                Log.i(TAG, "inline waiter reached " + settleSeconds + "s");
                firePending(ctx, cb, "inline", nonce);
            }
        }, "vpnswitch-waiter").start();

        cb.onResult(true, "⏳ " + settleSeconds + " 秒后确认网络，再"
                + ("stop".equals(mode) ? "关闭" : "开启") + "代理");
    }


    /**
     * 排一个 settle 秒后执行的 job。等待由系统调度器负责 —— 我们的进程可以立刻被冻结，
     * 到点系统会把进程叫醒执行 job（实测：息屏/Doze 下也能跑）。
     * 注意：expedited job 不允许带 delay，而"expedited 里自己 sleep"会被 ColorOS 冻结，已弃用。
     */
    private static boolean scheduleJob(Context ctx, String mode, int settleSeconds) {
        return scheduleJob(ctx, mode, settleSeconds, settleSeconds * 1000L);
    }

    private static boolean scheduleJob(Context ctx, String mode, int settleSeconds, long latency) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return false;
        ComponentName cn = new ComponentName(ctx, SettleJobService.class);
        try {
            js.cancel(JOB_ID);
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, cn)
                    .setMinimumLatency(latency)
                    .setOverrideDeadline(latency + 1000);
            if (js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "job scheduled mode=" + mode + " latency=" + latency + "ms");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "schedule job failed: " + t);
        }
        return false;
    }

    /**
     * Job 到点：比对网络指纹，没变才执行。由 SettleJobService 调用。
     */
    public static void firePending(Context ctx, final Callback cb) {
        firePending(ctx, cb, "job", null);
    }

    private static void firePending(Context ctx, final Callback cb, String from, String nonce) {
      synchronized (LOCK) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_PENDING_MODE, null);
        String fpBefore = prefs.getString(KEY_PENDING_FP, null);
        long at = prefs.getLong(KEY_PENDING_AT, 0);
        long seq = prefs.getLong(KEY_PENDING_SEQ, 0);
        int settle = prefs.getInt(KEY_PENDING_SETTLE, 0);
        int hops = prefs.getInt(KEY_PENDING_HOPS, 0);
        String pendingNonce = prefs.getString(KEY_PENDING_NONCE, null);
        // 又来了一次新的触发：这条老的 waiter 不许动手，让新的那次重新计时
        if (nonce != null && !nonce.equals(pendingNonce)) {
            Log.i(TAG, "fire(" + from + ") stale waiter (nonce=" + nonce + " vs " + pendingNonce + "), ignore");
            cb.onResult(true, "（已有更新的触发，本次忽略）");
            return;
        }
        long elapsed = at > 0 ? System.currentTimeMillis() - at : 0;
        // job 常常被提前（Activity 刚起时进程算前台，JobScheduler 会立刻放行）：
        // 那就把剩下的时间再排一次，绝对不在 settle 之前动手。
        if (settle > 0 && elapsed < settle * 1000L - 200 && hops < 10) {
            long remain = settle * 1000L - elapsed;
            prefs.edit().putInt(KEY_PENDING_HOPS, hops + 1).apply();
            boolean ok = scheduleJob(ctx, mode, (int) Math.ceil(remain / 1000.0), remain);
            Log.i(TAG, "job fired early (elapsed=" + elapsed + "ms), re-scheduled remain=" + remain
                    + "ms ok=" + ok);
            cb.onResult(true, "⏳ 还差 " + (remain / 1000) + " 秒");
            return;
        }
        boolean newer = prefs.getLong(KEY_SEQ, 0) != seq;
        prefs.edit().remove(KEY_PENDING_MODE).remove(KEY_PENDING_FP)
                .remove(KEY_PENDING_SEQ).remove(KEY_PENDING_AT)
                .remove(KEY_PENDING_SETTLE).apply();

        if (mode == null) {
            Log.i(TAG, "fire: nothing pending");
            cb.onResult(true, "（无待执行动作）");
            return;
        }
        String fpNow = netFingerprint(ctx);
        long late = at > 0 ? System.currentTimeMillis() - at : -1;
        Log.i(TAG, "fire(" + from + ") mode=" + mode + " late=" + late + "ms settle=" + settle
                + "s net=" + fpBefore + " -> " + fpNow + (newer ? " (newer trigger seen)" : ""));
        if (newer) {
            cb.onResult(true, "⏸ 又有新的网络事件，本次不执行");
            return;
        }
        if (fpBefore != null && !fpBefore.equals(fpNow)) {
            cb.onResult(true, "⏸ 网络又变了，本次不执行");
            return;
        }
        // 10 秒已经在系统调度器里等过了，这里直接干活（趁进程被唤醒的这一小段活着）
        run(ctx, mode, 0, cb);
      }
    }

    /**
     * 只看 WiFi 的网络“指纹”：网关地址（拿不到就用接口地址）。
     * 判断的是“还是不是同一个 WiFi / 还有没有 WiFi”，不受蜂窝网络抖动、VPN 开关影响。
     * 不需要任何权限。
     */
    private static String netFingerprint(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "NONE";
            List<String> wifis = new ArrayList<>();
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                // VPN 网络会继承底层 WiFi 的 transport，它自己的网关是 10.10.14.1 之类，
                // 开关 VPN 时会让指纹抖动，所以跳过。
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                String id = "";
                if (lp != null) {
                    InetAddress gw6 = null;
                    for (RouteInfo r : lp.getRoutes()) {
                        InetAddress g = r.getGateway();
                        if (!r.isDefaultRoute() || g == null || g.isAnyLocalAddress()) continue;
                        if (g instanceof java.net.Inet4Address) { id = g.getHostAddress(); break; }
                        if (gw6 == null) gw6 = g;
                    }
                    if (id.length() == 0 && gw6 != null) id = gw6.getHostAddress();
                    if (id.length() == 0 && lp.getLinkAddresses() != null && !lp.getLinkAddresses().isEmpty()) {
                        id = lp.getLinkAddresses().get(0).getAddress().getHostAddress();
                    }
                }
                wifis.add(id.length() == 0 ? "?" : id);
            }
            if (wifis.isEmpty()) return "NO_WIFI";
            Collections.sort(wifis);
            StringBuilder sb = new StringBuilder("WIFI");
            for (String w : wifis) sb.append('|').append(w);
            return sb.toString();
        } catch (Throwable t) {
            return "ERR";
        }
    }

    private static String failStartMsg() {
        return "❌ 启动失败：v2rayNG 拒绝后台启动\n请给 v2rayNG 和本应用开『自启动 / 允许后台运行』权限";
    }

    // ---------------------------------------------------------------- start / stop

    /** 触发 v2rayNG 官方的 Tasker 插件广播（启动时会自带前台服务通知）。 */
    private static void fireSetting(Context ctx, String pkg, boolean start) {
        Intent i = new Intent(ACTION_FIRE_SETTING);
        i.setComponent(new ComponentName(pkg, pkg + ".receiver.TaskerReceiver"));
        Bundle b = new Bundle();
        b.putBoolean(EXTRA_SWITCH, start);
        b.putString(EXTRA_GUID, GUID_DEFAULT);
        i.putExtra(EXTRA_BUNDLE, b);
        i.putExtra("com.twofortyfouram.locale.intent.extra.BLURB", "activateVpn");
        ctx.sendBroadcast(i);
        Log.i(TAG, "FIRE_SETTING switch=" + start + " -> " + pkg);
    }

    private static boolean start(Context ctx, String pkg) {
        // 第一招：直接广播（后台也能起：v2rayNG 自己会 startForegroundService）
        fireSetting(ctx, pkg, true);
        if (waitRunning(ctx, WAIT_START_MS)) return true;

        // 第二招：把 v2rayNG 界面拉到前台再广播。应用可见时前台服务启动一定放行，
        // 可以绕过 Android 12+ / 各家 ROM 的后台启动限制。
        Log.w(TAG, "broadcast start failed, falling back to bringing v2rayNG to front");
        bringToFront(ctx, pkg);
        sleep(FALLBACK_UI_DELAY_MS);
        fireSetting(ctx, pkg, true);
        if (waitRunning(ctx, WAIT_START_MS)) return true;

        // 第三招：再来一次。万一第一次把正在运行的实例停掉了（v2rayNG 重复启动会先停后起），
        // 这一次是干净的启动。
        fireSetting(ctx, pkg, true);
        return waitRunning(ctx, WAIT_START_MS);
    }

    private static boolean stop(Context ctx, String pkg) {
        // 第一招：发给 v2rayNG 核心服务自己注册的停止入口（MSG_STATE_STOP）
        Intent i = new Intent(pkg + ".action.service");
        i.setPackage(pkg);
        i.putExtra("key", MSG_STATE_STOP);
        ctx.sendBroadcast(i);
        Log.i(TAG, "stop broadcast sent");
        if (waitStopped(ctx, WAIT_STOP_MS)) return true;

        // 第二招：走官方 Tasker 插件接口的 switch=false 分支（LauncherManager.stopService）
        Log.w(TAG, "service stop ineffective, falling back to FIRE_SETTING(false)");
        fireSetting(ctx, pkg, false);
        return waitStopped(ctx, WAIT_STOP_MS);
    }

    private static void bringToFront(Context ctx, String pkg) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(launch);
            Log.i(TAG, "launched v2rayNG UI");
        } catch (Throwable t) {
            Log.w(TAG, "launch failed: " + t);
        }
    }

    // ---------------------------------------------------------------- state probing

    /**
     * 判断代理是否在运行：
     *  1) 本机 socks 端口（默认 10808）能连上；
     *  2) 自己的默认网络是 VPN 传输（全局 VPN 模式下成立）。
     */
    public static boolean isRunning(Context ctx) {
        for (int p : SOCKS_PORTS) {
            if (probeLocalPort(p)) return true;
        }
        return hasVpnTransport(ctx);
    }

    private static boolean probeLocalPort(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean hasVpnTransport(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean waitRunning(Context ctx, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isRunning(ctx)) return true;
            sleep(250);
        }
        return isRunning(ctx);
    }

    private static boolean waitStopped(Context ctx, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(ctx)) return true;
            sleep(200);
        }
        return !isRunning(ctx);
    }

    // ---------------------------------------------------------------- misc

    private static String findV2rayPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : V2RAY_PKGS) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
