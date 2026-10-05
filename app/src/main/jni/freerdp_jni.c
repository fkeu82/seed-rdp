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

/* setenv() 的 POSIX 声明必须在任何头文件之前引入。Android bionic 默认已暴露，
 * 这里显式声明以在严格 C 标准模式下也稳定可用。 */
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif

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

/* 键盘扫描码：扩展位来自 Kotlin 约定的 0xE000；RDP 侧的 KBDEXT 由
 * MAKE_RDP_SCANCODE 宏（freerdp/scancode.h，经 input.h 引入）自动处理。 */
#define ACCESSRDP_SCANCODE_EXT_MASK 0xE000

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
		if (g_setEnabled == NULL)
		{
			(*env)->ExceptionClear(env);
			__android_log_print(ANDROID_LOG_WARN, TAG, "未找到 AudioRedirect.setEnabled(Z)V");
		}
		(*env)->DeleteLocalRef(env, cls);
	}
	else
	{
		/* FindClass 失败会留下挂起异常，必须清掉，否则后续 JNI 调用会莫名失败。 */
		(*env)->ExceptionClear(env);
		__android_log_print(ANDROID_LOG_ERROR, TAG, "无法找到 AudioRedirect 类");
	}
	return JNI_VERSION_1_6;
}

/* ------------------------------------------------------------------ */
/* 连接 / 断开                                                         */
/* ------------------------------------------------------------------ */
/*
 * 建立 RDP 会话。
 *
 * ⚠️ 这里是全 App 最容易「点连接直接闪退」的地方，历史崩溃点有三处，已全部修掉：
 *
 *   [崩溃点 1] 用 _strdup() 给 settings 字段赋值 —— FreeRDP 内部释放这些字段时用的是
 *              free()，而 WinPR 的 _strdup 可能走自定义分配器，堆头不一致 -> 立刻 abort。
 *              改为标准 strdup()（bionic 提供，malloc 族，与 free() 配对）。
 *
 *   [崩溃点 2] freerdp_context_new() 的返回值没检查，随后直接访问
 *              instance->context->channels —— 一旦 context 创建失败就是空指针解引用 SIGSEGV。
 *              改为检查返回值 + 校验 context/channels 再加载插件。
 *
 *   [崩溃点 3] 连接失败时 freerdp_free() 之后没有把局部变量置空、也没保证 g_instance
 *              始终处于“可安全 disconnect”的状态，下一次连点会二次 free -> double free 崩溃。
 *              改为无论成败都维护一致状态：失败立即释放并让 g_instance 保持 NULL。
 *
 * 另外所有字符串都先判空、所有步骤失败都走统一清理路径，绝不带着半初始化状态返回。
 */
