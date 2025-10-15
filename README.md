<p align="center">
  <img src="app/src/main/res/drawable/shiba_logo.png" alt="ShibaMusic Icon" width="160" />
</p>
<h1 align="center">ShibaSync-Android</h1>

A real-time audio streaming receiver application for Android, built with Kotlin and Jetpack Compose with native C++ audio processing for high-performance, low-latency audio playback.

## 🎯 Key Features

**ShibaSync-Android** is the companion mobile application for ShibaSync-Windows that receives and plays streamed audio with minimal latency. The application offers:

- ✅ **Real-time audio streaming** via WebSocket/Socket.IO
- ✅ **Native audio processing** using Oboe library for low-latency playback
- ✅ **Modern Material Design 3** interface with Jetpack Compose
- ✅ **Background audio support** with MediaSession integration
- ✅ **ARM64 optimized** native libraries for best performance
- ✅ **Zero-configuration pairing** with ShibaSync-Windows

## 🔧 Technologies

- **Kotlin** - Primary development language
- **Jetpack Compose** - Modern UI toolkit
- **Material Design 3** - Google's latest design system
- **Oboe** - High-performance audio library for Android
- **Socket.IO Client** - Real-time communication with server
- **NDK/CMake** - Native C++ audio processing
- **MediaSession** - Background audio controls

## 📋 Prerequisites

- **Android Studio** Arctic Fox or newer
- **Android SDK** API Level 26+ (Android 8.0)
- **NDK** r21 or newer
- **CMake** 3.18+
- **Target Device**: Android 8.0+ with ARM64 architecture

## 🚀 Installation

### 1. Clone the repository
```bash
git clone https://github.com/ShiromiyaG/ShibaSync-Android.git
cd ShibaSync-Android
```

### 2. Open in Android Studio
- Launch Android Studio
- Open the project folder
- Wait for Gradle sync to complete

### 3. Build native libraries (optional)
```bash
./gradlew build
```

### 4. Run on device/emulator

- Connect Android device or start emulator
- Click "Run" in Android Studio or use:
```bash
./gradlew installDebug
```

## 📦 Build and Distribution

### Debug build
```bash
./gradlew assembleDebug
```

### Release build
```bash
./gradlew assembleRelease
```

The APK will be generated in `app/build/outputs/apk/`

## 🎮 How to Use

### Setting up Connection
1. Install and open ShibaSync-Android
2. Start ShibaSync-Windows on your PC
3. Enter the server IP address in the Android app
4. Tap "Connect" to establish audio streaming
5. Audio from your PC will play through your Android device

### Interface Features
- **Connection status**: Visual indicator of streaming connection
- **Audio controls**: Play/pause, volume control
- **Server settings**: Configure IP address and port
- **Background mode**: Continue playback when app is minimized
- **Media notifications**: System-level audio controls

## 🔧 Advanced Configuration

### Audio Parameters
- **Sample Rate**: 48 kHz (matches Windows app)
- **Format**: 16-bit PCM
- **Channels**: Stereo
- **Buffer Size**: Optimized for low latency using Oboe

### Network Configuration
The application connects to:
- WebSocket server running on ShibaSync-Windows

## 🛠 Development

### Project Structure
```bash
ShibaSync-Android/
├── app/
│ ├── src/main/
│ │ ├── java/com/shirou/shibasync/ # Kotlin source code
│ │ ├── cpp/ # Native C++ audio processing
│ │ ├── res/ # Resources and layouts
│ │ └── AndroidManifest.xml # App configuration
│ ├── build.gradle # App-level Gradle config
│ └── proguard-rules.pro # ProGuard configuration
├── build.gradle # Project-level Gradle config
├── settings.gradle # Gradle settings
└── build_native.ps1 # Native build script
```

### Key Components
- **MainActivity**: Main Compose UI and audio streaming logic
- **AudioService**: Background audio processing service
- **Native Audio Engine**: C++ Oboe-based audio playback
- **Socket Manager**: WebSocket/Socket.IO communication

### Build Configuration
- **Target SDK**: 33 (Android 13)
- **Min SDK**: 26 (Android 8.0)
- **Architecture**: ARM64 only (arm64-v8a)
- **C++ Standard**: C++17

### Development Commands
```bash
Clean build
./gradlew clean

Debug build
./gradlew assembleDebug

Run tests
./gradlew test

Generate signed APK
./gradlew assembleRelease
```

## 🤝 Contributing

Contributions are welcome! To contribute:

1. Fork the project
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

## 👨‍💻 Author

**Shirou** - [@ShiromiyaG](https://github.com/ShiromiyaG)

## 🐛 Known Issues

- Requires ARM64 device for optimal performance
- Some devices may experience audio latency depending on hardware
- Background audio may be limited by device power management settings
- Socket.IO connection may timeout on poor network conditions

## 📞 Support

If you encounter issues:
1. Verify your Android device is ARM64 and running Android 8.0+
2. Check network connectivity between Android device and Windows PC
3. Ensure ShibaSync-Windows server is running and accessible
4. Open an [issue](https://github.com/ShiromiyaG/ShibaSync-Android/issues) on GitHub

## 🔗 Related Projects

- [ShibaSync-Windows](https://github.com/ShiromiyaG/ShibaSync-Windows) - Windows audio capture application
