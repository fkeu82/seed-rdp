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
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/input.h>
#include <freerdp/error.h>            /* freerdp_get_last_error / _string */
#include <freerdp/channels/channels.h> /* freerdp_channels_load_plugin */
#include <winpr/crt.h>

#define TAG "AccessRDP/JNI"

/* ---- 全局会话与 JVM 句柄 ---- */
static JavaVM* g_vm = NULL;
static freerdp* g_instance = NULL;

/* AudioRedirect.setEnabled(boolean) 的 jmethodID（JNI_OnLoad 时缓存）。 */
static jmethodID g_setEnabled = NULL;

/* 最近一次连接失败的详细信息（供 Kotlin 通过 nativeGetLastError 取回并播报）。 */
static char g_last_error[1024] = { 0 };

/* 键盘扫描码：扩展位来自 Kotlin 约定的 0xE000；RDP 侧的 KBDEXT 由
 * MAKE_RDP_SCANCODE 宏（freerdp/scancode.h，经 input.h 引入）自动处理。 */
#define ACCESSRDP_SCANCODE_EXT_MASK 0xE000

/* ------------------------------------------------------------------ */
/* 证书校验回调                                                        */
/*                                                                     */
/* 【关键修复】这是 v1.0.3 连不上真实主机的头号原因。                   */
/*                                                                     */
/* FreeRDP 2.11 的 libfreerdp/crypto/tls.c 中，当服务器的证书不在      */
/* known_hosts 文件里时，会按以下顺序决定是否接受：                     */
/*   1. AutoAcceptCertificate == TRUE  -> 接受                         */
/*   2. AutoDenyCertificate  == TRUE   -> 拒绝                         */
/*   3. 调用 VerifyX509Certificate / VerifyCertificateEx 回调           */
/*   4. 都没有 -> accept_certificate = 0（拒绝）-> 连接失败             */
/*                                                                     */
/* 我们此前只设了 IgnoreCertificate，在 2.11 里并不足以自动接受未信任   */
/* 证书（Windows 自签名/RDP 默认证书就属于此类），所以必然握手失败。     */
/* 这里按官方示例补上回调，返回 1（接受并记住），确保能连上。            */
/* ------------------------------------------------------------------ */

/** 未信任证书：直接接受并存储（无界面客户端无法弹窗确认）。 */
static DWORD android_verify_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                           const char* common_name, const char* subject,
                                           const char* issuer, const char* fingerprint,
                                           DWORD flags)
{
	(void)instance;
	(void)flags;
	(void)common_name;
	__android_log_print(ANDROID_LOG_WARN, TAG,
	                    "接受未信任证书 %s:%u subject=%s issuer=%s fp=%s",
	                    host ? host : "?", (unsigned)port,
	                    subject ? subject : "?", issuer ? issuer : "?",
	                    fingerprint ? fingerprint : "?");
	return 1; /* 1 = 接受并存入 known_hosts */
}