JNIEXPORT jboolean JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_nativeConnect(
	JNIEnv* env, jclass clazz,
	jstring host, jint port, jstring user, jstring pass,
	jstring domain, jboolean enableAudio, jint securityLevel)
{
	(void)clazz;

	freerdp* instance = NULL;
	const char* hostStr = NULL;
	const char* userStr = NULL;
	const char* passStr = NULL;
	const char* domStr  = NULL;
	jboolean result = JNI_FALSE;

	/* ---- 0) 先安全关闭上一个会话（避免重复连接时泄漏 / 二次 free）---- */
	if (g_instance != NULL)
	{
		freerdp* old = g_instance;
		g_instance = NULL;            /* 先摘掉全局引用，再销毁，避免中途异常留下野指针 */
		freerdp_disconnect(old);
		freerdp_free(old);
	}

	/* ---- 1) 参数校验：主机为空直接失败，不让底层拿到 NULL 崩溃 ---- */
	if (host == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "主机地址为空，放弃连接");
		return JNI_FALSE;
	}
	hostStr = (*env)->GetStringUTFChars(env, host, NULL);
	if (hostStr == NULL || hostStr[0] == '\0')
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "主机地址非法，放弃连接");
		goto cleanup;
	}
	userStr = user   ? (*env)->GetStringUTFChars(env, user, NULL)   : NULL;
	passStr = pass   ? (*env)->GetStringUTFChars(env, pass, NULL)   : NULL;
	domStr  = domain ? (*env)->GetStringUTFChars(env, domain, NULL) : NULL;

	/* ---- 2) 创建实例 ---- */
	instance = freerdp_new();
	if (instance == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "freerdp_new 失败");
		goto cleanup;
	}

	/* [崩溃点 2 修复] context 创建必须检查返回值 */
	if (!freerdp_context_new(instance) || instance->context == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "freerdp_context_new 失败");
		freerdp_free(instance);
		instance = NULL;
		goto cleanup;
	}
	if (instance->settings == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "settings 为空");
		freerdp_free(instance);
		instance = NULL;
		goto cleanup;
	}

	rdpSettings* s = instance->settings;

	/* [崩溃点 1 修复] 一律用标准 strdup（malloc 族），与 FreeRDP 内部的 free() 配对。
	 * 注意：freerdp_free() 会负责释放这些字段，我们只负责“填进去”，不再自己 free。 */
	s->ServerHostname = strdup(hostStr);
	s->ServerPort     = (UINT32)((port > 0 && port <= 65535) ? port : 3389);
	if (userStr && userStr[0] != '\0') s->Username = strdup(userStr);
	if (passStr && passStr[0] != '\0') s->Password = strdup(passStr);
	if (domStr  && domStr[0]  != '\0') s->Domain   = strdup(domStr);

	/* 安全层：0=自动 1=RDP 2=TLS 3=NLA。注意 FreeRDP 要求至少启用一种安全层，
	 * 否则握手阶段会断言失败；这里对非法输入回退到 NLA。 */
	switch (securityLevel)
	{
		case 1: s->RdpSecurity = TRUE; s->TlsSecurity = FALSE; s->NlaSecurity = FALSE; break;
		case 2: s->RdpSecurity = FALSE; s->TlsSecurity = TRUE; s->NlaSecurity = FALSE; break;
		case 3: s->RdpSecurity = FALSE; s->TlsSecurity = FALSE; s->NlaSecurity = TRUE; break;
		default:
			s->RdpSecurity = TRUE; s->TlsSecurity = TRUE; s->NlaSecurity = TRUE; break;
	}

	/* 无界面客户端：软件 GDI 渲染（不需要真实显示也能拿到音频/输入通道）。 */
	s->SoftwareGdi   = TRUE;
	s->ColorDepth    = 16;
	s->DesktopWidth  = 1024;
	s->DesktopHeight = 768;

	/* 无头环境无法弹出证书确认框，直接接受自签名证书，避免卡死在握手。 */
	s->IgnoreCertificate     = TRUE;
	s->AutoAcceptCertificate = TRUE;

	/* 连接超时：避免 IP 不通时无限阻塞（用户体验 + 防止 ANR）。
	 * 单位毫秒，10 秒是局域网/公网都较合理的值。
	 * 注意：FreeRDP 2.11 只提供这两个超时设置字段，不要臆造 TcpRead/WriteTimeout。 */
	s->TcpConnectTimeout = 10000;
	s->TcpAckTimeout     = 10000;

	/* 开启音频回放；FreeRDP 会据此初始化 rdpsnd 通道，加载我们的 android 后端。 */
	s->AudioPlayback = enableAudio ? TRUE : FALSE;
	s->AudioCapture  = FALSE; /* 暂不开麦克风（audin） */

	/* ---- 3) 加载 rdpsnd 音频后端插件（失败只降级“无声”，不阻断连接）---- */
	if (instance->context->channels != NULL)
	{
		if (freerdp_channels_load_plugin(instance->context->channels,
		                                 instance->settings, "rdpsnd", NULL) <= 0)
		{
			__android_log_print(ANDROID_LOG_WARN, TAG,
			                    "加载 rdpsnd 插件失败，远端音频可能不可用");
		}
	}
	else
	{
		__android_log_print(ANDROID_LOG_WARN, TAG,
		                    "channels 为空，跳过 rdpsnd 插件加载");
	}

	/* ---- 4) 发起连接（可能阻塞，Kotlin 侧在 IO 线程调用）---- */
	__android_log_print(ANDROID_LOG_INFO, TAG, "正在连接 %s:%d", hostStr, (int)s->ServerPort);
	BOOL ok = FALSE;
	ok = freerdp_connect(instance);

	if (!ok)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG,
		                    "freerdp_connect 失败：%s:%d",
		                    hostStr, (int)s->ServerPort);
		/* [崩溃点 3 修复] 失败路径彻底释放，绝不留半初始化实例在全局 */
		freerdp_disconnect(instance);
		freerdp_free(instance);
		instance = NULL;
		goto cleanup;
	}

	/* 成功：把实例交给全局，供后续键盘/断开使用 */
	g_instance = instance;
	instance = NULL;                 /* 防止 cleanup 误释放已移交的实例 */
	result = JNI_TRUE;
	__android_log_print(ANDROID_LOG_INFO, TAG, "连接成功 %s:%d", hostStr, (int)s->ServerPort);

