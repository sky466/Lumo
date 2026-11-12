package com.letvcast.player;

/**
 * native 崩溃信号捕获（libcrashcatch.so）。
 *
 * 播放闪退目前可能是 native SIGSEGV（FFmpeg 软解/渲染层），这种情况 Java 的
 * UncaughtExceptionHandler 完全抓不到，日志里什么都留不下。装好 sigaction 后，
 * 崩溃时会把 signal / fault addr / pc / lr / sp 直接写进 session.log，
 * 配合播放前记录的 so 加载基址，就能算出崩在哪个库的哪个偏移。
 */
public final class CrashCatcher {

    private static boolean inited = false;

    private CrashCatcher() {
    }

    public static synchronized void init(String logPath) {
        if (inited) return;
        try {
            System.loadLibrary("crashcatch");
            nativeInit(logPath);
            inited = true;
        } catch (Throwable t) {
            LogBus.mark("CRASH", "native 崩溃捕获不可用: " + t);
        }
    }

    private static native void nativeInit(String logPath);
}
