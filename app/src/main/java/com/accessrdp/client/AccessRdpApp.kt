package com.accessrdp.client

import android.app.Application
import com.accessrdp.client.jni.FreerdpJni

/**
 * 应用入口。在 onCreate 里把 APK 的原生库目录告诉 FreeRDP，
 * 这样连接时 FreeRDP 才能 dlopen 到 librdpsnd_android.so（音频后端）。
 * 必须在任何 RDP 连接之前执行，故放在 Application 而非 Activity。
 */
class AccessRdpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FreerdpJni.setNativeLibraryDir(applicationInfo.nativeLibraryDir)
        } catch (e: Throwable) {
            // 原生库未集成（演示模式）时忽略，不影响其余功能。
        }
    }
}
