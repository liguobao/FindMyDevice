# FindMyDevice

## English / 中文

This is a beta version of an Android app designed to help you locate and view information about your Apple devices.

这是一个Beta版本的Android应用，用于帮助您查看和定位您的Apple设备。

### Privacy / 隐私

No privacy permissions, does not store any user data.

无隐私权限，不存储用户任何数据。

### Features / 功能

- View your Apple devices - 查看您的Apple设备
- Locate devices on a map - 在地图上定位设备
- Get device status and details - 获取设备状态和详细信息

### Installation / 安装

- Clone the repository - 克隆仓库
- Open in Android Studio - 在Android Studio中打开
- Build and run on your device - 构建并在设备上运行

### Build / 编译

To build the app using command line - 使用命令行构建应用：

```bash
./gradlew assembleDebug
```

To build a release APK - 构建 release APK：
```bash
./gradlew assembleRelease
```

Note - 注意：
- If you build release **without** a keystore, Gradle will output `app-release-unsigned.apk`, which **cannot be installed** on devices (MIUI/HyperOS may show “解析软件包时出现问题(33) / packageInfo is null”).
- For local testing/distribution without a release keystore, use the debug-signed release variant:

```bash
./gradlew assembleLocalRelease
```

To install the app - 安装应用：

```bash
./gradlew installDebug
```
