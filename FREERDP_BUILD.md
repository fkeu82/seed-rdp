# 接入真实 FreeRDP（含远端音频回传）—— 手把手实战指南

本指南说明如何把“演示模式”的 AccessRDP 升级为**真实连接 Windows 远程桌面、且 NVDA 等读屏语音能经 RDP 音频重定向传回手机**的版本。

> 无障碍前置（竖屏锁定、连接后才显示键盘、干净朗读与焦点链、自动切换逻辑）本工程均已完成。本指南只讲“如何拿到并集成真实 FreeRDP 原生库，尤其是音频”。

---

## 0. 先理解：为什么要“一整套” `.so` 而不是一个

FreeRDP 不是一个单独的库；运行时依赖一整套。本工程现在会产出**两个**自有原生库 + FreeRDP 依赖：

| 库 | 作用 |
|---|---|
| `libfreerdp_client.so` | **主 JNI 桥接**（连接 / 键盘 / 鼠标 / 音频开关），源文件 `app/src/main/jni/freerdp_jni.c` |
| `librdpsnd_android.so` | **音频输出后端**（FreeRDP rdpsnd 插件加载它，把解码后的 PCM 回传 Kotlin 播放），源文件 `app/src/main/jni/rdpsnd_android.c` |
| `libfreerdp2.so` / `libfreerdp-client2.so` | FreeRDP 核心 + 客户端通道（含 rdpsnd 插件框架） |
| `libwinpr2.so` | WinPR（线程/同步/句柄） |
| `libssl.so` / `libcrypto.so` | OpenSSL（TLS / NLA 网络级认证，公网必备） |

所以“集成原生库”= 把上面这一整套 `.so` 一起放进 `app/src/main/jniLibs/<abi>/`。
App 启动 `System.loadLibrary("freerdp_client")` 与 `System.loadLibrary("rdpsnd_android")` 后，Android 链接器自动解析它们的依赖，无需逐个 `load`。

支持 ABI：`arm64-v8a`（主流 64 位）、`armeabi-v7a`（老机型）。

---

## 1. 远端音频是怎么“传回手机”的（核心，已完整实现）

这是视障用户刚需：远端 Windows 上 NVDA 的朗读、系统提示音，必须通过 RDP 音频重定向在手机播放。

数据通路（已对照 FreeRDP 2.x 公共 API 实现，非桩）：

```
Windows 声音 → RDP 网络 → FreeRDP rdpsnd 通道(协议+解码) → 解码出 PCM
   → librdpsnd_android.so 的 android_play(pcm, size) 回调
   → JNI → FreerdpJni.onAudioData(byte[], rate, channels, bits)
   → AudioRedirect.write() → Android AudioTrack → 手机扬声器
```

关键实现点：
- `rdpsnd_android.c` 实现了 FreeRDP 要求的 **rdpsnd device-plugin**（`pcOpen`/`pcPlay`/`pcClose`/`pcFree` 等回调），入口符号 `freerdp_rdpsnd_client_subsystem_entry` 会被 FreeRDP `dlopen` 找到。
- `pcPlay` 收到的是**已解码的线性 PCM**（小端，通常 16bit/44.1k 或 48k/双声道）；我们原样回传 Kotlin，不重采样、不省略。
- 为了让 FreeRDP 优先选用我们的后端，CI 在交叉编译 FreeRDP 时，对 `channels/rdpsnd/client/rdpsnd_main.c` 的 `backends[]` 数组**打了一行补丁**：把 `{ "android", "" }` 置为首选（`build-freerdp.yml` 里的 python 补丁步骤）。同时 CI 关闭了 pulse/alsa/opensles 等内置后端，避免冲突。
- `freerdp_jni.c` 在 `nativeConnect` 里显式 `freerdp_channels_load_plugin(..., "rdpsnd", ...)` 并开启 `AudioPlayback`；还设置 `FREERDP_ADDIN_PATH` 指向 APK 原生库目录，确保 FreeRDP 能 `dlopen` 到 `librdpsnd_android.so`。
- `AudioRedirect.kt` 用 `AudioTrack(STREAM_MUSIC)` 播放，按采样率/声道/位深惰性重建，支持 8/16bit；`audioEnabled` 开关可静音。
- `AccessRdpApp.onCreate()` 在连接前调用 `FreerdpJni.setNativeLibraryDir(applicationInfo.nativeLibraryDir)` 设定 addin 路径。

