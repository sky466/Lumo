package com.letvcast.player;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 播放器内建日志总线（诊断用）。
 *
 * 为什么要自己写日志：乐视 X50Air 无法连 adb，一旦播放闪退（尤其是 native
 * SIGSEGV，Java 层 UncaughtExceptionHandler 抓不到）就看不出任何线索。
 * 这里把每一步都落到「应用私有目录」的 session.log：
 *   - 私有目录 /data/data/<pkg>/files 不需要 WRITE_EXTERNAL_STORAGE（API 18 也成立），
 *     不像 getExternalFilesDir(null) 那样在没有存储权限时静默失败（v1.14 之前
 *     就是因为这个才「找不到 crash.log」）。
 *   - 每写一条立即 flush + fsync，进程被信号杀死也不会丢最后几步。
 *   - 上次会话没有正常收尾时，启动时把内容留档成 abnormal.log，并在主界面弹出，
 *     用户用手机拍屏即可反馈根因。
 */
public final class LogBus {

    private static final String TAG = "LogBus";

    private static final String FILE_SESSION = "session.log";
    private static final String FILE_ABNORMAL = "abnormal.log";
    private static final String FILE_CRASH = "crash.log";

    private static final int MAX_MEM_LINES = 500;

    private static final ArrayList<String> memory = new ArrayList<String>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private static File sessionFile;
    private static File abnormalFile;
    private static File crashFile;
    private static FileOutputStream out;

    private static boolean previousAbnormal = false;
    private static String abnormalText = "";

    private LogBus() {
    }

    public static synchronized void init(Context ctx) {
        if (sessionFile != null) return;
        try {
            File dir = ctx.getFilesDir();
            sessionFile = new File(dir, FILE_SESSION);
            abnormalFile = new File(dir, FILE_ABNORMAL);
            crashFile = new File(dir, FILE_CRASH);

            // 判定上一次会话是否异常结束：最后一条不是 SESSION-END 就是没走完
            String last = lastNonEmptyLine(sessionFile);
            if (last != null && !last.contains("SESSION-END")) {
                previousAbnormal = true;
                String candidate = readFile(sessionFile);
                boolean candidateHasCrash = containsCrash(candidate);
                String old = abnormalFile.exists() ? readFile(abnormalFile) : "";
                boolean oldHasCrash = containsCrash(old);
                // 关键：真正的崩溃现场不要被后续「没有崩溃的一次异常退出」冲掉，
                // 否则用户多开一次 APP，SIGSEGV 的 pc/信号记录就丢了。
                if (candidateHasCrash || !oldHasCrash) {
                    abnormalText = candidate;
                    copyFile(sessionFile, abnormalFile);
                } else {
                    abnormalText = old;
                }
            }

            out = new FileOutputStream(sessionFile, false);
            mark("APP", "==== session start ====");
            mark("APP", "app=" + versionName(ctx)
                    + " android=" + Build.VERSION.RELEASE + "(API " + Build.VERSION.SDK_INT + ")"
                    + " model=" + Build.MODEL + " brand=" + Build.BRAND);
            mark("APP", "heapMax=" + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + "MB");
        } catch (Throwable t) {
            Log.e(TAG, "init fail", t);
        }
    }

    public static synchronized File getCrashFile() {
        return crashFile;
    }

    public static synchronized File getSessionFile() {
        return sessionFile;
    }

    /** 上次会话里是否有真正的崩溃记录（[CRASH] 信号 / [FATAL] 异常） */
    public static synchronized boolean previousSessionHadCrash() {
        return containsCrash(abnormalText);
    }

    public static synchronized void clearAbnormal() {
        previousAbnormal = false;
        abnormalText = "";
        if (abnormalFile != null) {
            //noinspection ResultOfMethodCallIgnored
            abnormalFile.delete();
        }
    }

    /** 记一条日志：内存 + 文件（立即落盘） */
    public static void mark(String tag, String msg) {
        String line;
        synchronized (LogBus.class) {
            line = TS.format(new Date()) + " [" + tag + "] " + msg;
            memory.add(line);
            while (memory.size() > MAX_MEM_LINES) memory.remove(0);
            Log.i(TAG, line);
            try {
                if (out == null) {
                    // 没有 init（比如被系统直接拉起的子进程）时不要静默丢日志
                    return;
                }
                out.write((line + "\n").getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void markEnd() {
        mark("APP", "SESSION-END");
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized String tail(int n) {
        int from = Math.max(0, memory.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < memory.size(); i++) {
            sb.append(memory.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** 打包一份可读报告（用于弹窗/拍屏反馈） */
    public static synchronized String report(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== 设备 ====\n");
        sb.append("型号: ").append(Build.MODEL).append(" / ").append(Build.BRAND).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("版本: ").append(versionName(ctx)).append('\n');
        sb.append("引擎: ").append(Config.getEngineName(ctx)).append('\n');
        sb.append("屏幕: ").append(screenInfo(ctx)).append('\n');

        if (previousAbnormal && !abnormalText.isEmpty()) {
            String crash = extractCrash(abnormalText);
            if (!crash.isEmpty()) {
                sb.append("\n==== native 崩溃现场（关键！） ====\n");
                sb.append(crash).append('\n');
            }
            sb.append("\n==== 上次异常退出的日志（尾部，已去掉 MAP 噪音） ====\n");
            sb.append(tailText(stripMaps(abnormalText), 30));
        }
        if (crashFile != null && crashFile.exists()) {
            String c = readFile(crashFile);
            if (c != null && !c.trim().isEmpty()) {
                sb.append("\n==== native 崩溃记录 ====\n");
                sb.append(tailText(c, 20));
            }
        }
        sb.append("\n==== 本次会话日志（尾部） ====\n");
        sb.append(tail(40));
        return sb.toString();
    }

    private static boolean containsCrash(String text) {
        return text != null && (text.contains("[CRASH]") || text.contains("[FATAL]"));
    }

    /** 把 [CRASH] 行单独拎出来（这是最关键的证据） */
    private static String extractCrash(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.contains("[CRASH]") || line.contains("[FATAL]")
                    || line.contains("Exception") || line.startsWith("\tat ")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 去掉 [MAP] 行，避免把关键日志挤掉 */
    private static String stripMaps(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.contains("[MAP]")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 屏幕分辨率（不走 activity，重启后也能拿到） */
    public static String screenInfo(Context ctx) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            String real = Config.lastScreenInfo;
            return dm.widthPixels + "x" + dm.heightPixels + " dpi=" + dm.densityDpi
                    + " | 播放界面记录=" + (real == null ? "-" : real);
        } catch (Throwable t) {
            return Config.lastScreenInfo;
        }
    }

    private static String tailText(String text, int n) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String lastNonEmptyLine(File f) {
        if (f == null || !f.exists()) return null;
        String last = null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) last = line;
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(br);
        }
        return last;
    }

    static String readFile(File f) {
        if (f == null || !f.exists()) return "";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            int readLen = (int) Math.min(len, 64 * 1024);
            byte[] buf = new byte[readLen];
            raf.seek(Math.max(0, len - readLen));
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(raf);
        }
    }

    private static void copyFile(File from, File to) {
        if (to == null) return;
        java.io.InputStream in = null;
        FileOutputStream o = null;
        try {
            in = new java.io.FileInputStream(from);
            o = new FileOutputStream(to, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            o.flush();
        } catch (Throwable ignored) {
        } finally {
            // v1.49：失败路径也要关，原来只在成功后 close，磁盘满/IO 错时会漏 fd
            closeQuietly(o);
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
