#!/bin/bash

# LetsFly Android 编译环境安装脚本
# 此脚本会安装编译当前项目所需的所有依赖

set -e  # 遇到错误立即退出

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}LetsFly Android 编译环境安装脚本${NC}"
echo -e "${GREEN}========================================${NC}"

# 项目需求 (从 build.gradle 分析得出)
# - compileSdk: 33
# - buildTools: 默认(通常随SDK安装)
# - Gradle: 7.0.2
# - JDK: 11 (兼容 Gradle 7.0.2)
# - Android Gradle Plugin: 7.0.0
# - Kotlin: 1.6.21

ANDROID_SDK_DIR="$HOME/android-sdk"
ANDROID_COMPILE_SDK=33
ANDROID_BUILD_TOOLS="33.0.0"
ANDROID_PLATFORM="android-33"

# 1. 检查并安装 JDK 11
echo -e "\n${YELLOW}[1/5] 检查 JDK...${NC}"
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    echo -e "${GREEN}✓ 已安装 JDK, 版本: $JAVA_VERSION${NC}"
    if [ "$JAVA_VERSION" -lt 11 ]; then
        echo -e "${YELLOW}警告: JDK 版本过低 (需要 >= 11)，正在安装 JDK 11...${NC}"
        sudo apt update
        sudo apt install -y openjdk-11-jdk
    fi
else
    echo -e "${YELLOW}未检测到 JDK，正在安装 JDK 11...${NC}"
    sudo apt update
    sudo apt install -y openjdk-11-jdk
fi

# 2. 下载 Android Command Line Tools
echo -e "\n${YELLOW}[2/5] 下载 Android Command Line Tools...${NC}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"
CMDLINE_TOOLS_ZIP="$HOME/commandlinetools.zip"

if [ ! -d "$ANDROID_SDK_DIR" ]; then
    mkdir -p "$ANDROID_SDK_DIR"
fi

if [ ! -d "$ANDROID_SDK_DIR/cmdline-tools/latest" ]; then
    echo -e "${YELLOW}正在下载 Android Command Line Tools...${NC}"
    wget -O "$CMDLINE_TOOLS_ZIP" "$CMDLINE_TOOLS_URL"

    echo -e "${YELLOW}正在解压...${NC}"
    unzip -q "$CMDLINE_TOOLS_ZIP" -d "$ANDROID_SDK_DIR"

    # 移动到正确的目录结构
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools/latest"
    mv "$ANDROID_SDK_DIR/cmdline-tools/bin" "$ANDROID_SDK_DIR/cmdline-tools/latest/"
    mv "$ANDROID_SDK_DIR/cmdline-tools/lib" "$ANDROID_SDK_DIR/cmdline-tools/latest/"

    # 如果还有其他文件，也移动过去
    if [ -d "$ANDROID_SDK_DIR/cmdline-tools/source.properties" ]; then
        mv "$ANDROID_SDK_DIR/cmdline-tools/source.properties" "$ANDROID_SDK_DIR/cmdline-tools/latest/"
    fi
    if [ -d "$ANDROID_SDK_DIR/cmdline-tools/NOTICE.txt" ]; then
        mv "$ANDROID_SDK_DIR/cmdline-tools/NOTICE.txt" "$ANDROID_SDK_DIR/cmdline-tools/latest/"
    fi

    rm "$CMDLINE_TOOLS_ZIP"
    echo -e "${GREEN}✓ Android Command Line Tools 安装完成${NC}"
else
    echo -e "${GREEN}✓ Android Command Line Tools 已存在${NC}"
fi

# 3. 设置环境变量
echo -e "\n${YELLOW}[3/5] 配置环境变量...${NC}"
export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export PATH="$PATH:$ANDROID_HOME/emulator"

# 写入到 ~/.bashrc (如果还没有)
if ! grep -q "ANDROID_HOME" "$HOME/.bashrc"; then
    echo "" >> "$HOME/.bashrc"
    echo "# Android SDK" >> "$HOME/.bashrc"
    echo "export ANDROID_HOME=\"$ANDROID_SDK_DIR\"" >> "$HOME/.bashrc"
    echo "export PATH=\"\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin\"" >> "$HOME/.bashrc"
    echo "export PATH=\"\$PATH:\$ANDROID_HOME/platform-tools\"" >> "$HOME/.bashrc"
    echo "export PATH=\"\$PATH:\$ANDROID_HOME/emulator\"" >> "$HOME/.bashrc"
    echo -e "${GREEN}✓ 环境变量已添加到 ~/.bashrc${NC}"
else
    echo -e "${GREEN}✓ 环境变量已存在于 ~/.bashrc${NC}"
fi

# 4. 安装必要的 Android SDK 组件
echo -e "\n${YELLOW}[4/5] 安装 Android SDK 组件...${NC}"
echo -e "${YELLOW}这可能需要几分钟时间...${NC}"

# 接受许可证
yes | sdkmanager --licenses > /dev/null 2>&1 || true

# 安装必需组件
echo -e "${YELLOW}安装 platform-tools...${NC}"
sdkmanager "platform-tools"

echo -e "${YELLOW}安装 platforms;${ANDROID_PLATFORM}...${NC}"
sdkmanager "platforms;${ANDROID_PLATFORM}"

echo -e "${YELLOW}安装 build-tools;${ANDROID_BUILD_TOOLS}...${NC}"
sdkmanager "build-tools;${ANDROID_BUILD_TOOLS}"

echo -e "${GREEN}✓ Android SDK 组件安装完成${NC}"

# 5. 验证安装
echo -e "\n${YELLOW}[5/5] 验证安装...${NC}"

echo -e "\n${GREEN}Java 版本:${NC}"
java -version

echo -e "\n${GREEN}已安装的 SDK 组件:${NC}"
sdkmanager --list | grep -E "platforms;android-|build-tools;" | grep "Installed"

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}环境安装完成！${NC}"
echo -e "${GREEN}========================================${NC}"

echo -e "\n${YELLOW}接下来的步骤:${NC}"
echo -e "1. 重启终端或运行: ${GREEN}source ~/.bashrc${NC}"
echo -e "2. 进入项目目录: ${GREEN}cd /home/ncer/LetsFly${NC}"
echo -e "3. 编译项目:"
echo -e "   - Debug 版本: ${GREEN}./gradlew assembleDebug${NC}"
echo -e "   - Release 版本: ${GREEN}./gradlew assembleRelease${NC}"
echo -e "4. 生成的 APK 位于: ${GREEN}app/build/outputs/apk/${NC}"

echo -e "\n${YELLOW}其他有用的命令:${NC}"
echo -e "  - 清理构建: ${GREEN}./gradlew clean${NC}"
echo -e "  - 查看任务: ${GREEN}./gradlew tasks${NC}"
echo -e "  - 运行测试: ${GREEN}./gradlew test${NC}"