/** 证书发生变化：同样接受（无界面客户端无法询问用户）。 */
static DWORD android_verify_changed_certificate_ex(
    freerdp* instance, const char* host, UINT16 port, const char* common_name,
    const char* subject, const char* issuer, const char* new_fingerprint,
    const char* old_subject, const char* old_issuer, const char* old_fingerprint,
    DWORD flags)
{
	(void)instance;
	(void)flags;
	(void)common_name;
	(void)issuer;
	(void)old_subject;
	(void)old_issuer;
	(void)old_fingerprint;
	__android_log_print(ANDROID_LOG_WARN, TAG,
	                    "证书已变更，接受新证书 %s:%u subject=%s new_fp=%s",
	                    host ? host : "?", (unsigned)port,
	                    subject ? subject : "?", new_fingerprint ? new_fingerprint : "?");
	return 1;
}

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

	/* 【需求 1】入口追踪日志：证明 nativeConnect 确实被调用、参数是什么。
	 * 用于排查「Kotlin 侧是否真的走到了原生连接」这类问题。 */
	__android_log_print(ANDROID_LOG_INFO, TAG,
	                    ">>> JNI nativeConnect 被调用 (port=%d, secLevel=%d)",
	                    (int)port, (int)securityLevel);

	/* 每次连接先清空上一次的错误描述，避免旧错误被误播报。 */
	g_last_error[0] = '\0';
	jboolean result = JNI_FALSE;

	/* ---- 0) 先安全关闭上一个会话（避免重复连接时泄漏 / 二次 free）---- */
	if (g_instance != NULL)
	{
		freerdp* old = g_instance;
		g_instance = NULL;            /* 先摘掉全局引用，再销毁，避免中途异常留下野指针 */
		freerdp_disconnect(old);
		freerdp_free(old);
	}

	/* ---- 1) 参数校验：主机为空直接失败，不让底层拿到 NULL 崩溃 ----
	 *
	 * 【v1.1.2 修复】以前这些提前返回分支只打 log、不写 g_last_error，
	 * 导致 Kotlin 侧 nativeGetLastError() 拿到空串，UI 只能显示
	 * 「连接失败，请检查网络、端口是否正确…」这种无信息量的兜底文案，
	 * 把「参数非法」误报成「网络问题」。现在每条提前返回路径都必须
	 * 写 g_last_error，保证失败原因一路带到 UI。 */
	if (host == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "主机地址为空，放弃连接");
		snprintf(g_last_error, sizeof(g_last_error),
		         "主机地址为空（JNI 收到 NULL）。请在连接界面填写目标 IP 或主机名。");
		return JNI_FALSE;
	}
	hostStr = (*env)->GetStringUTFChars(env, host, NULL);
	if (hostStr == NULL || hostStr[0] == '\0')
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "主机地址非法，放弃连接");
		snprintf(g_last_error, sizeof(g_last_error),
		         "主机地址为空字符串，无法连接。请填写目标 IP 或主机名。");
		goto cleanup;
	}
	userStr = user   ? (*env)->GetStringUTFChars(env, user, NULL)   : NULL;
	passStr = pass   ? (*env)->GetStringUTFChars(env, pass, NULL)   : NULL;
	domStr  = domain ? (*env)->GetStringUTFChars(env, domain, NULL) : NULL;

	/* ---- 1.5) 【已移除 TCP 预检】----
	 *
	 * 历史：v1.0.6 曾在此处先做一次独立的 TCP 预检（connect + close，不发任何数据）。
	 *
	 * 【为什么删掉】这个预检有严重副作用：
	 *   它会在目标服务器的连接日志里留下一条「连接进来、但一个字节都没发就断开」
	 *   的幽灵记录，极易被误判为「客户端不发 RDP 握手包」的 bug。
	 *   实测（Linux 版 FreeRDP 对照实验）证明：真正的 freerdp_connect() 会
	 *   正常发出 46 字节的 X.224 Connection Request + RDP Negotiation Request
	 *   （0300002e29e00000000000...0100080003000000），握手流程完全正常。
	 *
	 *   因此预检属于「制造问题假象」的多余步骤，已彻底删除。
	 *
	 * 现在改为：直接交给 FreeRDP 连接；若失败，再用 freerdp_get_last_error_* 
	 * 拿到真实错误码并给出分类提示（见下方 !ok 分支）。 */

	/* ---- 2) 创建实例 ---- */
	__android_log_print(ANDROID_LOG_INFO, TAG,
	                    "步骤 2/6：freerdp_new() 创建实例…");
	instance = freerdp_new();
	if (instance == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "freerdp_new 失败");
		snprintf(g_last_error, sizeof(g_last_error),
		         "内部错误：FreeRDP 实例创建失败（freerdp_new 返回 NULL），"
		         "连接未能发起。这通常意味着原生库未正确加载或内存不足。");
		goto cleanup;
	}

	/* [崩溃点 2 修复] context 创建必须检查返回值 */
	__android_log_print(ANDROID_LOG_INFO, TAG,
	                    "步骤 3/6：freerdp_context_new() 创建上下文…");
	if (!freerdp_context_new(instance) || instance->context == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "freerdp_context_new 失败");
		snprintf(g_last_error, sizeof(g_last_error),
		         "内部错误：FreeRDP 上下文创建失败（freerdp_context_new），"
		         "连接未能发起。请卸载后重装本应用；若仍失败请反馈此错误码。");
		freerdp_free(instance);
		instance = NULL;
		goto cleanup;
	}
	if (instance->settings == NULL)
	{
		__android_log_print(ANDROID_LOG_ERROR, TAG, "settings 为空");
		snprintf(g_last_error, sizeof(g_last_error),
		         "内部错误：FreeRDP 配置对象为空（settings == NULL），连接未能发起。");
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

	/* 安全层：0=自动 1=RDP 2=TLS 3=NLA。
	 *
	 * 【关键修复】必须显式打开 NegotiateSecurityLayer / Authentication，
	 * 这两项是官方 xfreerdp 的默认值（-nego / -authentication 默认 on），
	 * 但 freerdp_new() + freerdp_context_new() 的裸实例并不会帮我们设好它们：
	 *   - NegotiateSecurityLayer=FALSE 时不会做 X.224 安全协商，和现代 Windows 谈不拢；
	 *   - Authentication=FALSE 时 NLA/CredSSP 无从进行，直接被服务器拒绝。
	 * 这是 v1.0.3「IP 密码都对却连不上」的核心原因之一。
	 *
	 * 注：RDP 的加密级别（EncryptionLevel/Methods）仅在经典的 RDP 安全层下才有意义，
	 * NLA/TLS 下由 TLS 负责加密，无需手动设置。 */
	s->NegotiateSecurityLayer = TRUE;
	s->Authentication        = TRUE;
	s->ExtSecurity           = FALSE;

	/* 按用户选择设置可用的安全层组合；默认(0/其它)全开以便自动协商。 */
	switch (securityLevel)
	{
		case 1:
			s->RdpSecurity = TRUE;
			s->TlsSecurity = FALSE;
			s->NlaSecurity = FALSE;
			break;
		case 2:
			s->RdpSecurity = FALSE;
			s->TlsSecurity = TRUE;
			s->NlaSecurity = FALSE;
			break;
		case 3:
			s->RdpSecurity = FALSE;
			s->TlsSecurity = TRUE;   /* 保留 TLS：NLA 失败时仍可回退 */
			s->NlaSecurity = TRUE;
			break;
		default:
			/* 自动：三种全开，由 NegotiateSecurityLayer 与服务器协商最优方案 */
			s->RdpSecurity = TRUE;
			s->TlsSecurity = TRUE;
			s->NlaSecurity = TRUE;
			break;
	}

	/* 【关键修复】强制 TLS 1.2+。
	 * 现代 Windows（2016 以后 / 已打补丁的 2012 R2+）在 NLA 下普遍要求 TLS1.2，
	 * 而 FreeRDP 默认可能按 TLS1.0 协商 -> 服务器直接拒绝握手。
	 * 0x0303 = TLS1.2，0x0304 = TLS1.3。官方对应的是 +enforce-tlsv1_2。 */
	s->TLSMinVersion = 0x0303;
	s->TLSMaxVersion = 0x0304;
	s->TlsSecLevel   = 1;

	/* 【关键修复】证书回调。
	 * 官方 tls.c 在证书未被信任时会调用这两个回调，返回 1 表示接受。
	 * 若不设置，且 AutoAcceptCertificate 为 FALSE，则一定拒绝 -> 连接失败。 */
	instance->VerifyCertificateEx        = android_verify_certificate_ex;
	instance->VerifyChangedCertificateEx = android_verify_changed_certificate_ex;

	/* 无界面环境无法弹出证书确认框：自动接受自签名证书，避免卡死在握手。
	 * 与上面的回调形成双保险（回调未触发时 AutoAccept 兜底）。 */
	s->IgnoreCertificate     = TRUE;
	s->AutoAcceptCertificate = TRUE;
	s->AutoDenyCertificate   = FALSE;

	/* 无界面客户端：软件 GDI 渲染（不需要真实显示也能拿到音频/输入通道）。 */
	s->SoftwareGdi   = TRUE;
	s->ColorDepth    = 16;
	s->DesktopWidth  = 1024;
	s->DesktopHeight = 768;

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
	__android_log_print(ANDROID_LOG_INFO, TAG,
	                    "=== 开始真实 RDP 连接 === 目标=%s:%d 用户=%s 域=%s 安全级别=%d 音频=%d",
	                    hostStr, (int)s->ServerPort,
	                    (userStr != NULL && userStr[0] != '\0') ? userStr : "(空)",
	                    (domStr != NULL && domStr[0] != '\0') ? domStr : "(空)",
	                    (int)securityLevel, enableAudio ? 1 : 0);

	/* 【需求 2/3】打印实际生效的安全层配置，证明 TLS/NLA 标志到底开没开。
	 * 若 TlsSecurity/NlaSecurity 全为 FALSE，FreeRDP 会只发裸 X.224 CR；
	 * 若为 TRUE，则会在协商后主动发起 TLS ClientHello。 */
	__android_log_print(ANDROID_LOG_INFO, TAG,
	                    "安全层生效配置: RdpSecurity=%d TlsSecurity=%d NlaSecurity=%d "
	                    "NegotiateSecurityLayer=%d Authentication=%d ExtSecurity=%d "
	                    "TLSMin=0x%04X TLSMax=0x%04X",
	                    s->RdpSecurity, s->TlsSecurity, s->NlaSecurity,
	                    s->NegotiateSecurityLayer, s->Authentication, s->ExtSecurity,
	                    (unsigned)s->TLSMinVersion, (unsigned)s->TLSMaxVersion);

	/* 记录连接耗时，便于判断是"秒失败"（TCP 层被拒）还是"卡到超时"（被丢包） */
	struct timespec tsStart, tsEnd;
	clock_gettime(CLOCK_MONOTONIC, &tsStart);

	/* 【需求 2】TCP connect 之前的最后一道标记。
	 * 若 logcat 里看到了这一行、但服务器端没有任何连接记录，
	 * 说明 socket 建立阶段（freerdp_tcp_connect -> DNS/getaddrinfo/connect）
	 * 在设备侧就失败了 —— 而不是握手包发错。 */
	__android_log_print(ANDROID_LOG_INFO, TAG,
	                    "步骤 6/6：即将调用 freerdp_connect() —— "
	                    "若此后 logcat 无更多输出且服务器无连接，问题在 TCP/DNS 层。 "
	                    "目标 %s:%d", hostStr, (int)s->ServerPort);

	BOOL ok = FALSE;
	ok = freerdp_connect(instance);

	clock_gettime(CLOCK_MONOTONIC, &tsEnd);
	long elapsedMs = (tsEnd.tv_sec - tsStart.tv_sec) * 1000L
	               + (tsEnd.tv_nsec - tsStart.tv_nsec) / 1000000L;

	/* 【需求 3】真实连接日志：无论成功失败，都必须打印
	 * IP、端口、freerdp_connect 返回值、freerdp_get_last_error_string、耗时。 */
	__android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
	                    "=== freerdp_connect 返回 === 目标=%s:%d 返回值=%s 耗时=%ldms "
	                    "错误码=0x%08X 名称=%s 错误描述=%s",
	                    hostStr, (int)s->ServerPort,
	                    ok ? "TRUE" : "FALSE", elapsedMs,
	                    (unsigned)freerdp_get_last_error(instance->context),
	                    freerdp_get_last_error_name(
	                        freerdp_get_last_error(instance->context)),
	                    freerdp_get_last_error_string(
	                        freerdp_get_last_error(instance->context)));

	if (!ok)
	{
		/* 【关键】把 FreeRDP 底层的真实错误码/错误串带回来，
		 * 而不是只给用户一句模糊的“连接失败”。
		 * freerdp_get_last_error() 返回错误码（如 FREERDP_ERROR_TLS_CONNECT_FAILED），
		 * freerdp_get_last_error_string() 返回人类可读描述。
		 *
		 * ⚠️ 注意：FreeRDP 在 TCP 层失败后会走内部 reconnect 重试，重试路径中
		 *    有 set_last_error_log(context, 0) 会把错误码清成 0，此时
		 *    freerdp_get_last_error_string(0) 只会返回 "UNKNOWN [0x00000000]"。
		 *    所以下面会对「错误码为 0 / 串无意义」的情况单独处理，
		 *    结合我们预检阶段拿到的结果给出可指导排查的中文提示。 */
		UINT32 errCode = freerdp_get_last_error(instance->context);
		const char* errName = freerdp_get_last_error_name(errCode);
		const char* errStr  = freerdp_get_last_error_string(errCode);
		const char* errCat  = freerdp_get_last_error_category(errCode);

		__android_log_print(ANDROID_LOG_ERROR, TAG,
		                    "freerdp_connect 失败 %s:%d 错误码=0x%08X 名称=%s 分类=%s 描述=%s",
		                    hostStr, (int)s->ServerPort,
		                    (unsigned)errCode,
		                    errName ? errName : "?",
		                    errCat ? errCat : "?",
		                    errStr ? errStr : "?");

		/* 按 FreeRDP 的**真实错误码**分类给出可指导排查的中文提示。
		 *
		 * 重要：这里不再假设"TCP 必然已通"（旧版依赖已删除的 TCP 预检做此假设，
		 * 是错的）。现在完全依据 FreeRDP 返回的 errCode / errStr 判断卡在哪一层。
		 *
		 * 常见错误码（winpr/error.h）：
		 *   0x0002000D ERRCONNECT_CONNECT_TRANSPORT_FAILED      -> TCP 层就没连上
		 *   0x0002000C ERRCONNECT_SECURITY_NEGO_CONNECT_FAILED  -> 安全协商失败
		 *   0x00020009 ERRCONNECT_AUTHENTICATION_FAILED         -> 认证失败
		 *   0x0002000E/0F ... TLS/证书相关
		 */
		BOOL isTransportFailed = (errCode == 0x0002000D);   /* CONNECT_TRANSPORT_FAILED */
		BOOL isNegoFailed      = (errCode == 0x0002000C);   /* SECURITY_NEGO_CONNECT_FAILED */

		if (isTransportFailed)
		{
			/* TCP 层失败：连接根本没建立（端口关闭 / IP 不通 / 超时 / 被防火墙拦）。
			 *
			 * 【v1.1.2】结合耗时区分两种截然不同的情况，方便用户一步定位：
			 *   - < 1s 秒失败：SYN 被立即拒绝（RST）-> 端口没开 / 被安全组拒绝
			 *   - 约 10s 或更长：SYN 被静默丢弃 -> IP 不可达 / 被防火墙 DROP / 超时 */
			if (elapsedMs < 1500)
			{
				snprintf(g_last_error, sizeof(g_last_error),
				         "无法连接 %s:%d —— TCP 连接被立即拒绝（%ldms，%s）。"
				         "含义：目标主机可达，但该端口没有服务在监听，或被防火墙/云安全组"
				         "主动拒绝。请确认：1) 目标服务器上该端口的监听程序确实在运行；"
				         "2) 云服务器安全组已放行该端口；3) 服务器本机防火墙已放行该端口。",
				         hostStr, (int)s->ServerPort, elapsedMs,
				         (errStr && errStr[0]) ? errStr : "transport failed");
			}
			else
			{
				snprintf(g_last_error, sizeof(g_last_error),
				         "无法连接 %s:%d —— TCP 连接超时（%ldms，%s）。"
				         "含义：发出 SYN 后没有收到任何回应，通常是 SYN 被防火墙静默丢弃。"
				         "请确认：1) 目标 IP 是否可达（先用手机浏览器访问 "
				         "http://%s:%d 试试）；2) 云安全组/防火墙是否放行该端口；"
				         "3) 手机当前网络是否允许访问该公网地址。",
				         hostStr, (int)s->ServerPort, elapsedMs,
				         (errStr && errStr[0]) ? errStr : "transport failed",
				         hostStr, (int)s->ServerPort);
			}
		}
		else if (isNegoFailed)
		{
			/* TCP 通了，但安全层协商失败。
			 * 注意：这**不代表**对方是 RDP 服务器 —— 任何只接受 TCP 却不懂
			 * RDP 协议的程序（如普通 HTTP 服务、自写监听脚本）都会走到这里。 */
			snprintf(g_last_error, sizeof(g_last_error),
			         "已连上 %s:%d，但 RDP 安全协商失败。"
			         "可能原因：1) 该端口不是远程桌面端口（对方不是 RDP 服务）；"
			         "2) 服务器与客户端安全级别不匹配，请尝试切换「安全级别」(NLA/TLS/RDP)；"
			         "3) 服务器返回的数据不符合 RDP 协议。", hostStr, (int)s->ServerPort);
		}
		else if (strstr(errStr ? errStr : "", "AUTHENTICATION") != NULL ||
		         strstr(errStr ? errStr : "", "LOGON") != NULL ||
		         strstr(errStr ? errStr : "", "PASSWORD") != NULL ||
		         strstr(errStr ? errStr : "", "CREDENTIAL") != NULL)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "认证失败：用户名或密码不正确。"
			         "请确认账号密码无误；若域名/工作组有要求，用户名可写成「域\\用户名」或「主机名\\用户名」"
			         "（底层：%s）", errStr);
		}
		else if (strstr(errStr ? errStr : "", "TLS") != NULL ||
		         strstr(errStr ? errStr : "", "CERTIFICATE") != NULL ||
		         strstr(errStr ? errStr : "", "SECURITY") != NULL)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "安全层握手失败：%s。"
			         "请尝试在界面切换「安全级别」（NLA / TLS / RDP）后重试。", errStr);
		}
		else if (errStr == NULL || errStr[0] == '\0' ||
		         strncmp(errStr, "UNKNOWN", 7) == 0)
		{
			/* 无有效错误串：FreeRDP 内部重试会把错误码清 0，此时只能给综合提示 */
			snprintf(g_last_error, sizeof(g_last_error),
			         "连接 %s:%d 失败（FreeRDP 未返回具体错误码 0x%08X）。"
			         "若该端口并非真正的远程桌面服务，或安全级别不匹配，均会失败 —— "
			         "请确认目标确实开启了 RDP，并尝试切换「安全级别」重试。",
			         hostStr, (int)s->ServerPort, (unsigned)errCode);
		}
		else
		{
			/* 有有效错误码：原样带出，并附中文说明 */
			snprintf(g_last_error, sizeof(g_last_error),
			         "连接失败：%s（错误码 0x%08X%s%s）",
			         errStr, (unsigned)errCode,
			         errName ? " " : "", errName ? errName : "");
		}

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

	/* 【v1.1.2 修复】最终防线：走到这里若 g_last_error 仍为空，说明
	 * 失败发生在某个未显式写错误的路径上。绝不能把空串交给 Kotlin，
	 * 否则 UI 只能显示无信息量的兜底文案，把内部错误误报成网络问题。
	 * 这里补一条带错误码的通用说明，保证 UI 永远拿到可播报的原因。 */
	if (result == JNI_FALSE && g_last_error[0] == '\0')
	{
		snprintf(g_last_error, sizeof(g_last_error),
		         "连接 %s 未能发起（原生层未返回具体错误）。"
		         "可能是内部初始化失败，请卸载后重装本应用并重试。",
		         hostStr ? hostStr : "(未知主机)");
		__android_log_print(ANDROID_LOG_ERROR, TAG,
		                    "⚠️ 失败路径未写入 g_last_error，已补兜底文案（这是代码缺陷，请反馈）");
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

/*
 * 取回最近一次连接失败的详细描述（由 nativeConnect 失败时写入）。
 * 返回 UTF-8 字符串；无错误时返回空串。供 Kotlin 用 TalkBack 播报真实原因。
 */
JNIEXPORT jstring JNICALL
Java_com_accessrdp_client_jni_FreerdpJni_nativeGetLastError(JNIEnv* env, jclass clazz)
{
	(void)clazz;
	return (*env)->NewStringUTF(env, g_last_error);
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
