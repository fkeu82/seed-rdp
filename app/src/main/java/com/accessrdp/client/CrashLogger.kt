package com.accessrdp.client

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「黑匣子」——全局未捕获异常记录器（v1.0.3 强化版）。
 *
 * 相比上一版的关键改进：**日志一定写得进去、一定找得到**。
 *
 * 上一版写到 `Android/data/<pkg>/files/`，但在 Android 11+ 的分区存储下，
 * 普通文件管理器**根本进不去这个目录**，所以用户反馈"指定目录里没有日志"。
 *
 * 本版改为写入**公共「下载」目录下的 AccessRDP 文件夹**：
 *
 *   - Android 10+ ：通过 MediaStore 写入
 *       `Download/AccessRDP/crash-YYYYMMDD.txt`（无需任何权限，文件管理器里直接可见）
 *   - Android 9-  ：直接写 `/sdcard/Download/AccessRDP/crash-YYYYMMDD.txt`
 *       （配合 manifest 的 WRITE_EXTERNAL_STORAGE，maxSdkVersion=28）
 *   - 全部失败时  ：回退到 app 私有目录，并**同时打 logcat**，任何情况下都不丢信息
 *
 * 每次写日志还会同步 `Log.e(TAG, ...)` 到 logcat，方便 `adb logcat -s AccessRDP/Crash` 抓取。
 */
object CrashLogger {

    private const val TAG = "AccessRDP/Crash"

    /** 公共下载目录下自建的文件夹名，用户一眼能找到。 */
    private const val PUBLIC_DIR = "AccessRDP"

    @Volatile
    private var installed = false

    /** 最近一次写入的日志绝对路径（供 UI / 播报展示）。 */
    @Volatile
    var lastLogPath: String? = null
        private set

    /**
     * 安装全局崩溃捕获。必须在 Application.onCreate 里尽早点调用。
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
                // 记录日志本身失败也绝不能影响崩溃流程；至少打到 logcat。
                Log.e(TAG, "写入崩溃日志失败", t)
            }
            // 交还给系统默认处理器（保留系统崩溃对话框 / 进程终止行为）。
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "全局崩溃捕获已安装，日志目录：Download/$PUBLIC_DIR/")
    }

    /**
     * 主动写一条运行日志（非崩溃场景也可用，例如连接失败时留痕）。
     * 用于保证"必须打印日志"在任何路径下都成立。
     */
    fun log(context: Context, tag: String, message: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val content = "[$stamp] [$tag] $message\n"
            val path = appendLog(context, "run-$day.txt", content)
            Log.i(TAG, "运行日志($tag): $message  -> $path")
        } catch (t: Throwable) {
            Log.e(TAG, "写入运行日志失败", t)
        }
    }

    /** 崩溃日志的展示路径（供 UI 告诉用户去哪里找）。 */
    fun displayPath(): String = "下载/AccessRDP/"

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val fileName = "crash-$day.txt"

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("========== AccessRDP 崩溃报告 ==========")
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
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

        val content = sw.toString()

        // 1) 一定打到 logcat（任何设备都能用 adb logcat -s AccessRDP/Crash 抓到）
        Log.e(TAG, "检测到崩溃，开始写入日志。\n$content")

        // 2) 落盘
        val path = appendLog(context, fileName, content)
        lastLogPath = path
        Log.e(TAG, "崩溃日志已写入：$path")
    }

    /**
     * 追加写日志，返回最终写入路径（失败返回 null）。
     *
     * 写入策略按 Android 版本分派，确保"一定写得进去、一定找得到"：
     * - API 29+：MediaStore（Download/AccessRDP/），免权限、文件管理器可见
     * - API 21-28：直接写 /sdcard/Download/AccessRDP/
     * - 兜底：app 私有外部目录
     */
    private fun appendLog(context: Context, fileName: String, content: String): String? {
        // ---- 方案 A：Android 10+ 用 MediaStore 写入公共下载目录 ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val viaMediaStore = writeViaMediaStore(context, fileName, content)
            if (viaMediaStore != null) return viaMediaStore
        }

        // ---- 方案 B：直接写外部存储的 Download 目录（API 28- 无需分区存储适配）----
        val direct = writeDirect(fileName, content)
        if (direct != null) return direct

        // ---- 方案 C：全部失败 -> app 私有目录兜底（至少不丢数据）----
        return writePrivate(context, fileName, content)
    }

    /** MediaStore 方式：不需要权限，且「文件管理 → 下载」里直接可见。 */
    private fun writeViaMediaStore(context: Context, fileName: String, content: String): String? {
        return try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR"

            // MediaStore 不支持 append：先读出旧内容（若有），再整体覆盖写回。
            var existing = ""
            val existingUri = findExistingUri(context, collection, fileName)
            if (existingUri != null) {
                existing = try {
                    context.contentResolver.openInputStream(existingUri)
                        ?.use { it.readBytes().decodeToString() } ?: ""
                } catch (t: Throwable) {
                    ""
                }
                try {
                    context.contentResolver.delete(existingUri, null, null)
                } catch (t: Throwable) {
                    // 删除失败不致命，下面按新建处理
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                os.write((existing + content).toByteArray())
                os.flush()
            } ?: return null

            "Download/$PUBLIC_DIR/$fileName"
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore 写日志失败，尝试直接写文件", t)
            null
        }
    }

    /** 在 MediaStore 中查找已存在的同名日志文件 URI。 */
    private fun findExistingUri(
        context: Context,
        collection: android.net.Uri,
        fileName: String
    ): android.net.Uri? {
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("%$PUBLIC_DIR%", fileName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idIdx >= 0) {
                        ContentUris.withAppendedId(collection, cursor.getLong(idIdx))
                    } else null
                } else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 直接写文件：公共 Download 目录。 */
    private fun writeDirect(fileName: String, content: String): String? {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                PUBLIC_DIR
            )
            if (!dir.exists() && !dir.mkdirs()) return null
            val f = File(dir, fileName)
            f.appendText(content)
            f.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "直接写入 Download 目录失败", t)
            null
        }
    }

    /** 兜底：app 私有目录（一定能写，但用户不易访问）。 */
    private fun writePrivate(context: Context, fileName: String, content: String): String? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            f.appendText(content)
            f.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "私有目录写日志也失败", t)
            null
        }
    }

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (e: Exception) {
        "unknown"
    }

    /**
     * APK 内实际携带的 ABI 目录，用于判定「ABI 误选闪退」。
     */
    private fun apkAbis(context: Context): List<String> = try {
        context.applicationInfo.nativeLibraryDir
            ?.let { File(it).parentFile?.listFiles()?.map { f -> f.name } }
            ?.sorted()
            ?: Build.SUPPORTED_ABIS.toList()
    } catch (e: Exception) {
        Build.SUPPORTED_ABIS.toList()
    }
}
