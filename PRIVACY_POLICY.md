# MeshLink Privacy Policy

MeshLink is an open-source Android application for peer-to-peer mesh networking.

MeshLink does not collect, track, or transmit analytics, advertising identifiers, or personally identifiable information to external servers. All BLE identifiers, routing tables, and messages are processed strictly locally to operate the peer-to-peer mesh network.

Email and text-sharing operations use standard Android system intents. Content is handled locally or transmitted through the peer-to-peer mesh using end-to-end encryption to designated gateway nodes.

---

### Permissions Requested

The authoritative list of Android permissions requested is in `app/src/main/AndroidManifest.xml`:

| Permission | Purpose |
| :--- | :--- |
| `android.permission.BLUETOOTH` & `BLUETOOTH_ADMIN` | Access Bluetooth on legacy Android versions (API <= 30). |
| `android.permission.ACCESS_FINE_LOCATION` | Required for BLE scanning on legacy Android versions (API <= 32). Location coordinates are never recorded or transmitted. |
| `android.permission.BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | Discover, connect, and advertise to nearby mesh nodes on Android 12+ (API 31+). |
| `android.permission.NEARBY_WIFI_DEVICES` | Discover and connect to nearby Wi-Fi Direct mesh nodes on Android 13+ (API 33+). |
| `android.permission.POST_NOTIFICATIONS` | Display the foreground service notification on Android 13+ (API 33+). |
| `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Maintain active background BLE advertising and mesh service operation. |
| `android.permission.ACCESS_NETWORK_STATE` & `INTERNET` | Detect network connectivity and route outbound gateway traffic over secure connections. |

---

For security inquiries or questions, please open an issue in the project repository.
