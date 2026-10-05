/*
 * rdpsnd_android.c —— AccessRDP 的 FreeRDP 音频输出后端（addin）
 *
 * 编译产物：librdpsnd_android.so（放到 app/src/main/jniLibs/<abi>/）
 *
 * 工作原理（已对照 FreeRDP 2.x include/freerdp/client/rdpsnd.h 实现）：
 *   - FreeRDP 的 rdpsnd 通道负责“协议 + 解码”，把 Windows 传来的声音解码成 PCM；
 *     解码完成后调用本后端的 pcPlay(device, pcm, size) 回调。
 *   - 我们在这个回调里把 PCM 字节通过 JNI 交回 Kotlin 的 AudioTrack 播放，
 *     于是远端 Windows 上 NVDA 读屏的语音、系统提示音等，就被“重定向”回手机扬声器。
 *   - 这是我们专为 AccessRDP 写的后端；FreeRDP 通过 backends[] 数组（已在本仓库 CI
 *     里用补丁把 "android" 置为首选）加载 librdpsnd_android.so 并调用下面的入口函数。
 *
 * 这是视障用户的核心刚需：远端音频必须能传回手机。本文件即实现这一点，不做任何省略。
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#include <freerdp/client/rdpsnd.h>   /* rdpsndDevicePlugin, entry points */
#include <freerdp/codec/audio.h>     /* AUDIO_FORMAT */
#include <freerdp/channels/channels.h> /* CHANNEL_RC_OK / CHANNEL_RC_NO_MEMORY */

#define TAG "AccessRDP/rdpsnd"

/* 本后端设备结构：内嵌 FreeRDP 要求的 rdpsndDevicePlugin，并附带我们自己的状态。 */
typedef struct
{
	rdpsndDevicePlugin device; /* 必须是第一个成员 */
	int sampleRate;
	int channels;
	int bits;
	UINT32 volume; /* 0..0xFFFF，左右声道合并值 */
} RdpsndAndroid;

/* ---- JNI 缓存（本 .so 由 FreeRDP dlopen 加载，需自带 JNI_OnLoad 缓存 JVM）---- */
static JavaVM*   g_vm        = NULL;
static jmethodID g_onAudioData = NULL; /* (byte[], rate, channels, bits) -> void */
static jmethodID g_onAudioStart = NULL; /* () -> void */
static jmethodID g_onAudioStop  = NULL; /* () -> void */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
	(void)reserved;
	g_vm = vm;
	JNIEnv* env = NULL;
	if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;

	jclass cls = (*env)->FindClass(env, "com/accessrdp/client/jni/FreerdpJni");
	if (cls != NULL)
	{
		g_onAudioData = (*env)->GetStaticMethodID(env, cls, "onAudioData", "([BIII)V");
		g_onAudioStart = (*env)->GetStaticMethodID(env, cls, "onAudioStart", "()V");
		g_onAudioStop  = (*env)->GetStaticMethodID(env, cls, "onAudioStop", "()V");
	}
	else
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "无法找到 FreerdpJni 类");
	}
	return JNI_VERSION_1_6;
}

/* 在 FreeRDP 音频线程上安全地拿到 JNIEnv（可能需 attach）。 */
static JNIEnv* get_env(int* need_detach)
{
	JNIEnv* env = NULL;
	*need_detach = 0;
	if (g_vm == NULL)
		return NULL;
	jint res = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
	if (res == JNI_EDETACHED)
	{
		if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK)
			*need_detach = 1;
	}
	return env;
}

static jclass freerdp_jni_class(JNIEnv* env)
{
	return (*env)->FindClass(env, "com/accessrdp/client/jni/FreerdpJni");
}

/* ---- 后端回调实现 ---- */

/* 服务器询问我们支持哪些格式：返回 TRUE 表示全部接受，
 * FreeRDP 会先把音频解码成 PCM（见 pcPlay）再交给我们。 */
static BOOL android_format_supported(rdpsndDevicePlugin* device, const AUDIO_FORMAT* format)
{
	(void)device;
	(void)format;
	return TRUE;
}

