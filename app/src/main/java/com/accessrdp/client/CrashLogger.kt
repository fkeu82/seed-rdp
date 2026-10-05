package com.accessrdp.client

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「黑匣子」——全局未捕获异常记录器。
 *
 * 目的：真机上 App 崩溃时，无法连 USB 调试的情况下，用户仍能把崩溃日志读出来反馈。
 *
 * 行为：
 * - 安装一个 [Thread.setDefaultUncaughtExceptionHandler]，捕获所有线程的未捕获异常；
 * - 把时间 / 机型 / Android 版本 / 完整 StackTrace 追加写入
 *   `Android/data/com.accessrdp.client/files/crash-YYYYMMDD.txt`（外部可见，无需 root）；
 * - 若外部目录不可写，回退到内部 filesDir；
 * - 记录完成后，**继续调用系统默认处理器**，让系统照常处理崩溃（不要吞掉，避免状态不一致）。
 *
 * 日志文件位置（用户可直接在「文件管理 → Android/data/com.accessrdp.client/files」里找到）：
 *   - /sdcard/Android/data/com.accessrdp.client/files/crash-*.txt
 */
object CrashLogger {

    private const val TAG = "AccessRDP/Crash"

    /** 崩溃日志目录名（放外部 filesDir，用户能在文件管理器里看到）。 */
    private const val DIR_NAME = "files"

    @Volatile
    private var installed = false

    /**
     * 安装全局崩溃捕获。必须在 Application.onCreate 里尽早点调用。
     * @param context 建议传 applicationContext
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (t: Throwable) {
                // 记录日志本身失败也绝不能影响崩溃流程。
                Log.e(TAG, "写入崩溃日志失败", t)
            }
            // 交还给系统默认处理器（保留系统崩溃对话框 / 进程终止行为）。
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "全局崩溃捕获已安装")
    }

    /** 崩溃日志文件路径（供 UI 显示给用户，便于其找到并反馈）。 */
    fun logDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        return if (external != null && (external.exists() || external.mkdirs())) {
            external
        } else {
            context.filesDir
        }
    }

    /** 最近一次崩溃日志文件（若存在）。 */
    fun latestLogFile(context: Context): File? =
        logDir(context).listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = logDir(context)
        if (!dir.exists()) dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(dir, "crash-$day.txt")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("========== AccessRDP 崩溃报告 ==========")
            pw.println("时间: $stamp")
            pw.println("线程: ${thread.name}")
            pw.println("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("设备支持的 ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            pw.println("设备首选 ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            pw.println("APK 内包含的 ABI: ${apkAbis(context).joinToString(", ")}")
            pw.println("App 版本: ${appVersion(context)}")
            pw.println("原生库已加载: ${com.accessrdp.client.jni.FreerdpJni.isLibraryLoaded}")
            pw.println("音频后端已加载: ${com.accessrdp.client.jni.FreerdpJni.isAudioBackendLoaded}")
            pw.println("最近一次加载错误: ${com.accessrdp.client.jni.FreerdpJni.lastLoadError}")
            pw.println("---------- StackTrace ----------")
            throwable.printStackTrace(pw)
            pw.println("========== 报告结束 ==========")
            pw.println()
        }

        // 追加写入，保留当天多次崩溃记录。
        file.appendText(sw.toString())
        Log.e(TAG, "崩溃日志已写入: ${file.absolutePath}")
    }

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (e: Exception) {
        "unknown"
    }

    /**
     * APK 内实际携带的 ABI 目录。
     *
     * 排查「ABI 误选闪退」的关键证据：若设备首选 ABI 不在本列表里，
     * 那 System.loadLibrary 必然失败——这正是"装上能打开、一用原生库就闪退"的典型成因。
     */
    private fun apkAbis(context: Context): List<String> = try {
        context.applicationInfo.nativeLibraryDir
            ?.let { File(it).parentFile?.listFiles()?.mapNotNull { f -> f.name } }
            ?.sorted()
            ?: Build.SUPPORTED_ABIS.toList()
    } catch (e: Exception) {
        Build.SUPPORTED_ABIS.toList()
    }
}
