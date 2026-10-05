package com.accessrdp.client.jni

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * 远程音频重定向的“播放端”。
 *
 * FreeRDP 原生层在远端把 Windows 的声音解码为 PCM（常用 44100/48000Hz、双声道、16bit 小端），
 * 通过 [FreerdpJni.onAudioData] 回调到本对象；本对象用 Android [AudioTrack] 直接播放，
 * 实现“把 Windows 电脑的声音（含 NVDA 读屏语音）传回手机”。
 *
 * 仅依赖 Android 框架，与 RDP 协议无关，可独立测试。
 */
object AudioRedirect {

    private const val TAG = "AccessRDP/Audio"

    @Volatile private var track: AudioTrack? = null

    @Volatile private var currentRate: Int = 0
    @Volatile private var currentChannels: Int = 0
    @Volatile private var currentBits: Int = 0

    /** 远端音频总开关：false 时丢弃 PCM（对应“静音/不回传”）。 */
    @Volatile private var audioEnabled: Boolean = true

    private val lock = Any()

    fun setEnabled(on: Boolean) {
        audioEnabled = on
        if (!on) release()
        Log.i(TAG, "音频回传开关 = $on")
    }

    /** 音频格式协商完成（FreeRDP 即将开始推流）。目前惰性创建 AudioTrack，这里仅打点。 */
    fun onStart() {
        Log.i(TAG, "音频流开始")
    }

    /**
     * 写入一帧 PCM 数据并播放。采样率/声道/位深变化时自动重建 [AudioTrack]。
     * 位深 8 用 [AudioFormat.ENCODING_PCM_8BIT]，其余按 16bit 处理。
     */
    fun write(data: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!audioEnabled || data.isEmpty()) return
        // 某些异常采样率（0 / 负数 / 超范围）会让 AudioTrack 构造直接抛 IllegalArgumentException；
        // 这里做一次防御性归一，避免原生回调线程上抛异常导致崩溃。
        val rate = if (sampleRate in 8000..192000) sampleRate else 44100
        val ch = if (channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = if (bitsPerSample == 8) {
            AudioFormat.ENCODING_PCM_8BIT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        synchronized(lock) {
            try {
                if (track == null || currentRate != rate || currentChannels != ch || currentBits != encoding) {
                    track?.release()
                    val minBuf = AudioTrack.getMinBufferSize(rate, ch, encoding)
                        .coerceAtLeast(1)
                    track = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate, ch, encoding,
                        minBuf * 2, AudioTrack.MODE_STREAM
                    ).apply { play() }
                    currentRate = rate
                    currentChannels = ch
                    currentBits = encoding
                    Log.i(TAG, "AudioTrack 已创建：${rate}Hz / $channels 声道 / $bitsPerSample bit")
                }
                track?.write(data, 0, data.size)
            } catch (e: Exception) {
                // 任何播放异常都只记录，绝不冒泡（否则会导致 App 崩溃）。
                Log.e(TAG, "AudioTrack.write 失败：${e.message}")
                try {
                    track?.release()
                } catch (_: Exception) {
                }
                track = null
                currentRate = 0
                currentChannels = 0
                currentBits = 0
            }
        }
    }

    /** 释放播放资源（断开连接、关闭音频通道或退出时调用）。 */
    fun release() {
        synchronized(lock) {
            track?.stop()
            track?.release()
            track = null
            currentRate = 0
            currentChannels = 0
            currentBits = 0
        }
    }
}
