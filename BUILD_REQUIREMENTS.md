# LetsFly 编译环境要求

## 项目配置分析

根据项目的 Gradle 配置文件分析，编译此项目需要以下环境：

### Android SDK 配置
- **Compile SDK Version**: 33 (Android 13)
- **Target SDK Version**: 33
- **Min SDK Version**: 21 (Android 5.0)
- **Build Tools**: 33.0.0 或更高

### Gradle 配置
- **Gradle 版本**: 7.0.2
- **Android Gradle Plugin**: 7.0.0
- **Kotlin 版本**: 1.6.21

### JDK 要求
- **JDK 版本**: 11 或更高 (推荐 JDK 11)
  - Gradle 7.0.2 需要 JDK 11+
  - Java 1.8 编译目标 (项目使用 Java 8 特性)

## 项目模块
1. **app**: 主应用模块
2. **ch34x**: CH34x USB 串口驱动库模块

## 快速开始

### 自动安装 (推荐)

运行提供的自动安装脚本：

```bash
./setup-android-env.sh
```

脚本会自动安装：
- OpenJDK 11
- Android Command Line Tools
- Android SDK Platform 33
- Android Build Tools 33.0.0
- Platform Tools

### 手动安装

#### 1. 安装 JDK 11

```bash
sudo apt update
sudo apt install openjdk-11-jdk
```

验证安装：
```bash
java -version
```

#### 2. 下载 Android Command Line Tools

```bash
# 创建 SDK 目录
mkdir -p ~/android-sdk

# 下载 Command Line Tools
cd ~
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip

# 解压
unzip commandlinetools-linux-9477386_latest.zip -d ~/android-sdk
mkdir -p ~/android-sdk/cmdline-tools/latest
mv ~/android-sdk/cmdline-tools/{bin,lib} ~/android-sdk/cmdline-tools/latest/
```

#### 3. 配置环境变量

编辑 `~/.bashrc`:

```bash
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

应用配置：
```bash
source ~/.bashrc
```

#### 4. 安装 SDK 组件

```bash
# 接受许可证
yes | sdkmanager --licenses

# 安装必要组件
sdkmanager "platform-tools"
sdkmanager "platforms;android-33"
sdkmanager "build-tools;33.0.0"
```

## 编译项目

### 构建 Debug 版本

```bash
./gradlew assembleDebug
```

生成的 APK: `app/build/outputs/apk/debug/app-debug.apk`

### 构建 Release 版本

```bash
./gradlew assembleRelease
```

生成的 APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

### 清理构建

```bash
./gradlew clean
```

### 查看所有可用任务

```bash
./gradlew tasks
```

## 常见问题

### Gradle Wrapper 权限问题

如果遇到 `gradlew` 权限问题：
```bash
chmod +x gradlew
```

### SDK 许可证未接受

```bash
yes | sdkmanager --licenses
```

### 内存不足

编辑 `gradle.properties`，调整 JVM 参数：
```
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### 网络问题

如果下载 SDK 或依赖时遇到网络问题，可以配置代理或使用国内镜像源。

在 `build.gradle` 中添加阿里云镜像：
```gradle
repositories {
    maven { url 'https://maven.aliyun.com/repository/google' }
    maven { url 'https://maven.aliyun.com/repository/public' }
    google()
    mavenCentral()
}
```

## 依赖项

项目使用的主要依赖：
- AndroidX Core KTX 1.3.2
- AndroidX AppCompat 1.2.0/1.6.1
- Material Components 1.3.0
- ConstraintLayout 2.0.4
- AndroidRocker 1.0.1 (虚拟摇杆)
- CH34x USB 串口驱动 (子模块)

## 系统要求

- **操作系统**: Linux (Ubuntu/Debian 或其他发行版)
- **磁盘空间**: 至少 5GB (用于 SDK 和构建缓存)
- **内存**: 建议 4GB 或更多
