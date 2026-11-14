# deactivateVpn

**打开它 = 确保 v2rayNG 的代理处于关闭状态**。配套的还有 `activateVpn`（打开即开启），见 `../activateVpn`。

给“连上家里 WiFi 时自动关梯子”这类自动化用：把自动化里“关闭 v2rayNG / 强行停止应用”换成“打开 deactivateVpn”——这样就不用把 v2rayNG 整个杀掉了，只停代理。

## 它做了什么

1. 探测代理是否在运行（`127.0.0.1:10808/10809` 端口 + 当前网络是否 VPN 传输）；
2. 没在运行 → 直接报“代理本来就没在运行”，什么都不做；
3. 在运行 → 给 v2rayNG 核心服务发它的停止指令（`com.v2ray.ang.action.service` + `key=4` / `MSG_STATE_STOP`）；
4. 4 秒内没停下来 → 退一步走官方 Tasker 插件接口的 `switch=false` 分支（`LauncherManager.stopService`）；
5. Toast 报结果。窗口透明，执行完立刻消失。

## 安装

```bash
cd /Users/gaofei/agent/switchvpn/deactivateVpn
./build.sh                       # 手工构建，不需要 Gradle；调用 ../vpncommon/build_apk.sh
adb install -r deactivateVpn.apk # ⚠️ 安装时请点亮屏幕，否则 ColorOS 的安装确认框弹不出来会超时
```

## 使用

直接点桌面图标 `deactivateVpn`。自动化里：连上家里 WiFi → 打开 deactivateVpn。

顺带提醒：如果本来那条“关闭 v2rayNG”是“强行停止应用”，换成 deactivateVpn 更好——强行停止会让 v2rayNG 的进程/界面都没了，而 deactivateVpn 只停代理，下次开启更快、也不会影响 v2rayNG 的状态。

### 无界面调用（可选）

```bash
adb shell am broadcast -a com.local.deactivatevpn.action.OFF -n com.local.deactivatevpn/.StartReceiver
adb shell am broadcast -a com.local.deactivatevpn.action.OFF -n com.local.deactivatevpn/.StartReceiver --es mode status
```

> 同 activateVpn：ColorOS 冻结后台应用时广播可能排队十几秒，**自动化请用“打开应用”**。

## 权限

跟 activateVpn 一样，给 `v2rayNG` 和 `deactivateVpn` 开自启动 + 后台运行 + 电池不优化。

## 实测记录（OnePlus PJZ110 / Android 17 / v2rayNG 2.3.14）

| 场景 | 结果 |
| --- | --- |
| 代理在运行，点 deactivateVpn | 发停止指令后端口立刻关闭 ✅ |
| 代理已关，点 deactivateVpn | 报“代理本来就没在运行”，无副作用 ✅ |

## 目录 / 调试

代码在 `app/src/main/java/com/local/deactivatevpn/`，核心逻辑与 activateVpn 共用 `../vpncommon/com/local/vpncommon/Controller.java`。

```bash
adb logcat -s VpnSwitch:V
```
