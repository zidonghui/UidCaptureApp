# UidCaptureApp

Android 本机 VPN 抓包工具，自动拦截游戏登录接口，提取 UID 和 Token。

## 功能

- 一键启动 VPN 抓包，无需 Root
- 自动拦截 `logs.quickapi.net/v2/users/checkLogin` 登录接口
- 自动解析 gzip 压缩响应，提取 UID / Token / 用户名
- 一键复制 UID 或 Token 到剪贴板
- 捕获成功后 Toast 弹窗 + 通知栏提醒

## 使用方法

1. 打开 App，点击「启动VPN抓包」
2. 授予 VPN 权限
3. 打开游戏，正常登录
4. 回到 App，UID / Token 自动显示
5. 点击「复制」按钮即可复制

## 技术架构

- VpnService + tun2http native 库转发流量
- LittleProxy 作为本地 HTTP 代理内核
- 拦截响应 → gzip 解压 → JSON 解析 → UI 回调

## 编译

GitHub Actions 自动编译，push 到 main 分支即可生成 APK。
