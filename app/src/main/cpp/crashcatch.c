/*
 * crashcatch.c —— 捕获 native 崩溃信号并把现场写进日志文件。
 *
 * 为什么需要它：播放闪退最可能是 native 层 SIGSEGV（FFmpeg 软解／渲染），
 * 这种崩溃不走 Java 的 UncaughtExceptionHandler，crash.log 里什么都没有。
 * 这里装 sigaction，把 signal、fault address、PC/LR/SP 用 write() 直接写进
 * 应用私有目录的日志文件（open 时打开 fd，信号处理里只做 async-signal-safe 的
 * write，再 _exit，避免二次崩溃把现场冲掉）。
 *
 * Java 侧在启动播放前会先把 /proc/self/maps 里各 .so 的加载基址写进日志，
 * 于是从 pc 值就能算出落在哪个库的哪个偏移，足以定位是 libijkffmpeg 还是
 * libijksdl／libgui 的问题。
 */
#include <jni.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <ucontext.h>

static int log_fd = -1;
static char alt_stack[64 * 1024];

static void put_str(const char *s) {
    if (log_fd >= 0 && s) {
        size_t len = strlen(s);
        ssize_t r = write(log_fd, s, len);
        (void) r;
    }
}

static void put_hex(uintptr_t v) {
    char buf[20];
    int i = 0;
    buf[i++] = '0';
    buf[i++] = 'x';
    int started = 0;
    for (int shift = (int) (sizeof(uintptr_t) * 8) - 4; shift >= 0; shift -= 4) {
        int d = (int) ((v >> shift) & 0xF);
        if (d != 0) started = 1;
        if (started || shift == 0) {
            buf[i++] = (char) (d < 10 ? ('0' + d) : ('a' + d - 10));
        }
    }
    buf[i] = '\0';
    put_str(buf);
}

static void handler(int sig, siginfo_t *info, void *ctx) {
    put_str("\n[CRASH] signal=");
    put_hex((uintptr_t) sig);
    put_str(" (");
    switch (sig) {
        case SIGSEGV: put_str("SIGSEGV"); break;
        case SIGBUS:  put_str("SIGBUS");  break;
        case SIGILL:  put_str("SIGILL");  break;
        case SIGFPE:  put_str("SIGFPE");  break;
        case SIGABRT: put_str("SIGABRT"); break;
        default:      put_str("other");   break;
    }
    put_str(") fault_addr=");
    put_hex(info ? (uintptr_t) info->si_addr : (uintptr_t) 0);
#if defined(__arm__)
    if (ctx) {
        ucontext_t *uc = (ucontext_t *) ctx;
        put_str(" pc=");
        put_hex((uintptr_t) uc->uc_mcontext.arm_pc);
        put_str(" lr=");
        put_hex((uintptr_t) uc->uc_mcontext.arm_lr);
        put_str(" sp=");
        put_hex((uintptr_t) uc->uc_mcontext.arm_sp);
    }
#elif defined(__aarch64__)
    if (ctx) {
        ucontext_t *uc = (ucontext_t *) ctx;
        put_str(" pc=");
        put_hex((uintptr_t) uc->uc_mcontext.pc);
        put_str(" lr=");
        put_hex((uintptr_t) uc->uc_mcontext.regs[30]);
        put_str(" sp=");
        put_hex((uintptr_t) uc->uc_mcontext.sp);
    }
#endif
    put_str("\n");

    // 交回默认处理，让系统同时留下 tombstone（有 adb 时更好排查）
    signal(sig, SIG_DFL);
    // 直接退出：不再跑 Java/native 收尾逻辑，避免二次崩溃覆盖现场
    _exit(128 + sig);
}

static void install(int sig) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, NULL);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_letvcast_player_CrashCatcher_nativeInit(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (log_fd < 0 && path != NULL) {
        const char *p = (*env)->GetStringUTFChars(env, path, NULL);
        if (p != NULL) {
            log_fd = open(p, O_WRONLY | O_APPEND | O_CREAT, 0600);
            (*env)->ReleaseStringUTFChars(env, path, p);
        }
    }
    if (log_fd < 0) return;

    stack_t ss;
    ss.ss_sp = alt_stack;
    ss.ss_size = sizeof(alt_stack);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    install(SIGSEGV);
    install(SIGBUS);
    install(SIGILL);
    install(SIGFPE);
    install(SIGABRT);

    put_str("[CRASH-CATCH] signal handler installed\n");
}
