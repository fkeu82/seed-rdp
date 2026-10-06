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
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
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

/* ------------------------------------------------------------------ */
/* 【自诊断】连接前的 TCP 预检                                          */
/*                                                                     */
/* 为什么需要它：                                                       */
/*   FreeRDP 的 freerdp_connect() 在 TCP 层失败时，内部会走            */
/*   freerdp_reconnect() 重试，而重试路径里有一句                       */
/*   freerdp_set_last_error_log(context, 0) —— 会把 last_error 清成 0。 */
/*   结果就是：连接明明失败，freerdp_get_last_error() 却返回 0，        */
/*   我们只能给用户播报一句无用的「底层未返回具体原因」。               */
/*                                                                     */
/*   与其依赖 FreeRDP 不可靠的错误码，不如在调用它之前，**自己先探一次 */
/*   TCP**。这样能拿到操作系统层面最精确的失败原因：                    */
/*     - 域名解析失败        -> 无法解析主机地址                       */
/*     - 连接被拒(REFUSED)   -> 端口上没有服务在监听（最常见！）         */
/*     - 连接超时(TIMEOUT)   -> 被防火墙/云安全组丢包，或主机不可达      */
/*     - 连接成功            -> TCP 层没问题，继续交给 FreeRDP 做 RDP 握手 */
/*                                                                     */
/* 返回：0 = TCP 可达（继续）；非 0 = 已失败，g_last_error 已写好。      */
/* ------------------------------------------------------------------ */
static int tcp_preflight(const char* host, int port, int timeoutMs)
{
	struct addrinfo hints;
	struct addrinfo* res = NULL;
	char portStr[16];
	int rc;
	int fd = -1;
	int result = -9;

	if (host == NULL || host[0] == '\0')
	{
		snprintf(g_last_error, sizeof(g_last_error), "主机地址为空");
		return -1;
	}
	if (port <= 0 || port > 65535)
	{
		snprintf(g_last_error, sizeof(g_last_error),
		         "端口号非法（%d），应为 1-65535", port);
		return -1;
	}

	snprintf(portStr, sizeof(portStr), "%d", port);
	memset(&hints, 0, sizeof(hints));
	hints.ai_family   = AF_UNSPEC;
	hints.ai_socktype = SOCK_STREAM;

	/* --- 第一步：解析主机 --- */
	rc = getaddrinfo(host, portStr, &hints, &res);
	if (rc != 0 || res == NULL)
	{
		snprintf(g_last_error, sizeof(g_last_error),
		         "无法解析主机地址「%s」（%s）。请检查 IP 是否写错，或改用 IP 直连",
		         host, gai_strerror(rc));
		__android_log_print(ANDROID_LOG_ERROR, TAG,
		                    "预检失败：DNS 解析 %s 失败 rc=%d (%s)", host, rc, gai_strerror(rc));
		if (res) freeaddrinfo(res);
		return -1;
	}

	/* --- 第二步：逐个地址尝试 TCP 连接（非阻塞 + select 实现超时） --- */
	{
		struct addrinfo* ai;
		int lastErrno = 0;
		int tried = 0;

		for (ai = res; ai != NULL; ai = ai->ai_next)
		{
			int flags;
			fd_set wfds;
			struct timeval tv;
			int soerr = 0;
			socklen_t soerrLen = sizeof(soerr);
			char ipbuf[64] = { 0 };

			if (ai->ai_family != AF_INET && ai->ai_family != AF_INET6)
				continue;

			tried++;

			if (ai->ai_family == AF_INET)
			{
				struct sockaddr_in* v4 = (struct sockaddr_in*)ai->ai_addr;
				inet_ntop(AF_INET, &v4->sin_addr, ipbuf, sizeof(ipbuf));
			}
			else
			{
				struct sockaddr_in6* v6 = (struct sockaddr_in6*)ai->ai_addr;
				inet_ntop(AF_INET6, &v6->sin6_addr, ipbuf, sizeof(ipbuf));
			}

			fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
			if (fd < 0)
			{
				lastErrno = errno;
				continue;
			}

			/* 设为非阻塞，交给 select 控制超时 */
			flags = fcntl(fd, F_GETFL, 0);
			fcntl(fd, F_SETFL, flags | O_NONBLOCK);

			rc = connect(fd, ai->ai_addr, (socklen_t)ai->ai_addrlen);
			if (rc == 0)
			{
				/* 立刻连上（本机/内网常见） */
				result = 0;
				close(fd);
				fd = -1;
				goto done;
			}

			if (errno != EINPROGRESS)
			{
				lastErrno = errno;
				close(fd);
				fd = -1;
				continue;
			}

			/* 等待可写（连接完成或失败） */
			FD_ZERO(&wfds);
			FD_SET(fd, &wfds);
			tv.tv_sec  = timeoutMs / 1000;
			tv.tv_usec = (timeoutMs % 1000) * 1000;

			rc = select(fd + 1, NULL, &wfds, NULL, &tv);
			if (rc <= 0)
			{
				/* 超时 */
				snprintf(g_last_error, sizeof(g_last_error),
				         "连接 %s:%d 超时（目标 %s）。网络不可达，或该端口被防火墙/云安全组拦截。",
				         host, port, ipbuf);
				__android_log_print(ANDROID_LOG_ERROR, TAG,
				                    "预检失败：连接 %s:%d (%s) 超时", host, port, ipbuf);
				close(fd);
				fd = -1;
				result = -2;
				goto done;
			}

			/* 可写：检查 SO_ERROR 判断成功还是被拒 */
			if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &soerrLen) < 0)
				soerr = errno;

			if (soerr == 0)
			{
				result = 0;
				close(fd);
				fd = -1;
				goto done;
			}

			/* 记录本地址的失败原因，继续尝试下一个地址 */
			lastErrno = soerr;
			close(fd);
			fd = -1;

			if (soerr == ECONNREFUSED)
			{
				snprintf(g_last_error, sizeof(g_last_error),
				         "服务器 %s 的 %d 端口拒绝连接（Connection refused）。"
				         "说明该端口上没有任何程序在监听 —— 端口号很可能填错了，"
				         "请确认远程桌面服务真正监听的端口（Windows 默认是 3389）。",
				         host, port);
			}
			else if (soerr == EHOSTUNREACH || soerr == ENETUNREACH)
			{
				snprintf(g_last_error, sizeof(g_last_error),
				         "无法到达主机 %s（%s）。请检查网络连接。", host, ipbuf);
			}
			else if (soerr == ETIMEDOUT)
			{
				snprintf(g_last_error, sizeof(g_last_error),
				         "连接 %s:%d 超时。可能被防火墙/云安全组拦截。", host, port);
			}
			else
			{
				snprintf(g_last_error, sizeof(g_last_error),
				         "连接 %s:%d 失败（%s）。", host, port, strerror(soerr));
			}
			result = -2;
		}

		if (tried == 0)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "主机「%s」没有可用的 IPv4/IPv6 地址。", host);
			result = -1;
		}
		else if (result == -9)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "连接 %s:%d 失败（%s）。", host, port,
			         lastErrno ? strerror(lastErrno) : "未知原因");
			result = -2;
		}
	}

