package com.accessrdp.client

import android.app.Application
import android.util.Log
import com.accessrdp.client.jni.FreerdpJni

/**
 * 应用入口。
 *
 * 启动顺序（防御性，避免真机闪退）：
 *  1. 先安装「黑匣子」全局崩溃捕获 —— 保证之后任何崩溃都能被记录到文件；
 *  2. 预加载原生库（带安全气囊：失败只降级，不崩）；
 *  3. 把 APK 的原生库目录告诉 FreeRDP，这样连接时 FreeRDP 才能 dlopen 到
 *     librdpsnd_android.so（音频后端）。
 */
class AccessRdpApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1) 最先安装崩溃捕获，确保后续任何异常都能落盘。
        try {
            CrashLogger.install(this)
        } catch (t: Throwable) {
            Log.e("AccessRDP/App", "安装崩溃捕获失败", t)
        }

        // 2) 预加载原生库（load() 内部已兜住所有异常，不会抛）。
        try {
            FreerdpJni.load()
        } catch (t: Throwable) {
            Log.e("AccessRDP/App", "预加载原生库异常", t)
        }

        // 3) 告诉 FreeRDP 原生库目录（仅当主库已成功加载时才调用，
        //    否则调用 external 会抛 UnsatisfiedLinkError）。
        if (FreerdpJni.isLibraryLoaded) {
            try {
                FreerdpJni.setNativeLibraryDir(applicationInfo.nativeLibraryDir)
            } catch (t: Throwable) {
                // 原生库未集成 / 符号缺失时忽略，不影响其余功能。
                Log.w("AccessRDP/App", "setNativeLibraryDir 失败", t)
            }
        }
    }
}
