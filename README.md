# switchvpn

给“连上/离开某个 WiFi 时自动开/关梯子”的自动化用的两个极简 Android 小应用（v2rayNG 专用，无需 root，不改 v2rayNG）。

| 应用 | 包名 | 作用 |
| --- | --- | --- |
| `activateVpn` | `com.local.activatevpn` | 打开它 = 确保 v2rayNG 代理**开启**（已在运行则不打扰） |
| `deactivateVpn` | `com.local.deactivatevpn` | 打开它 = 确保 v2rayNG 代理**关闭**（没运行则什么都不做） |

## 5 秒防抖（网络抖动不动作）

两个 App 被拉起后**不会立刻动代理**，而是：

1. 记下当时的「WiFi 指纹」（如 `WIFI|192.168.2.1`）和动作，排一个 5 秒后的 Job，自己立刻退出（**全程不显示任何界面、不弹 Toast**，见下一节）；
2. 5 秒到点再比对指纹：期间 WiFi 变了（离开又连上、连上又断）→ **放弃本次，不动作**；
3. 期间又来了新的触发（App 被再次拉起）→ 老的那次自动作废，按最新一次重新计时（nonce 判定）。

实测（Android 17 / ColorOS）：

| 场景 | 结果 |
| --- | --- |
| 息屏，触发后在 10.01 秒执行 | ✅ 准时 |
| 息屏 + 5 秒内 WiFi 关闭又恢复 | ✅ `net=NO_WIFI -> WIFI|192.168.2.1` → 放弃，代理未动 |
| 5 秒内被再次触发 | ✅ 老的 waiter `stale waiter ... ignore`，按新的重新计时 |

⚠️ 前提：必须给这两个 App 开「允许完全后台运行」（设置 → 电池 → 应用耗电管理），否则 ColorOS 会把刚退到后台的进程丢进 freezer 冻结（实测 `cgroup.freeze=1`，连前台服务都挡不住）、把闹钟推迟到 +2 天，等待会被打断。

## 零屏幕干扰

主界面用的是 `@android:style/Theme.NoDisplay`：`onCreate` 里登记完动作就 `finish()`，**不创建任何窗口**，也没有 Toast（结果只写 logcat，tag `VpnSwitch`）。所以从自动化里拉起时屏幕不会闪、不会抢触控焦点。

```bash
adb logcat -s VpnSwitch     # 看执行记录（用户看不到，只有调试时用）
```

## 为什么不是“一个 App + 参数”

试过单包方案（extra `mode=start|stop`、`vpnswitch://start`、action `...ON/OFF`），但 **OPPO 的「自动指令」只会按包名调 `getLaunchIntentForPackage()` 拉起应用**（反编译 `com.coloros.shortcuts` 可见该调用），无法携带 extra / data，也就不可能区分开与关。因此保留两个包，各自对应一个图标，自动化里分别指向它们即可。

单次调用可以覆盖等待时间：`am start -n com.local.activatevpn/.MainActivity --ei settle 0`（0 = 立即执行）。

原理：v2rayNG 自带官方 Tasker/Locale 插件广播（`com.v2ray.ang/.receiver.TaskerReceiver`，`exported=true`），这两个 App 就是把它包装成“打开即执行”的图标。详见 `vpncommon/调研笔记.md`。

## 构建 / 安装

```bash
cd /Users/gaofei/agent/switchvpn
./activateVpn/build.sh          # 产物 activateVpn/activateVpn.apk
./deactivateVpn/build.sh        # 产物 deactivateVpn/deactivateVpn.apk

adb install -r activateVpn/activateVpn.apk     # ⚠️ 安装时点亮屏幕，否则确认框弹不出来会超时
adb install -r deactivateVpn/deactivateVpn.apk
```

## 自动化怎么配

- 连上家里 WiFi → 打开 **deactivateVpn**
- 离开家里 WiFi → 打开 **activateVpn**

（不要用“关闭程序/强行停止 v2rayNG”，也不需要“打开 v2rayNG”那一步；不要用发广播的方式触发本应用——ColorOS 冻结后台应用会让广播延迟十几秒。）

权限：给 `v2rayNG`、`activateVpn`、`deactivateVpn` 都开「自启动 + 允许后台运行 + 电池不优化」。

## 目录

```
switchvpn/
├── vpncommon/
│   ├── com/local/vpncommon/Controller.java   # 共用核心逻辑（状态探测 / 启 / 停 / 5 秒防抖）
│   ├── com/local/vpncommon/SettleJobService.java  # 防抖到点后由系统唤醒执行的 Job
│   ├── build_apk.sh                          # 通用构建脚本 aapt2→javac→d8→zipalign→apksigner
│   ├── keystore.jks                          # 签名（口令 vpnswitch）
│   └── 调研笔记.md                           # v2rayNG 接口源码级调研、坑、实测记录
├── activateVpn/    …/app/src/main/{AndroidManifest.xml,java/com/local/activatevpn/*}
└── deactivateVpn/  …/app/src/main/{AndroidManifest.xml,java/com/local/deactivatevpn/*}
```

调试：`adb logcat -s VpnSwitch:V`