done:
	if (fd >= 0) close(fd);
	if (res) freeaddrinfo(res);

	if (result == 0)
	{
		__android_log_print(ANDROID_LOG_INFO, TAG,
		                    "预检通过：TCP %s:%d 可达，继续 RDP 握手", host, port);
	}
	return result;
}

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

	/* ---- 1.5) 【关键】连接前先做 TCP 预检 ----
	 *
	 * 理由见 tcp_preflight() 上方注释：FreeRDP 在 TCP 失败后会内部重试并把
	 * last_error 清成 0，导致用户只能看到一句「底层未返回具体原因」。
	 * 这里我们自己先探一次，拿到操作系统层面最精确的失败原因。
	 *
	 * 预检不通过就直接返回，既省掉 FreeRDP 内部漫长的重试等待，
	 * 也能给用户一句真正有用的中文提示。 */
	{
		int pre = tcp_preflight(hostStr, (int)port, 10000);
		if (pre != 0)
		{
			__android_log_print(ANDROID_LOG_ERROR, TAG,
			                    "TCP 预检失败 %s:%d -> %s", hostStr, (int)port, g_last_error);
			goto cleanup;   /* g_last_error 已被预检写好，直接带回 Kotlin 播报 */
		}
	}

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

	BOOL ok = FALSE;
	ok = freerdp_connect(instance);

	/* 【需求 3】真实连接日志：无论成功失败，都必须打印
	 * IP、端口、freerdp_connect 返回值、freerdp_get_last_error_string。 */
	__android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
	                    "=== freerdp_connect 返回 === 目标=%s:%d 返回值=%s 错误码=0x%08X 错误描述=%s",
	                    hostStr, (int)s->ServerPort,
	                    ok ? "TRUE" : "FALSE",
	                    (unsigned)freerdp_get_last_error(instance->context),
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

		/* errStr 为空、或是无意义的 UNKNOWN[0x0]，说明 FreeRDP 没能给出有效原因。
		 * 此时 TCP 已经通了（预检通过才会走到这里），所以问题一定出在
		 * RDP 协议/安全层/认证阶段，据此给一段有指向性的提示。 */
		if (errStr == NULL || errStr[0] == '\0' ||
		    strncmp(errStr, "UNKNOWN", 7) == 0)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "已连上 %s:%d，但远程桌面握手失败。"
			         "常见原因：1) 该端口不是远程桌面端口；"
			         "2) 服务器要求网络级身份验证(NLA)，请在界面切换安全级别重试；"
			         "3) 用户名或密码不正确（可试试「主机名\\用户名」格式）。"
			         "（FreeRDP 未返回具体错误码 0x%08X）",
			         hostStr, (int)s->ServerPort, (unsigned)errCode);
		}
		else if (strstr(errStr, "AUTHENTICATION") != NULL ||
		         strstr(errStr, "LOGON") != NULL ||
		         strstr(errStr, "PASSWORD") != NULL ||
		         strstr(errStr, "CREDENTIAL") != NULL)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "认证失败：用户名或密码不正确。"
			         "请确认账号密码无误；若域名/工作组有要求，用户名可写成「域\\用户名」或「主机名\\用户名」"
			         "（底层：%s）", errStr);
		}
		else if (strstr(errStr, "TLS") != NULL ||
		         strstr(errStr, "CERTIFICATE") != NULL ||
		         strstr(errStr, "SECURITY") != NULL)
		{
			snprintf(g_last_error, sizeof(g_last_error),
			         "安全层握手失败：%s。"
			         "请尝试在界面切换「安全级别」（NLA / TLS / RDP）后重试。", errStr);
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