cleanup:
	if (hostStr) (*env)->ReleaseStringUTFChars(env, host, hostStr);
	if (userStr) (*env)->ReleaseStringUTFChars(env, user, userStr);
	if (passStr) (*env)->ReleaseStringUTFChars(env, pass, passStr);
	if (domStr)  (*env)->ReleaseStringUTFChars(env, domain, domStr);

	/* 兜底：任何异常路径下若还持有未移交的实例，这里释放掉。 */
	if (instance != NULL)
	{
		freerdp_disconnect(instance);
		freerdp_free(instance);
	}

	return result;
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
/* 发送一次键盘事件。
 *
 * FreeRDP 2.x 的 freerdp_input_send_keyboard_event_ex(rdpInput*, BOOL down, UINT32 rdp_scancode)
 * 的第三个参数是“已按 RDP 约定组装好的 rdp_scancode”，不是裸扫描码：
 *   - 低 8 位 = 扫描码（RDP_SCANCODE_CODE）
 *   - KBDEXT(0x0100) 位 = 是否扩展键（RDP_SCANCODE_EXTENDED）
 * down 单独控制按下(true)/抬起(false)，函数内部会自行加上 KBD_FLAGS_DOWN/RELEASE
 * 与 KBD_FLAGS_EXTENDED。故这里用 MAKE_RDP_SCANCODE 组装，绝不能再自己塞 flags。
 *
 * @param rdpScanCode 来自 Kotlin：低 8 位扫描码，0xE000 位表示扩展键（如方向键/Insert 等）
 * @param down        TRUE=按下，FALSE=抬起
 */
static void send_key(jint rdpScanCode, BOOL down)
{
	if (g_instance == NULL || g_instance->input == NULL)
		return;

	UINT16 code     = (UINT16)(rdpScanCode & 0x00FF);
	BOOL   extended = (rdpScanCode & ACCESSRDP_SCANCODE_EXT_MASK) ? TRUE : FALSE;
	UINT32 rdpScancode = (UINT32)MAKE_RDP_SCANCODE(code, extended);

	freerdp_input_send_keyboard_event_ex(g_instance->input, down, rdpScancode);
}

JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_sendKeyDown(JNIEnv* env, jclass clazz, jint rdpScanCode)
{
	(void)env;
	(void)clazz;
	send_key(rdpScanCode, TRUE); /* TRUE = 按下 */
}

JNIEXPORT void JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_sendKeyUp(JNIEnv* env, jclass clazz, jint rdpScanCode)
{
	(void)env;
	(void)clazz;
	send_key(rdpScanCode, FALSE); /* FALSE = 抬起 */
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
	if (g_setEnabled == NULL)
		return;
	jclass cls = (*env)->FindClass(env, "com/accessrdp/client/jni/AudioRedirect");
	if (cls == NULL)
	{
		(*env)->ExceptionClear(env);
		return;
	}
	(*env)->CallStaticVoidMethod(env, cls, g_setEnabled, enabled);
	(*env)->DeleteLocalRef(env, cls);
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
