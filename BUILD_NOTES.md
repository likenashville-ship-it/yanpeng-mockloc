# 管家模拟定位 (com.yanpeng.mockloc) 1.0.0

## APK
- `/workspace/mockloc/app/build/outputs/apk/debug/app-debug.apk`
- copy: `/workspace/mockloc/管家模拟定位-1.0.0-debug.apk`

## Build
```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.21+9
export ANDROID_HOME=/workspace/tools/android-sdk
cd /workspace/mockloc && ./gradlew assembleDebug
```

## Behavior
- Map: Leaflet + Gaode web tiles (GCJ-02)
- Search: Nominatim (WGS→GCJ for display); manual lat/lng fallback
- Mock: LocationManager.addTestProvider + setTestProviderLocation on gps + network
- Injects WGS84 (converted from GCJ-02)
- Foreground service + notification while mocking