---

## 2. 推荐方案：GitHub Actions 云端编译（推送即构建）

工程内置工作流 `.github/workflows/build-freerdp.yml`，**推送到 `main` 即自动开始编译并产出 APK**；打 `v*` 标签额外发布到 Releases。

### 步骤
1. 把本工程 `git push` 到 GitHub 仓库（默认分支 `main`）。
2. 推送会**自动触发** Actions → `Build FreeRDP & APK (Android)`：
   - 云端 Ubuntu：下载 NDK（腾讯镜像优先）→ 交叉编译 OpenSSL → 克隆并**补丁** FreeRDP → 编译 FreeRDP → 编译两个 JNI 库 → 收集全部 `.so` 到 `app/src/main/jniLibs/` → Gradle 打包 `app-debug.apk`（可直接安装）。
3. 流水线跑完后，在 **Artifacts** 下载 `AccessRDP-debug-apk`（即 `app-debug.apk`）。
4. 打 `v*` 标签（`git tag v1.0.0 && git push --tags`）→ APK 自动发布到仓库 **Releases**，可在 Release 页面直接下载（公网链接）。

> 国内加速：NDK 用腾讯镜像；FreeRDP/OpenSSL 的 `git clone` 带 `ghproxy.com` / `gitee.com/mirrors` 回退（见工作流里的 `clone()` 函数）。

---

## 3. 备选方案：本地用 NDK 编译（离线可控）

### 3.1 准备
```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk-r25c
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
ROOT=$HOME/accessrdp-build && mkdir -p $ROOT && cd $ROOT
PREFIX=$ROOT/freerdp-install; SSL_PREFIX=$ROOT/openssl
```

### 3.2 编译 OpenSSL
```bash
git clone --depth 1 -b openssl-3.1.4 https://github.com/openssl/openssl.git openssl-src
cd openssl-src
./Configure android-arm64 -D__ANDROID_API__=24 --prefix=$SSL_PREFIX --cross-compile-prefix=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android-
make -j"$(nproc)" && make install_sw; cd ..
```

### 3.3 克隆并补丁 FreeRDP（关键：加 android 音频后端）
```bash
git clone --depth 1 -b 2.11.0 https://github.com/FreeRDP/FreeRDP.git
python3 - <<'PY'
import re
p="FreeRDP/channels/rdpsnd/client/rdpsnd_main.c"
s=open(p,encoding="utf-8").read()
s,_=re.subn(r'(\n[ \t]*\{ "fake", "" \})', r'\n\t\t{ "android", "" },\1', s, count=1)
open(p,"w",encoding="utf-8").write(s)
PY
```

### 3.4 编译 FreeRDP（关闭所有内置音频/图形后端，仅保留 rdpsnd 通道）
```bash
cd FreeRDP && mkdir build-android && cd build-android
cmake -GNinja -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DWITH_OPENSSL=ON -DOPENSSL_ROOT_DIR=$SSL_PREFIX -DWITH_OPENH264=OFF -DWITH_FFMPEG=OFF \
  -DWITH_JPEG=OFF -DWITH_PULSE=OFF -DWITH_ALSA=OFF -DWITH_OSS=OFF -DWITH_OPENSLES=OFF \
  -DWITH_WINMM=OFF -DWITH_MACAUDIO=OFF -DWITH_IOSAUDIO=OFF -DWITH_CUPS=OFF -DWITH_WAYLAND=OFF \
  -DWITH_URBDRC=OFF -DWITH_GSSAPI=OFF -DWITH_SSE2=OFF -DWITH_NEON=ON \
  -DWITH_SERVER=OFF -DBUILD_TESTING=OFF -DWITH_SAMPLE=OFF -DWITH_CLI=OFF -DWITH_MANPAGES=OFF ..
cmake --build . -j"$(nproc)" && cmake --install .; cd ../..
```

### 3.5 编译两个 JNI 库
```bash
mkdir bridge-build && cd bridge-build
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DFREERDP_ROOT=$PREFIX -DCMAKE_INSTALL_PREFIX=$ROOT/bridge-out \
  -S /path/to/AccessRDP/app/src/main/jni
cmake --build .; cd ..
```

