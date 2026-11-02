# 管家模拟定位（MockLoc）

Android 模拟定位工具：高德选点/搜索，设为系统「模拟位置信息应用」后对第三方显示所选城市。

**包名** `com.yanpeng.mockloc` · 当前构建 **1.0.1-debug**

## 功能

- 高德地图选点与搜索
- 系统模拟位置注入（需开发者选项里指定本应用）
- 加强版注入，减少高德等 App 跳回真位置

## 权限 / 设置

1. 开发者选项 → 选择模拟位置信息应用 → 管家模拟定位
2. 授予定位相关权限

## 构建

```bash
./gradlew :app:assembleDebug
```

`local.properties` 指向本机 Android SDK（勿提交）。

## 隐私

定位仅用于本机模拟注入，不上云。

## License

MIT
