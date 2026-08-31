# Usque Android

<div align="center">

🚀 **High-Performance, Battery-Efficient Cloudflare WARP / MASQUE Client for Android**

[![Build and Release Android APK](https://github.com/cryptic-noodle/usque-android/actions/workflows/build-android-release.yml/badge.svg)](https://github.com/cryptic-noodle/usque-android/actions/workflows/build-android-release.yml)
[![GitHub release](https://img.shields.io/github/v/release/cryptic-noodle/usque-android?include_prereleases&style=flat-square)](https://github.com/cryptic-noodle/usque-android/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)

*Based on [Usque](README-USQUE.md) by [@Diniboy1123](https://github.com/Diniboy1123/usque) & maintained by [@cryptic-noodle](https://github.com/cryptic-noodle).*

</div>

---

## 📖 Overview

**Usque Android** is a native Android VPN application powered by the modern **MASQUE (Connect-IP / RFC 9484)** protocol connecting directly to Cloudflare's WARP infrastructure.

Designed specifically for **ultra-low latency**, **censorship circumvention**, and **extreme battery efficiency**, Usque Android rivals and outperforms official WireGuard-based clients by operating directly at the Linux kernel packet interface.

---

## ✨ Features & Highlights

### ⚡ Extreme Battery & Speed Optimizations
* **Zero-JNI Kernel Packet Streaming**: Direct Linux `/dev/tun` file descriptor access in Go. Eliminates JVM GC pauses, memory allocations, and JNI overhead during active network streaming.
* **QUIC (HTTP/3) Path MTU Discovery (PMTUD)**: Automatic packet sizing prevents fragmentation drops across mobile carriers and Wi-Fi networks.
* **Low-Power Foreground Service**: Optimized Android service with `IMPORTANCE_LOW` notifications to prevent unnecessary wakelocks and battery drain.

### 🛡️ Censorship Circumvention & Protocol Modes
* **Custom SNI (Server Name Indication)**: Configure custom TLS SNI (defaults to `api.cloudflare.com`) to bypass deep packet inspection (DPI) and domain-based throttling.
* **Custom Endpoints**: Connect to custom Cloudflare IPv4 or IPv6 edge node endpoints (e.g. `162.159.198.1:443` or `[2606:4700:103::1]:443`).
* **HTTP/2 (TCP) & QUIC (HTTP/3) Transports**: Switch between QUIC over UDP and HTTP/2 over TCP with a single toggle.

### 🌐 DNS Customization
* **Configurable DNS Servers**: Set custom primary and secondary DNS resolvers (e.g. `1.1.1.1, 1.0.0.1`, `8.8.8.8, 8.8.4.4`, `9.9.9.9`, or AdGuard DNS for ad-blocking).
* **Dual Stack IPv4 & IPv6**: Full IPv4 (`0.0.0.0/0`) and IPv6 (`::/0`) routing support.

### 📱 Premium Native Android Experience
* **Ongoing Status Notification with 1-Tap Disconnect**: Manage tunnel connection without opening the app.
* **Live In-App Log Viewer**: Real-time debounced log streaming with clipboard copying and adjustable log levels (`DEBUG`, `INFO`, `WARN`, `ERROR`).
* **Fail-Safe Disconnect & Reconnection**: Thread-safe TUN device shutdown prevents bad file descriptor crashes and ANRs.
* **Android 13+ Notification Permissions**: Smooth runtime permission prompts on first launch.

---

## 🛠️ Comparison: Usque Android vs. Standard WARP (WireGuard)

| Feature | Standard WireGuard WARP | Usque Android (MASQUE) |
| :--- | :--- | :--- |
| **Protocol** | WireGuard (UDP) | MASQUE / Connect-IP (HTTP/3 & HTTP/2) |
| **DPI / Censorship Resistance** | Low (WireGuard headers easily fingerprinted) | **High** (Appears as standard HTTPS/QUIC traffic) |
| **Custom SNI Support** | ❌ No | **✅ Yes (`api.cloudflare.com` / custom)** |
| **Transport Fallback** | UDP only | **QUIC (UDP) + HTTP/2 (TCP fallback)** |
| **Custom DNS Resolvers** | Limited | **✅ Full custom resolver configuration** |
| **Packet I/O Path** | JNI Buffers / Userspace | **Zero-JNI Native Kernel FD Streaming** |
| **PMTU Discovery** | Fixed MTU | **✅ Automatic PMTUD** |

---

## 🚀 Installation & Download

Pre-built APKs for `arm64-v8a` and `armeabi-v7a` devices are available on the [Releases Page](https://github.com/cryptic-noodle/usque-android/releases).

1. Download the latest `usque-android-*.apk`.
2. Install the APK on your Android device (Android 7.0+ / API 24+ supported).
3. Open the app, grant VPN & Notification permissions, and tap **Connect**.

---

## ⚙️ Settings & Configuration

Tap the **⚙️ Settings** button on the home screen to customize:

* **Protocol Mode**: Check to force **HTTP/2 (TCP)**; uncheck for **QUIC (HTTP/3)**.
* **SNI**: Defaults to `api.cloudflare.com`. Change to any Cloudflare-fronted domain to bypass ISP blocking.
* **Custom Endpoint**: Specify `ip:port` (e.g. `162.159.198.1:443`) to connect to specific Cloudflare datacenters.
* **Custom DNS Servers**: Enter comma-separated DNS IPs (e.g. `1.1.1.1, 1.0.0.1` or `94.140.14.14`).
* **Keepalive Period**: Tunnel keepalive interval in seconds (default: `30s`).
* **MTU**: Tunnel MTU size (default: `1280`).
* **Always Reconnect**: Automatically reconnect when switching networks.
* **Engine Log Level**: Adjust verbosity (`DEBUG`, `INFO`, `WARN`, `ERROR`).

---

## 🏗️ Building From Source

### Prerequisites
* Go 1.22+
* Java JDK 21
* Android SDK (API 34, Build Tools 34.0.0) & NDK `26.1.10909125`
* `gomobile` & `gobind`

### 1. Compile the Go MASQUE Core (`usque.aar`)
```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

cd android
gomobile bind -v -target=android/arm64,android/arm -androidapi 24 -ldflags="-s -w" -o usque.aar github.com/Diniboy1123/usque/android
mkdir -p usque-vpn/app/libs
cp usque.aar usque-vpn/app/libs/
```

### 2. Build the Android Application
```bash
cd usque-vpn
chmod +x gradlew
./gradlew assembleDebug
```
The output APK will be located at:
`android/usque-vpn/app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License & Credits

* Core MASQUE implementation by [@Diniboy1123](https://github.com/Diniboy1123/usque) under MIT License.
* Android port, optimization, and enhancements by [@cryptic-noodle](https://github.com/cryptic-noodle).
* Original CLI documentation is archived in [README-USQUE.md](README-USQUE.md).