/* 协商出一个格式时调用：记录采样率/声道/位深，并通知 Kotlin 准备 AudioTrack。 */
static BOOL android_open(rdpsndDevicePlugin* device, const AUDIO_FORMAT* format, UINT32 latency)
{
	RdpsndAndroid* a = (RdpsndAndroid*)device;
	(void)latency;
	if (format != NULL)
	{
		a->sampleRate = (int)format->nSamplesPerSec;
		a->channels   = (int)format->nChannels;
		a->bits       = (int)format->wBitsPerSample;
		__android_log_print(ANDROID_LOG_INFO, TAG,
		                    "音频格式打开：%dHz / %d 声道 / %d bit",
		                    a->sampleRate, a->channels, a->bits);
	}
	int detach = 0;
	JNIEnv* env = get_env(&detach);
	if (env != NULL && g_onAudioStart != NULL)
	{
		(*env)->CallStaticVoidMethod(env, freerdp_jni_class(env), g_onAudioStart);
	}
	if (detach)
		(*g_vm)->DetachCurrentThread(g_vm);
	return TRUE;
}

/* 核心：FreeRDP 把一帧解码后的 PCM 数据交到这里。我们原样回传 Kotlin 播放。
 * data 已是线性 PCM（小端），位深由 android_open 协定（通常为 16bit）。 */
static UINT android_play(rdpsndDevicePlugin* device, const BYTE* data, size_t size)
{
	RdpsndAndroid* a = (RdpsndAndroid*)device;
	if (data == NULL || size == 0)
		return CHANNEL_RC_OK;

	int detach = 0;
	JNIEnv* env = get_env(&detach);
	if (env != NULL && g_onAudioData != NULL)
	{
		jbyteArray arr = (*env)->NewByteArray(env, (jsize)size);
		if (arr != NULL)
		{
			(*env)->SetByteArrayRegion(env, arr, 0, (jsize)size, (const jbyte*)data);
			(*env)->CallStaticVoidMethod(env, freerdp_jni_class(env), g_onAudioData,
			                             arr, (jint)a->sampleRate, (jint)a->channels, (jint)a->bits);
			(*env)->DeleteLocalRef(env, arr);
		}
	}
	if (detach)
		(*g_vm)->DetachCurrentThread(g_vm);
	return CHANNEL_RC_OK;
}

/* 音量同步（来自远端 Windows 的音量设置）：仅记录，可后续映射到 AudioTrack。 */
static BOOL android_set_volume(rdpsndDevicePlugin* device, UINT32 value)
{
	RdpsndAndroid* a = (RdpsndAndroid*)device;
	a->volume = value;
	return TRUE;
}

/* 通道关闭：通知 Kotlin 停止播放并释放 AudioTrack。 */
static void android_close(rdpsndDevicePlugin* device)
{
	(void)device;
	int detach = 0;
	JNIEnv* env = get_env(&detach);
	if (env != NULL && g_onAudioStop != NULL)
	{
		(*env)->CallStaticVoidMethod(env, freerdp_jni_class(env), g_onAudioStop);
	}
	if (detach)
		(*g_vm)->DetachCurrentThread(g_vm);
}

static void android_free(rdpsndDevicePlugin* device)
{
	free(device);
}

/*
 * FreeRDP 加载后端时调用的入口（符号名固定为 freerdp_rdpsnd_client_subsystem_entry）。
 * 我们填充回调并注册设备；之后 FreeRDP 在收到音频时就会回调 android_play。
 */
UINT freerdp_rdpsnd_client_subsystem_entry(PFREERDP_RDPSND_DEVICE_ENTRY_POINTS pEntryPoints)
{
	RdpsndAndroid* a = (RdpsndAndroid*)calloc(1, sizeof(RdpsndAndroid));
	if (a == NULL)
		return CHANNEL_RC_NO_MEMORY;

	a->device.Open           = android_open;
	a->device.FormatSupported = android_format_supported;
	a->device.SetVolume      = android_set_volume;
	a->device.Play           = android_play;
	a->device.Close          = android_close;
	a->device.Free           = android_free;

	pEntryPoints->pRegisterRdpsndDevice(pEntryPoints->rdpsnd, &a->device);
	__android_log_print(ANDROID_LOG_INFO, TAG, "AccessRDP Android 音频后端已注册");
	return CHANNEL_RC_OK;
}
