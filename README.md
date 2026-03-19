# Qwen Chat Android 壳应用

这是一个将 `https://chat.qwen.ai/` 封装为安卓应用的轻量项目，应用名称为 `Qwen Chat`。  
项目基于原生 Android `WebView` 实现，不依赖第三方 Android UI 框架

## 项目特点

- 使用原生 `WebView` 加载 Qwen Chat 网页
- 优先使用设备真实 `WebView User-Agent`，提升页面兼容性
- 保留登录态，支持 Cookie 持久化
- 启动时批量申请麦克风、相机等运行时权限，供网页后续直接使用
- 支持文件选择、下载、基础多窗口跳转
- 支持网页直接调用拍照、录像、录音等系统能力
- 使用本地脚本直接构建 APK，不依赖 Gradle 在线下载依赖

## 目录结构

```text
qwen-chat-app/
├── AndroidManifest.xml
├── README.md
├── .gitignore
├── build.sh
├── assets-src/
│   └── favicon.png
├── res/
│   └── mipmap-xxxhdpi/
│       ├── ic_launcher.png
│       └── ic_launcher_round.png
└── src/
    └── com/codex/qwenchat/
        ├── CaptureFileProvider.java
        ├── MainActivity.java
```

## 环境要求

- Linux / macOS Shell 环境
- JDK 17 或更高版本
- Android SDK
- 已安装以下 Android SDK 组件：
  - `platforms;android-34`
  - `build-tools;34.0.0`

默认脚本会优先读取环境变量 `ANDROID_SDK_ROOT`，如果未设置，则回退到 `/opt/android-sdk`。

## 核心实现说明

### 1. 页面加载

应用主入口位于 `MainActivity.java`，主要负责：

- 创建 `WebView`
- 设置兼容性更好的 `User-Agent`
- 开启 JavaScript、DOM Storage、Cookie、第三方 Cookie
- 处理运行时权限、返回键、文件上传、下载、弹窗跳转

### 2. 登录状态保留

登录状态依赖 `WebView` 的 Cookie 持久化。  
首次登录后，只要用户不清除应用数据，通常再次打开应用仍可保持登录状态。

## 如何构建 APK

在项目根目录执行：

```bash
./build.sh
```

构建完成后，APK 默认输出到：

```text
dist/QwenChat.apk
```

首次构建时，如果项目目录下不存在 `debug.keystore`，脚本会自动生成一个调试签名证书用于打包。



## 注意事项

- 该项目封装的是第三方网页服务，页面行为可能会随着对方站点更新而变化
- 如果站点修改了移动端检测逻辑，可能需要重新调整 `User-Agent` 或 `WebView` 配置
- 如果你覆盖安装旧版本后页面表现异常，建议先清除应用数据再测试
- 当前构建脚本生成的是调试签名 APK，如需正式分发，建议替换为你自己的发布签名

## 免责声明

本项目仅为网页封装示例与个人使用场景准备，不代表 Qwen 官方发布。  
站点名称、图标及相关资源归其各自权利方所有。
