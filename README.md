# RC控制器应用

一款用于通过CRSF协议控制RC设备的安卓应用程序。该应用提供了直观的操纵杆控制、陀螺仪飞行控制以及可配置的通道开关，带来全面的遥控体验。

## 功能特性

- **双操纵杆控制**: 左右操纵杆实现传统遥控风格的控制
- **陀螺仪飞行模式**: 使用手机陀螺仪进行沉浸式飞行控制
- **多通道开关**: CH6、CH7和CH8三位开关，带颜色编码的位置指示
- **横屏与竖屏模式**: 支持两种方向的自适应用户界面
- **USB串口通信**: 通过USB直接连接RC硬件，支持CH34x驱动
- **实时遥测**: 显示通道值和传感器数据以供调试

## 设置与安装

### 系统要求
- 支持USB OTG的安卓设备
- 兼容支持CRSF协议的RC接收机
- 连接RC硬件的USB线缆

### 安装步骤
1. 克隆或下载项目源代码
2. 在Android Studio中打开项目
3. 构建并将应用安装到安卓设备上
4. 在设备上启用开发者选项和USB调试
5. 通过USB连接RC硬件

## 使用方法

### 基础控制
- **连接按钮**: 通过USB串口建立与RC硬件的连接
- **使用陀螺仪按钮**: 在操纵杆和陀螺仪控制模式之间切换
- **竖屏/横屏切换**: 在屏幕方向之间切换
- **解锁开关**: 解锁/锁定RC系统
- **CH6/CH7/CH8开关**: 三位开关辅助功能（颜色指示位置：红色=低，橙色=中，绿色=高）

### 飞行模式
- **操纵杆模式**: 使用屏幕操纵杆进行传统遥控控制
- **陀螺仪模式**: 倾斜手机来控制飞机（点击"使用陀螺仪"按钮激活）

### 通道映射
- CH1: 横滚（左操纵杆X轴）
- CH2: 俯仰（左操纵杆Y轴）
- CH3: 油门（右操纵杆Y轴）
- CH4: 偏航（右操纵杆X轴）
- CH5: 解锁/锁定
- CH6: 三位开关
- CH7: 三位开关
- CH8: 三位开关

## 项目结构

```
app/
├── src/main/java/com/example/myapplication/    # 主应用程序代码
│   └── MainActivity.kt                         # 主应用程序逻辑
├── src/main/java/                              # 自定义组件
│   ├── Joystick.kt                             # 自定义操纵杆视图
│   └── CRSFData.kt                             # CRSF协议实现
├── src/main/res/                               # 资源（布局、图像等）
└── src/main/AndroidManifest.xml                # 应用清单
ch34x/                                          # USB驱动模块
```

## 自定义

### 添加新的通道开关
应用使用通用的ThreePositionSwitch类，可以轻松添加额外的开关：
1. 在布局文件中添加新按钮
2. 在MainActivity中创建新的ThreePositionSwitch实例
3. 将其映射到相应的CRSF数据数组索引

### 操纵杆配置
使用布局文件中的XML属性来自定义操纵杆灵敏度和外观。

## 故障排除

- **连接问题**: 确保已授予USB权限且CH34x驱动正确配置
- **陀螺仪无响应**: 检查是否已授予陀螺仪权限
- **控制无效**: 验证CRSF协议设置是否匹配您的RC硬件

## 贡献

欢迎贡献！请随时提交拉取请求。对于重大更改，请先开issue讨论您想要更改的内容。

## 许可证

本项目根据GNU通用公共许可证第3版（GPL-3.0）获得许可 - 详见[LICENSE](LICENSE)文件。

---

# RC Controller App

An Android application designed for controlling RC aircraft and other devices using the CRSF (Crossfire) protocol. The app provides intuitive joystick controls, gyroscope-based flight control, and configurable channel switches for a comprehensive RC experience.

## Features

- **Dual Joystick Control**: Left and right joysticks for traditional RC-style control
- **Gyroscope Flight Mode**: Use phone's gyroscope for immersive flight control
- **Multiple Channel Switches**: Three-position switches for CH6, CH7, and CH8 with color-coded positions
- **Portrait & Landscape Modes**: Adaptable UI supporting both orientations
- **USB Serial Communication**: Direct connection to RC hardware via USB with CH34x driver support
- **Real-time Telemetry**: Display of channel values and sensor data for debugging

## Setup & Installation

### Prerequisites
- Android device with USB OTG support
- Compatible RC receiver that supports CRSF protocol
- USB cable for connecting to RC hardware

### Installation Steps
1. Clone or download the project source code
2. Open the project in Android Studio
3. Build and install the app on your Android device
4. Enable developer options and USB debugging on your device
5. Connect your RC hardware via USB

## Usage

### Basic Controls
- **Connect Button**: Establishes connection to RC hardware via USB serial
- **Use Gyro Button**: Toggles between joystick and gyroscope control modes
- **Portrait/Landscape Toggle**: Switch between screen orientations
- **Arm Switch**: Arms/disarms the RC system
- **CH6/CH7/CH8 Switches**: Three-position switches for auxiliary functions (colors indicate position: red=LOW, orange=MIDDLE, green=HIGH)

### Flight Modes
- **Joystick Mode**: Traditional RC control using on-screen joysticks
- **Gyroscope Mode**: Tilt your phone to control the aircraft (activate with "Use Gyro" button)

### Channel Mapping
- CH1: Roll (left joystick X-axis)
- CH2: Pitch (left joystick Y-axis)
- CH3: Throttle (right joystick Y-axis)
- CH4: Yaw (right joystick X-axis)
- CH5: Arm/Disarm
- CH6: Three-position switch
- CH7: Three-position switch
- CH8: Three-position switch

## Project Structure

```
app/
├── src/main/java/com/example/myapplication/    # Main application code
│   └── MainActivity.kt                         # Main application logic
├── src/main/java/                              # Custom components
│   ├── Joystick.kt                             # Custom joystick view
│   └── CRSFData.kt                             # CRSF protocol implementation
├── src/main/res/                               # Resources (layouts, drawables, etc.)
└── src/main/AndroidManifest.xml                # App manifest
ch34x/                                          # USB driver module
```

## Customization

### Adding New Channel Switches
The app uses a generic ThreePositionSwitch class that makes it easy to add additional switches:
1. Add a new button in the layout files
2. Create a new ThreePositionSwitch instance in MainActivity
3. Map it to the appropriate CRSF data array index

### Joystick Configuration
Customize joystick sensitivity and appearance using XML attributes in the layout files.

## Troubleshooting

- **Connection Issues**: Ensure USB permissions are granted and CH34x driver is properly configured
- **Gyroscope Not Responding**: Check that gyroscope permissions are granted
- **Controls Not Working**: Verify CRSF protocol settings match your RC hardware

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0) - see the [LICENSE](LICENSE) file for details.