### 3.6 收集 `.so` 到工程
```bash
DST=/path/to/AccessRDP/app/src/main/jniLibs/arm64-v8a && mkdir -p "$DST"
cp bridge-build/libfreerdp_client.so bridge-build/librdpsnd_android.so "$DST/"
cp $PREFIX/lib/*.so "$DST/"      # FreeRDP + OpenSSL 依赖
```
然后 `./gradlew assembleDebug` 打包即可（真实连接版）。

---

## 4. App 是怎么“自动切换”的（无需改代码）

`RdpViewModel` 初始化时：
```kotlin
val nativeLoaded = FreerdpJni.load()          // System.loadLibrary("freerdp_client") + ("rdpsnd_android")
_transport = if (nativeLoaded) NativeTransport() else MockTransport()
```
- `jniLibs` 放好 `.so` → 加载成功 → `NativeTransport`：**点“连接”即真实 RDP 会话，远端声音经音频重定向在手机播放**。
- 没有 `.so`（演示 APK）→ 回退 `MockTransport`：键盘逻辑、组合键、扫描码回显照常，便于体验。

`NativeTransport` 把 `StickyKeyManager` 的每条动作转发给 `FreerdpJni.sendKeyDown/sendKeyUp` → FreeRDP `freerdp_input_send_keyboard_event_ex`，发往远端 Windows。粘滞键逻辑、自动复位、焦点链**完全不用改**。

---

## 5. 国内托管 / 镜像建议

- **Release 下载慢**：用 `https://ghproxy.com/https://github.com/<你>/<仓库>/releases/download/<标签>/app-debug.apk` 加速。
- **Gitee**：导入 Gitee 后同样支持 `.workflows/`；注意 Gitee runner 默认无 Android 环境，需在工作流里自装 NDK/SDK。
- **NDK 镜像**：腾讯云 `https://mirrors.cloud.tencent.com/AndroidSDK/`。

---

## 6. 真机验证清单（连真实 Windows，重点验音频）

1. 远端 Windows 开启“允许远程桌面”，记好 IP/域名与账号密码。
2. 手机安装“真实连接版” APK，打开 **TalkBack**。
3. 顶部填主机 / 用户名 / 密码，开启“远程音频重定向”，点“连接”。状态变“已连接”后**底部复选框键盘出现**。
4. 双击勾 `Win` 再按 `D` → 远端显示桌面；双击 `Ctrl`+`Alt` 再按 `Del` → 远端安全界面。每次发送后复选框自动复位（防卡键）。
5. **音频验证**：远端 Windows 打开 NVDA（或放一段音乐 / 系统提示音），手机应**实时听到**对应声音。若听不到：
   - 确认连接时“远程音频重定向”已开启（`AudioPlayback`）；
   - 用 `adb logcat | grep AccessRDP` 看 `AccessRDP/rdpsnd`（后端注册）与 `AccessRDP/Audio`（AudioTrack 创建）日志；
   - 确认 `librdpsnd_android.so` 已随 APK 安装（`unzip -l app-debug.apk | grep rdpsnd`）。

---

## 7. 常见问题

| 现象 | 排查 |
|---|---|
| 启动崩 / `UnsatisfiedLinkError` | `jniLibs` 漏了依赖库（必须整套：`libfreerdp2.so`/`libfreerdp-client2.so`/`libwinpr2.so`/`libssl.so`/`libcrypto.so`）。 |
| 连不上（NLA 主机） | `securityLevel=3` 且用户名/密码/域正确；公网 Windows 通常要求 NLA。 |
| 黑屏但已连接 | 视频编解码问题；确认未误开 OpenH264 却没编进去，或让对端改用 RFX。 |
| **有连接但没声音** | 查 `librdpsnd_android.so` 是否在 APK 内；查 `FREERDP_ADDIN_PATH` 是否设置（`AccessRdpApp`）；`adb logcat` 看 `AccessRDP/rdpsnd` 是否打印“Android 音频后端已注册”。FreeRDP 找不到后端会回退到静音的 `fake`，日志会有提示。 |
| 键盘发了没反应 | 检查远端窗口聚焦；组合键走 `freerdp_input_send_keyboard_event_ex`，扩展键已带 `0xE000` 高位。 |
