# activateVpn

**打开它 = 确保 v2rayNG 的代理处于开启状态**（不用再进 v2rayNG 里点那个开关）。
配套的还有一个 `deactivateVpn`（打开即关闭代理），见 `../deactivateVpn`。

给“离开家 WiFi 时自动开梯子”这类自动化用：把自动化里“打开 v2rayNG”换成“打开 activateVpn”。

## 它做了什么

v2rayNG 自带官方 Tasker/Locale 插件接口（`com.v2ray.ang/.receiver.TaskerReceiver`，`exported=true`）。activateVpn 打开时：

1. 探测代理是否已在运行（本机 `127.0.0.1:10808/10809` 端口 + 当前网络是否 VPN 传输）；
2. 已在运行 → 什么都不做（**不会**把你正在用的梯子重启）；
3. 没运行 → 发 `FIRE_SETTING` 广播让 v2rayNG 启动（服务器 = 当前选中的那个，guid `Default`）；
4. 8 秒内没起来 → 把 v2rayNG 界面拉到前台再发一次（应用可见时系统一定允许启动前台服务，绕开 Android 12+ 的后台限制）；
5. 还不行 → 再发一次（v2rayNG 对“重复启动”的处理是先停后起，第二次是干净启动）；
6. Toast 报结果，窗口透明、执行完立刻消失。

## 安装

```bash
cd /Users/gaofei/agent/switchvpn/activateVpn
./build.sh                      # 手工构建，不需要 Gradle；调用 ../vpncommon/build_apk.sh
adb install -r activateVpn.apk  # ⚠️ 安装时请点亮屏幕，否则 ColorOS 的安装确认框弹不出来会超时
```

## 使用

直接点桌面图标 `activateVpn`。也可以作为“保底”：自动化里保留“打开 v2rayNG”，再加一个“打开 activateVpn”（顺序：先 v2rayNG 后 activateVpn 更稳）。

### 无界面调用（可选）

```bash
adb shell am broadcast -a com.local.activatevpn.action.ON -n com.local.activatevpn/.StartReceiver
# 只查状态（logcat 里看结果）
adb shell am broadcast -a com.local.activatevpn.action.ON -n com.local.activatevpn/.StartReceiver --es mode status
adb shell am broadcast -a com.local.activatevpn.action.ON -n com.local.activatevpn/.StartReceiver --es mode stop
```

> 后台广播入口受 Android 12+ 前台服务限制影响，可能被系统拦掉；**从桌面/自动化“启动应用”那条路最稳**。广播失败时会自动降级为把 v2rayNG 拉到前台再起。
>
> 另：ColorOS 会把后台闲着的本应用冻结（logcat 里 `OplusHansManager ... try idling`），此时发给它的广播可能排队十几秒才执行。所以做自动化时**请用“打开应用”，不要用“发送广播”**。

## 必须给的权限（ColorOS / OxygenOS）

设置 → 电池 → 应用启动管理（或“应用 → 自启动/后台运行”）里，把下面两个都设为**允许自启动 + 允许后台运行**：

- `v2rayNG`
- `activateVpn`

再把两者加入“电池优化 → 不优化”，否则息屏久了系统会把它们冻住。

## 实测记录（OnePlus PJZ110 / Android 17 / v2rayNG 2.3.14）

| 场景 | 结果 |
| --- | --- |
| 代理已在运行，点 activateVpn | 不做任何事（v2rayNG service 的 createTime 不变，不重启、不断流） |
| 代理已关，点 activateVpn | ~0.3s 起来，无 UI 介入 |
| v2rayNG 被 force-stop（自动化“关闭程序”），点 activateVpn | ~0.5s 起来 |
| activateVpn 冷进程启动 | ~0.3s 起来 |
| 屏幕熄灭 / Doze 下触发 | 正常起来，且不会点亮屏幕 |

## 已知限制

- 状态探测默认端口 `10808`（v2rayNG 默认 socks 端口）。改过 v2rayNG 的 socks 端口时探测会失效 → 可能把正在用的连接断掉再重连；改 `../vpncommon/com/local/vpncommon/Controller.java` 里的 `SOCKS_PORTS` 即可。
- v2rayNG 对“重复启动”不幂等（重复调用会先停旧 tun 再建新的，短暂断流），所以本应用要先探测再启动。
- 只支持 v2rayNG（`com.v2ray.ang` / `com.v2ray.ang.fdroid`）。

## 目录

```
agent/switchvpn/
├── vpncommon/                    # 两个 app 共用
│   ├── com/local/vpncommon/Controller.java   # 核心逻辑（探测/启停）
│   ├── build_apk.sh                         # 通用构建脚本（aapt2→javac→d8→zipalign→apksigner）
│   ├── keystore.jks                         # 签名证书（口令 vpnswitch）
│   └── 调研笔记.md                          # v2rayNG 接口源码级调研 + 坑 + 实测
├── activateVpn/                  # 打开即开启（本文件所在目录）
│   ├── build.sh                  # → ../vpncommon/build_apk.sh
│   ├── activateVpn.apk
│   └── app/src/main/{AndroidManifest.xml,java/com/local/activatevpn/*}
└── deactivateVpn/                # 打开即关闭
```

调试：

```bash
adb logcat -s VpnSwitch:V
```
