# BLUFI-NET: Resilient Peer-to-Peer Internet Relay over Bluetooth Mesh

BLUFI-NET is an Android application that enables nearby devices to form a self-organizing Bluetooth Low Energy (BLE) mesh network. Any device in the mesh with an active internet connection (via cellular, Wi-Fi, or satellite) can dynamically act as a gateway and share its connectivity with offline devices over multi-hop routing paths.

---

## 📥 Download Ready-to-Use APK

You can download the pre-built, fully tested MeshLink Android APK directly:

👉 **[Download MeshLink APK (`app-debug.apk`)](app-debug.apk)** *(6.3 MB)*

*Minimum Android Version:* Android 6.0 (API 23+)  
*Target Android Version:* Android 17 (API 37)

---

## 1. Project Goals & Success Criteria

### **Primary Goals**
* **Dynamic Sharing:** Any device can automatically announce itself as an internet gateway when online, and retract its status when offline.
* **Multi-Hop Relay:** Outbound requests are routed hop-by-hop through intermediate offline nodes until they reach a gateway.
* **Privacy & Security:** Secure connection payloads using end-to-end encryption (E2E) so intermediate relay nodes cannot inspect user traffic.
* **Resilience:** The network auto-recovers and dynamically re-routes if a gateway drops offline or goes out of range mid-session.

### **MVP Success Criteria**
1. Offline client successfully fetches web data over a 1-hop BLE path.
2. Offline client routes data successfully over a multi-hop (2+ hops) BLE path.
3. Gateway status updates dynamically in response to connectivity state toggles.
4. Mesh selects the best available gateway automatically (by hop-count, battery, and load).
5. Data remains encrypted end-to-end between client and gateway.

---

## 2. Current Implementation Status

Out of the 11 Functional Requirements (FR) defined in the project specification, the current status is as follows:

### **Completed Features**
* **BLE Peer Discovery (FR-1):** Peers are discovered dynamically over BLE without requiring manual pairing.
* **Connectivity Monitoring (FR-2):** Online status checked via modern `NetworkCapabilities` APIs; gateways dynamically signal online/offline transitions.
* **Gateway Status Broadcast (FR-3):** Internet-connected nodes automatically advertise gateway capabilities across the mesh.
* **Routing Table Management (FR-4):** Nodes maintain a dynamic routing table and resolve shortest paths via BFS routing search.
* **End-to-End Encryption (FR-5):** Payloads are encrypted using Google Tink ECIES (P-256 + AES-128-GCM + ECDSA signatures) backed by Android Keystore hardware keys with TOFU fingerprint pinning.
* **Arbitrary HTTP Relay & Mesh Browser (FR-6):** Full HTTP/HTTPS web fetching over mesh with SSRF prevention (`PublicInternetAddressPolicy`), 5MB streamed body capping, and redacted debug logging.
* **Gateway Failover (FR-7):** Automatic re-routing upon mid-session gateway loss via `;;offline` signal propagation.
* **Gateway Data Limit Control (FR-9):** Configured 50MB relay data cap (`RELAY_DATA_LIMIT`) with usage tracking.
* **Background Service Execution (FR-10):** Decoupled service lifecycle running as a compliant `connectedDevice` foreground service with persistent notifications.
* **System-wide VPN Tunneling:** Local SOCKS5 proxy and Wi-Fi Direct tunneling forward system app traffic securely through mesh gateways.
* **Fallback Text Messaging (FR-11):** Basic local text messaging is fully supported between mesh peers without internet.

---

## 3. Technology Stack & Requirements

* **Platform:** Android (legacy Java implementation with built-in Kotlin enabled for new code).
* **Build System:** Android Gradle Plugin 9.2.1, Gradle 9.4.1, Kotlin DSL, and a version catalog.
* **Toolchain Compatibility:** Gradle runs on JDK 17–26 and is verified with OpenJDK 25.0.2; Android Java/Kotlin bytecode targets JVM 17.
* **Minimum Android SDK:** 23 (Android 6.0 Marshmallow).
* **Compile/Target Android SDK:** 37.0 (Android 17).
* **Hardware Requirement:** Devices serving as mesh servers must support BLE Multiple Advertisement.

---

## 4. Getting Started

### **Prerequisites**
Install the following before opening or building the project:

* Android Studio Panda 3 (2025.3.3 Patch 1) or newer.
* JDK 17 through 26. JDK 25 is recommended for command-line builds.
* Android SDK Platform 37.0 and Android SDK Build Tools 36.0.0.

Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or let Android Studio create an uncommitted `local.properties` containing `sdk.dir`. Do not commit a machine-specific SDK path.

### **Building the Project**
The checked-in wrapper downloads the exact Gradle version. From the project root, run:

```bash
./gradlew test lintDebug assembleDebug
```

On Windows, use `gradlew.bat`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### **Run on Physical Devices**
1. Deploy the APK to 2 or more physical Android devices.
2. Grant Nearby Devices permission on Android 12+, notification permission on Android 13+, or location permission on Android 6–11.
3. Start the services. If a device has internet access, it will register itself as a gateway; other devices will join as clients and route local messaging or requests through it.

BLE advertising is generally unavailable in standard emulators, so multi-device mesh verification requires physical hardware.
