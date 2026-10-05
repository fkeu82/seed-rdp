/*
 * freerdp_jni.c —— AccessRDP 的 FreeRDP JNI 桥接（C 侧主入口）
 *
 * 编译产物：libfreerdp_client.so（放到 app/src/main/jniLibs/<abi>/）
 * 对应 Kotlin 侧：com.accessrdp.client.jni.FreerdpJni
 *
 * 职责：
 *   - nativeConnect / nativeDisconnect：建立 / 断开 RDP 会话（含加载 rdpsnd 音频插件）
 *   - sendKeyDown / sendKeyUp：发送键盘扫描码（核心无障碍组合键通路）
 *   - sendMouseMove / sendMouseButton：触屏映射为鼠标（可选）
 *   - setAudioSinkEnabled：把“远端音频是否回传”的开关转给 Kotlin 的 AudioRedirect
 *   - setNativeLibraryDir：告诉 FreeRDP 去哪里找 librdpsnd_android.so（音频后端）
 *
 * 音频后端的 PCM 回调在 librdpsnd_android.so（见 rdpsnd_android.c）里完成，
 * 本文件只负责“让 FreeRDP 把音频后端加载起来”。
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/input.h>
#include <freerdp/channels/channels.h> /* freerdp_channels_load_plugin */
#include <winpr/crt.h>

#define TAG "AccessRDP/JNI"

/* ---- 全局会话与 JVM 句柄 ---- */
static JavaVM* g_vm = NULL;
static freerdp* g_instance = NULL;

/* AudioRedirect.setEnabled(boolean) 的 jmethodID（JNI_OnLoad 时缓存）。 */
static jmethodID g_setEnabled = NULL;

/* 键盘 flags（与 FreeRDP KBD_FLAGS 对齐） */
#define KBD_FLAGS_EXTENDED 0x0100
#define KBD_FLAGS_RELEASE  0x8000

/* ------------------------------------------------------------------ */
/* JNI 装载：缓存 JVM 与回调方法                                       */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
	(void)reserved;
	g_vm = vm;
	JNIEnv* env = NULL;
	if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;

	jclass cls = (*env)->FindClass(env, "com/accessrdp/client/jni/AudioRedirect");
	if (cls != NULL)
	{
		g_setEnabled = (*env)->GetStaticMethodID(env, cls, "setEnabled", "(Z)V");
	}
	else
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "无法找到 AudioRedirect 类");
	}
	return JNI_VERSION_1_6;
}

/* ------------------------------------------------------------------ */
/* 连接 / 断开                                                         */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_nativeConnect(
	JNIEnv* env, jclass clazz,
	jstring host, jint port, jstring user, jstring pass,
	jstring domain, jboolean enableAudio, jint securityLevel)
{
	(void)clazz;

	if (g_instance != NULL)
	{
		freerdp_disconnect(g_instance);
		freerdp_free(g_instance);
		g_instance = NULL;
	}

	const char* hostStr = (*env)->GetStringUTFChars(env, host, NULL);
	const char* userStr = user ? (*env)->GetStringUTFChars(env, user, NULL) : NULL;
	const char* passStr = pass ? (*env)->GetStringUTFChars(env, pass, NULL) : NULL;
	const char* domStr  = domain ? (*env)->GetStringUTFChars(env, domain, NULL) : NULL;

	freerdp* instance = freerdp_new();
	if (instance == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "freerdp_new 失败");
		goto cleanup;
	}
	freerdp_context_new(instance);

	rdpSettings* s = instance->settings;
	s->ServerHostname = _strdup(hostStr);
	s->ServerPort     = (UINT32)port;
	if (userStr) s->Username = _strdup(userStr);
	if (passStr) s->Password = _strdup(passStr);
	if (domStr)  s->Domain   = _strdup(domStr);

	/* 安全层：0=自动 1=RDP 2=TLS 3=NLA */
	s->RdpSecurity = (securityLevel == 1) ? TRUE : FALSE;
	s->TlsSecurity = (securityLevel == 2) ? TRUE : FALSE;
	s->NlaSecurity = (securityLevel == 3) ? TRUE : FALSE;
	if (securityLevel == 0)
	{
		s->RdpSecurity = TRUE;
		s->TlsSecurity = TRUE;
		s->NlaSecurity = TRUE;
	}

	/* 我们是无界面客户端：用软件 GDI 渲染（不需要真实显示也能拿到音频/输入通道）。 */
	s->SoftwareGdi  = TRUE;
	s->ColorDepth   = 16;
	s->DesktopWidth = 1024;
	s->DesktopHeight = 768;

	/* 无头环境无法弹出证书确认框，直接接受自签名证书，避免卡死在握手。 */
	s->IgnoreCertificate    = TRUE;
	s->AutoAcceptCertificate = TRUE;

	/* 开启音频回放；FreeRDP 会据此初始化 rdpsnd 通道，加载我们的 android 后端。 */
	s->AudioPlayback = enableAudio ? TRUE : FALSE;
	s->AudioCapture  = FALSE; /* 暂不开麦克风（audin） */

	/* 显式加载 rdpsnd 插件（音频输出虚拟通道）。
	 * 我们的 CI 已给 FreeRDP 的 backends[] 补丁把 "android" 置为首选，
	 * 于是 FreeRDP 会去 dlopen librdpsnd_android.so 并接管“把 PCM 传回手机”。 */
	if (freerdp_channels_load_plugin(instance->context->channels,
	                                 instance->settings, "rdpsnd", NULL) <= 0)
	{
		__android_log_print(ANDROID_LOG_WARN, TAG,
		                    "加载 rdpsnd 插件失败，远端音频可能不可用");
	}

	__android_log_print(ANDROID_LOG_INFO, TAG, "正在连接 %s:%d", hostStr, (int)port);
	BOOL ok = freerdp_connect(instance);
	if (!ok)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "freerdp_connect 失败");
		freerdp_free(instance);
		instance = NULL;
	}
	g_instance = instance;

