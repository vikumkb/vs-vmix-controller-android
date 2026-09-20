# V&S vMix Controller Android — Auto Connect

This edition does **not** show or ask for an IP address.

## App behavior

1. Open the app.
2. It first tries `vands-vmix.local`.
3. If that does not resolve, it scans the local IPv4 networks visible to Android.
4. When it finds the V&S Controller on port 8090, it opens it full-screen in the app.
5. No controller IP or URL is shown to the operator.

If the network changes, tap the small reconnect icon at bottom-right.

## Supported network paths

- Same Wi-Fi
- Same LAN / Ethernet-connected controller PC
- Mobile hotspot
- Laptop / Windows hotspot
- USB tethering when Android exposes the tether network as a reachable local interface

A plain USB cable without USB tethering is not an IP network.

## Build

GitHub Actions automatically builds the Android APK on every push to `main`.

```bash
gradle :app:assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`
