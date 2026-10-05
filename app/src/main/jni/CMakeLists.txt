# CMakeLists.txt —— 编译 AccessRDP 的两个 JNI 原生库：
#   1) libfreerdp_client.so  : 主桥接（连接 / 键盘 / 鼠标 / 音频开关）
#   2) librdpsnd_android.so  : 音频输出后端（把解码后的 PCM 回传 Kotlin 播放）
#
# 依赖：已交叉编译并安装好的 FreeRDP（含头文件与 .so，且已打“android 后端”补丁）。
# 用法（配合 NDK toolchain）：
#   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
#         -DFREERDP_ROOT=/path/to/freerdp-install \
#         -S app/src/main/jni -B build-android
#   cmake --build build-android
#
# 产物：libfreerdp_client.so、librdpsnd_android.so（放到 app/src/main/jniLibs/<abi>/）

cmake_minimum_required(VERSION 3.22)
project(accessrdp_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT DEFINED FREERDP_ROOT)
  set(FREERDP_ROOT $ENV{FREERDP_ROOT})
endif()
if(FREERDP_ROOT STREQUAL "")
  message(FATAL_ERROR "请通过 -DFREERDP_ROOT=... 指定 FreeRDP 安装目录")
endif()

message(STATUS "FREERDP_ROOT = ${FREERDP_ROOT}")

# FreeRDP 安装后的头文件路径
include_directories(
  ${FREERDP_ROOT}/include
  ${FREERDP_ROOT}/include/freerdp
  ${FREERDP_ROOT}/include/winpr
)

# 定位依赖库。
# 注意：交叉编译时 NDK 工具链会把 CMAKE_FIND_ROOT_PATH_MODE_LIBRARY 设为 ONLY，
# 导致 find_library 只搜 NDK sysroot、完全忽略我们给的 PATHS，从而找不到
# build/freerdp-install/lib 下的 .so。因此这里直接构造绝对路径，可靠且不受
# find-root-path 影响；同时显式校验文件存在，缺失即报错而不是悄悄把空串当库名。
set(_fr_libdir ${FREERDP_ROOT}/lib)
set(FREERDP_LIB      ${_fr_libdir}/libfreerdp2.so)
set(FREERDP_CLI_LIB  ${_fr_libdir}/libfreerdp-client2.so)
set(WINPR_LIB        ${_fr_libdir}/libwinpr2.so)
foreach(_l FREERDP_LIB FREERDP_CLI_LIB WINPR_LIB)
  if(NOT EXISTS ${${_l}})
    message(FATAL_ERROR "找不到依赖库 ${_l}: ${${_l}} （请确认 FreeRDP 已正确安装到 ${FREERDP_ROOT}）")
  endif()
  message(STATUS "${_l} = ${${_l}}")
endforeach()

# ssl/crypto 来自按 ABI 单独编译的 OpenSSL（通过 -DOPENSSL_ROOT=... 传入），
# 不一定在 FREERDP_ROOT/lib 中。找到则显式链接（写入 DT_NEEDED，运行时由动态
# 链接器加载）；找不到也无害——libfreerdp2.so 自身已带 DT_NEEDED libssl/libcrypto。
set(SSL_LIB "")
set(CRYPTO_LIB "")
if(DEFINED OPENSSL_ROOT AND EXISTS "${OPENSSL_ROOT}/lib/libssl.so")
  set(SSL_LIB   ${OPENSSL_ROOT}/lib/libssl.so)
  set(CRYPTO_LIB ${OPENSSL_ROOT}/lib/libcrypto.so)
  message(STATUS "OpenSSL = ${SSL_LIB}")
endif()

set(COMMON_LIBS
  ${FREERDP_CLI_LIB}
  ${FREERDP_LIB}
  ${WINPR_LIB}
  log
)
if(SSL_LIB)
  list(APPEND COMMON_LIBS ${SSL_LIB})
endif()
if(CRYPTO_LIB)
  list(APPEND COMMON_LIBS ${CRYPTO_LIB})
endif()

# ---- 主桥接：libfreerdp_client.so ----
add_library(freerdp_client SHARED freerdp_jni.c)
target_include_directories(freerdp_client PRIVATE
  ${FREERDP_ROOT}/include
  ${FREERDP_ROOT}/include/freerdp
  ${FREERDP_ROOT}/include/winpr
)
target_link_libraries(freerdp_client ${COMMON_LIBS})
set_target_properties(freerdp_client PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

# ---- 音频后端：librdpsnd_android.so ----
# 注意：入口符号 freerdp_rdpsnd_client_subsystem_entry 必须对外导出，
# 故关闭默认隐藏可见性，确保 FreeRDP 的 dlopen + GetProcAddress 能找到它。
add_library(rdpsnd_android SHARED rdpsnd_android.c)
target_include_directories(rdpsnd_android PRIVATE
  ${FREERDP_ROOT}/include
  ${FREERDP_ROOT}/include/freerdp
  ${FREERDP_ROOT}/include/winpr
)
target_link_libraries(rdpsnd_android ${COMMON_LIBS})
set_target_properties(rdpsnd_android PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}
  C_VISIBILITY_PRESET default
)