cleanup:
	if (hostStr) (*env)->ReleaseStringUTFChars(env, host, hostStr);
	if (userStr) (*env)->ReleaseStringUTFChars(env, user, userStr);
	if (passStr) (*env)->ReleaseStringUTFChars(env, pass, passStr);
	if (domStr)  (*env)->ReleaseStringUTFChars(env, domain, domStr);

	return g_instance != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_nativeDisconnect(JNIEnv* env, jclass clazz)
{
	(void)env;
	(void)clazz;
	if (g_instance != NULL)
	{
		freerdp_disconnect(g_instance);
		freerdp_free(g_instance);
		g_instance = NULL;
	}
	return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/* 键盘：核心无障碍组合键通路                                          */
/* ------------------------------------------------------------------ */
static void send_key(UINT16 scancode, UINT16 base_flags)
{
	if (g_instance == NULL || g_instance->input == NULL)
		return;
	freerdp_input_send_keyboard_event_ex(g_instance->input, base_flags, scancode);
}

JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_sendKeyDown(JNIEnv* env, jclass clazz, jint rdpScanCode)
{
	(void)env;
	(void)clazz;
	UINT16 scancode = (UINT16)(rdpScanCode & 0x00FF);
	UINT16 flags    = (rdpScanCode & 0xE000) ? KBD_FLAGS_EXTENDED : 0;
	send_key(scancode, flags); /* 0 = 按下 */
}

JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_sendKeyUp(JNIEnv* env, jclass clazz, jint rdpScanCode)
{
	(void)env;
	(void)clazz;
	UINT16 scancode = (UINT16)(rdpScanCode & 0x00FF);
	UINT16 flags    = (rdpScanCode & 0xE000) ? KBD_FLAGS_EXTENDED : 0;
	send_key(scancode, (UINT16)(flags | KBD_FLAGS_RELEASE));
}

/* ------------------------------------------------------------------ */
/* 鼠标（触屏映射，可选）                                              */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_sendMouseMove(JNIEnv* env, jclass clazz, jint x, jint y)
{
	(void)env;
	(void)clazz;
	if (g_instance == NULL || g_instance->input == NULL)
		return;
	freerdp_input_send_mouse_event(g_instance->input, PTR_FLAGS_MOVE, (UINT16)x, (UINT16)y);
}

JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_sendMouseButton(JNIEnv* env, jclass clazz,
                                                         jint button, jboolean down)
{
	(void)env;
	(void)clazz;
	if (g_instance == NULL || g_instance->input == NULL)
		return;
	UINT16 flags = 0;
	if (button == 0)      flags = down ? (PTR_FLAGS_BUTTON1 | PTR_FLAGS_DOWN) : PTR_FLAGS_BUTTON1;
	else if (button == 1) flags = down ? (PTR_FLAGS_BUTTON2 | PTR_FLAGS_DOWN) : PTR_FLAGS_BUTTON2;
	else if (button == 2) flags = down ? (PTR_FLAGS_BUTTON3 | PTR_FLAGS_DOWN) : PTR_FLAGS_BUTTON3;
	freerdp_input_send_mouse_event(g_instance->input, flags, 0, 0);
}

/* ------------------------------------------------------------------ */
/* 音频开关 + addin 路径                                               */
/* ------------------------------------------------------------------ */

/* 把“是否回传远端音频”的开关转交给 Kotlin 的 AudioRedirect（在那里决定要不要真的写入喇叭）。 */
JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_setAudioSinkEnabled(JNIEnv* env, jclass clazz, jboolean enabled)
{
	(void)clazz;
	if (g_setEnabled != NULL)
	{
		(*env)->CallStaticVoidMethod(env,
			(*env)->FindClass(env, "com/accessrdp/client/jni/AudioRedirect"),
			g_setEnabled, enabled);
	}
}

/* 告诉 FreeRDP 去 APK 的原生库目录里找 librdpsnd_android.so（音频后端）。
 * 必须在连接前调用；路径来自 Kotlin 的 context.applicationInfo.nativeLibraryDir。 */
JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_setNativeLibraryDir(JNIEnv* env, jclass clazz, jstring dir)
{
	(void)clazz;
	if (dir == NULL)
		return;
	const char* d = (*env)->GetStringUTFChars(env, dir, NULL);
	if (d != NULL)
	{
		setenv("FREERDP_ADDIN_PATH", d, 1);
		__android_log_print(ANDROID_LOG_INFO, TAG, "FREERDP_ADDIN_PATH=%s", d);
		(*env)->ReleaseStringUTFChars(env, dir, d);
	}
}
