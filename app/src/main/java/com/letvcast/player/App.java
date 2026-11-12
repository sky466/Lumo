package com.letvcast.player;

import android.app.Application;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 应用入口：
 * 1. 初始化内建日志（私有目录，无需存储权限，逐条落盘）。
 * 2. 安装 native 崩溃信号捕获（SIGSEGV/SIGBUS/... 也能留下现场）。
 * 3. 安装 Java 未捕获异常处理（写 crash.log，再交回系统）。
 * 4. 检测「上次播放中进程被杀」→ 自动切换到下一个播放引擎（自愈降级）。
 */
public class App extends Application {

    private static final String TAG = "LetvPlayer";

    /** 启动时是否因为上次播放异常而切换过引擎（主界面会提示） */
    public static volatile boolean engineAutoSwitched = false;
    public static volatile String engineSwitchMessage = "";

    @Override
    public void onCreate() {
        super.onCreate();
        LogBus.init(this);
        ThumbCache.init(this);
        CrashCatcher.init(LogBus.getSessionFile() == null
                ? null : LogBus.getSessionFile().getAbsolutePath());
        installCrashHandler();
        checkPreviousPlaybackCrash();
        // SMB 协议偏好（默认自动：先 SMB2/3，老设备退回 SMB1）
        try {
            SmbClient.protocolPreference = Config.getSmbProtocol(this);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 上次是不是在播放中直接死掉（闪退/被杀）？是的话把引擎降级到下一个，
     * 这样即使某个引擎在这台电视上必崩，用户重开一次也会自动换引擎试。
     */
    private void checkPreviousPlaybackCrash() {
        try {
            // 引擎列表升级后统一回到默认（最优）引擎，避免历史遗留的选择让谁都卡
            if (Config.getEngineSchema(this) < Config.ENGINE_SCHEMA) {
                LogBus.mark("APP", "engine schema upgrade, reset engine to "
                        + Config.ENGINE_NAMES[Config.defaultEngine(this)]);
                Config.setEngine(this, Config.defaultEngine(this));
                Config.setEngineSchema(this, Config.ENGINE_SCHEMA);
            }

            if (!Config.isInPlayback(this)) return;

            // 只有「上次真的崩了」才轮换引擎。
            // 仅仅被系统杀进程/强制停止不该影响引擎选择（否则会莫名退到软解，越用越卡）。
            boolean crashed = LogBus.previousSessionHadCrash();
            Config.setInPlayback(this, false);
            if (!crashed) {
                LogBus.mark("APP", "previous playback ended abnormally but no crash record, keep engine "
                        + Config.getEngine(this));
                return;
            }
            int cur = Config.getEngine(this);
            int next = (cur + 1) % Config.ENGINE_COUNT;
            Config.setEngine(this, next);
            engineAutoSwitched = true;
            engineSwitchMessage = "上次播放崩溃，已自动切换引擎：\n"
                    + Config.ENGINE_NAMES[cur] + " → " + Config.ENGINE_NAMES[next];
            LogBus.mark("APP", "previous playback crashed, engine " + cur + " -> " + next);
        } catch (Throwable t) {
            Log.e(TAG, "checkPreviousPlaybackCrash", t);
        }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                String stack = sw.toString();
                LogBus.mark("FATAL", "thread=" + thread.getName() + "\n" + stack);
                writeCrashFile(thread, stack);
                Log.e(TAG, "FATAL: " + stack);

                // 关键：只有主线程异常才需要结束进程（UI 已经不可用了）。
                // 后台线程（代理/下载/目录加载）出错时只让该线程死掉，
                // 进程继续活着，用户看到的是「播放/加载失败」提示而不是闪退回主界面。
                boolean isMainThread = Looper.getMainLooper() != null
                        && Looper.getMainLooper().getThread() == thread;
                if (!isMainThread) {
                    LogBus.mark("FATAL", "后台线程异常已隔离，进程继续运行");
                    return;
                }
                // 主线程崩溃：交给系统默认处理（弹"应用已停止"，下次启动会换引擎）
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(1);
                }
            }
        });
    }

    /** 私有目录写崩溃栈（API 18 无存储权限也能写） */
    private void writeCrashFile(Thread thread, String stack) {
        FileOutputStream fos = null;
        try {
            File f = LogBus.getCrashFile();
            if (f == null) return;
            fos = new FileOutputStream(f, true);
            String head = "\n===== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.ROOT).format(new java.util.Date()) + " =====\n";
            fos.write((head + "thread=" + thread.getName() + "\n" + stack).getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
