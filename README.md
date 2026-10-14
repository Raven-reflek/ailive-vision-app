# AI直播-视觉采集APP

用手机摄像头对拍"直播手机的屏幕",在本机做文字识别(ML Kit),把识别出的弹幕文字上传云服务,作为 dycast 采集之外的**补充通道**
(不碰抖音服务器,从根上绕开协议层面的风险)。目前处于**真机测试阶段**。设计依据见大脑库 `docs/adr/0024-视觉OCR弹幕采集作为dycast补充通道.md`。

- Kotlin,minSdk 26,targetSdk 34,CameraX + ML Kit 中文文字识别,包名 `com.luxofilms.vision`。
- 与门店 APP 完全独立,只通过云服务按门店归并(同一家店可以同时有播放手机和采集手机两台设备)。

## 新同事先看

云服务仓库 `docs/交接与启动说明.md`(环境、构建、**签名注意事项**、发版流程)。

## 构建

```bat
:: 工程根目录先建 local.properties(已被 git 忽略),写:  sdk.dir=C\:\Users\<你>\AppData\Local\Android\Sdk
gradlew.bat assembleDebug          :: app\build\outputs\apk\debug\app-debug.apk
gradlew.bat assembleRelease        :: app\build\outputs\apk\release\app-release.apk
```

每次发版同时改 `app/build.gradle.kts` 里的 `versionCode`(+1)和 `versionName`。发布走应用内更新通道:APK 命名 `vision-<版本>.apk`,
调云服务 `/admin/release` 登记时必须带 `"app":"vision"`。

## 使用流程

1. 首次打开扫总部绑定二维码,绑定到测试门店(设备令牌加密保存)。
2. 点「标定」:两步标定(先框住被拍手机的屏幕四角,再框出弹幕滚动区域),标定数据以比例坐标保存。
3. 点「开始采集」:前台服务按设定帧率取帧 → 透视纠正与裁剪 → 本地 OCR → 上传文字。首页会显示最近识别到的几行,用于核对识别效果。
4. 服务有看门狗:摄像头长时间没有画面会自动重连并发告警;手机重启后会尝试自动恢复(后台启动相机在新版安卓上可能被系统限制,失败会发通知提示手动开始)。

## 主要文件(`app/src/main/java/com/luxofilms/vision/`)

| 文件 | 职责 |
|---|---|
| `CaptureService.kt` | 前台服务:CameraX 取帧、节流、看门狗、状态通知 |
| `FrameProcessor.kt` | 透视纠正 + 裁剪 + 预处理 + 本地 OCR + 上传文字 |
| `CameraAlignActivity.kt` / `AlignOverlayView.kt` | 两步标定界面 |
| `CalibrationStore.kt` / `PerspectiveUtils.kt` | 标定数据与透视变换 |
| `Api.kt` / `Prefs.kt` | 云服务接口与加密存储 |
| `Alerts.kt` / `BootReceiver.kt` | 告警通知与开机自启 |
| `UpdateChecker.kt` | 应用内更新 |

## 已知限制

- 本地 OCR 对弹幕昵称识别很弱(昵称小、彩色、带打码),目前上传时昵称统一用固定值,只取弹幕内容。
- 服务端对每台设备限流 40 次/60 秒,默认采样 0.5 帧/秒。
- 云端大模型识别路径(`/api/vision/frame`)已实现但受方舟账号权限问题阻塞,当前线上用的是本地 OCR 路径(`/api/vision/text`)。
