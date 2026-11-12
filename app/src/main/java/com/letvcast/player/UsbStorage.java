package com.letvcast.player;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * U 盘 / 移动存储检测（v1.40 重写判定逻辑）。
 *
 * <p>老版本靠「目录名字像 U 盘就叫 U 盘」兜底，结果电视固件开机就建好
 * {@code /mnt/udisk}、{@code /mnt/usb}、{@code /mnt/sda} 这类空挂载点，
 * 里面什么都没有却也被当成了 U 盘，主界面一直挂着一张假的 U 盘卡片。
 *
 * <p>现在的判定只有一条硬标准：<b>这个目录是不是真的挂着东西</b>。
 * <ol>
 *   <li>主力来源 {@code /proc/mounts}：只有文件系统是 U 盘格式（vfat/exfat/ntfs/udf/iso9660…）
 *       或设备节点是 {@code /dev/block/vold/...}（vold 挂载＝可移动介质）的条目才算数；</li>
 *   <li>挂载表里没有的路径直接丢弃，不管它叫不叫 udisk；</li>
 *   <li>只有在完全读不到挂载表的机器上，才用「StatFs 和父目录不一样」兜底
 *       （挂载点必然和父目录不是同一个文件系统，空壳目录则完全一样）。</li>
 * </ol>
 * 内部存储（/storage/emulated、/mnt/sdcard、/data 等）在挂载表阶段就排除了。
 */
public class UsbStorage {

    public static class Volume {
        public String label = "U 盘";
        public String path = "";
        /** 文件系统类型（vfat / exfat / ntfs…），诊断日志用 */
        public String fs = "";
        public long total = -1;
        public long free = -1;

        public String sizeText() {
            if (total <= 0) return "";
            return "剩余 " + human(free) + " / 共 " + human(total);
        }
    }

    /** 移动存储的文件系统白名单（各家固件叫法不同，全列上） */
    private static final String[] REMOVABLE_FS = {
            "vfat", "fat", "fat32", "msdos", "exfat", "texfat", "sdfat", "tntfs",
            "ntfs", "ntfs3", "fuseblk", "fuse.exfat", "fuse.ntfs", "udf", "iso9660", "cdfs"
    };

    /** 明确不是移动存储的文件系统：哪怕挂在 /mnt/xxx 下面也一律不算 */
    private static final String[] INTERNAL_FS = {
            "ext2", "ext3", "ext4", "f2fs", "yaffs2", "yaffs", "ubifs", "squashfs",
            "tmpfs", "ramfs", "devtmpfs", "rootfs", "overlay", "proc", "sysfs",
            "devpts", "cgroup", "selinuxfs", "debugfs", "tracefs", "configfs", "binder"
    };

    /** 这些路径下的东西永远不是「用户插进来的 U 盘」 */
    private static final String[] DENY_PREFIX = {
            "/proc", "/sys", "/dev", "/acct", "/data", "/cache", "/system",
            "/vendor", "/oem", "/product", "/mnt/secure", "/mnt/asec", "/mnt/obb",
            "/mnt/runtime", "/mnt/shell", "/mnt/user", "/mnt/expand", "/mnt/sdcard",
            "/storage/emulated", "/storage/self"
    };

    private static final class Mount {
        String path;
        String fs;
        String dev;
    }

    /** 列出所有可浏览的移动存储（U 盘 / SD 卡）；没插就返回空表 */
    public static List<Volume> list(Context ctx) {
        Map<String, Volume> found = new LinkedHashMap<String, Volume>();
        Map<String, Mount> mounts = readMounts();

        fromMounts(found, mounts);
        fromStorageManager(ctx, found);
        // 兜底：极少数固件把 U 盘挂成奇怪的文件系统名，挂在 /mnt/usb、/mnt/udisk 这类
        // 名字很像挂载点的目录上。这里再补一遍，但必须同时满足「名字像可移动介质」
        // 且「确实和父目录不是同一个文件系统」——固件预留的空壳目录两条都不满足。
        scanDirs(found, mounts);

        List<Volume> out = new ArrayList<Volume>();
        String primary = primaryStorage();
        for (Volume v : found.values()) {
            if (v.path == null || v.path.isEmpty()) continue;
            File f = new File(v.path);
            if (!f.isDirectory() || !f.canRead()) continue;
            String abs = absolute(f);
            if (primary != null && abs.equals(primary)) continue;
            if (isDeniedPath(abs)) continue;
            fillSpace(v);
            out.add(v);
        }
        // 有容量的（真 U 盘）排前面
        Collections.sort(out, new Comparator<Volume>() {
            @Override
            public int compare(Volume a, Volume b) {
                if ((a.total > 0) != (b.total > 0)) return a.total > 0 ? -1 : 1;
                return a.path.compareTo(b.path);
            }
        });
        return out;
    }

    // ==================== 挂载表 ====================

    private static Map<String, Mount> readMounts() {
        Map<String, Mount> out = new LinkedHashMap<String, Mount>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                Mount m = new Mount();
                m.dev = p[0];
                m.path = unescape(p[1]);
                m.fs = p[2].toLowerCase(Locale.ROOT);
                if (m.path == null || m.path.isEmpty()) continue;
                m.path = absolute(new File(m.path));
                out.put(m.path, m);
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /**
     * 只从挂载表里挑「真·移动存储」。
     * 判据：文件系统是 U 盘格式，或者设备节点是 vold 挂的可移动介质。
     */
    private static void fromMounts(Map<String, Volume> found, Map<String, Mount> mounts) {
        StringBuilder skipped = new StringBuilder();
        for (Mount m : mounts.values()) {
            if (!isRemovableMount(m)) {
                // 名字像 U 盘却没被采纳 —— 记进诊断日志，真机上遇到不认识的固件能直接看出原因
                String name = m.path.substring(m.path.lastIndexOf('/') + 1);
                if (looksLikeRemovable(name) && skipped.length() < 300) {
                    skipped.append(m.path).append("[fs=").append(m.fs)
                            .append(",dev=").append(m.dev).append("] ");
                }
                continue;
            }
            if (isDeniedPath(m.path)) continue;
            // /mnt/media_rw/XXXX 一般应用读不到，对应可读路径是 /storage/XXXX
            if (m.path.startsWith("/mnt/media_rw/")) {
                String name = m.path.substring("/mnt/media_rw/".length());
                if (name.indexOf('/') < 0) add(found, "/storage/" + name, null, m.fs);
            }
            add(found, m.path, null, m.fs);
        }
        if (skipped.length() > 0) {
            LogBus.mark("USB", "未被采纳的可疑挂载点：" + skipped);
        }
    }

    private static boolean isInternalFs(String fs) {
        String f = fs == null ? "" : fs;
        for (String internal : INTERNAL_FS) {
            if (internal.equals(f)) return true;
        }
        return false;
    }

    private static boolean isRemovableMount(Mount m) {
        String fs = m.fs == null ? "" : m.fs;
        for (String internal : INTERNAL_FS) {
            if (internal.equals(fs)) return false;
        }
        for (String ok : REMOVABLE_FS) {
            if (ok.equals(fs)) return true;
        }
        // fuse.exfat / fuse.ntfs / 厂商自定义的 fuse.xxx
        if (fs.startsWith("fuse.")) return true;
        // vold 只会挂可移动介质（U 盘 / SD 卡）
        if (m.dev != null && m.dev.startsWith("/dev/block/vold/")) return true;
        // 有些 Mstar 固件把 U 盘挂成 ext4 但设备节点是 sda/sdb
        String dev = m.dev == null ? "" : m.dev.toLowerCase(Locale.ROOT);
        return dev.contains("/sda") || dev.contains("/sdb") || dev.contains("/sdc");
    }

    // ==================== StorageManager（API 24+） ====================

    private static void fromStorageManager(Context ctx, Map<String, Volume> found) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return;
            List<StorageVolume> vols = sm.getStorageVolumes();
            for (StorageVolume sv : vols) {
                boolean removable;
                try {
                    removable = sv.isRemovable();
                } catch (Throwable t) {
                    removable = false;
                }
                if (!removable) continue;
                String state = null;
                try {
                    state = sv.getState();
                } catch (Throwable ignored) {
                }
                if (state != null && !"mounted".equalsIgnoreCase(state)
                        && !"MOUNTED".equalsIgnoreCase(state)) continue;
                String path = volumePath(sv);
                String desc = null;
                try {
                    desc = sv.getDescription(ctx);
                } catch (Throwable ignored) {
                }
                add(found, path, desc, "");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String volumePath(StorageVolume sv) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                File d = sv.getDirectory();
                if (d != null) return d.getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        // API 24~29 的隐藏 API：getPathFile() / getPath()
        try {
            Method m = StorageVolume.class.getMethod("getPathFile");
            Object o = m.invoke(sv);
            if (o instanceof File) return ((File) o).getAbsolutePath();
        } catch (Throwable ignored) {
        }
        try {
            Method m = StorageVolume.class.getMethod("getPath");
            Object o = m.invoke(sv);
            if (o instanceof String) return (String) o;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 兜底（仅在读不到挂载表时使用） ====================

    private static void scanDirs(Map<String, Volume> found, Map<String, Mount> mounts) {
        scanOne(new File("/storage"), found, mounts);
        scanOne(new File("/mnt"), found, mounts);
    }

    private static void scanOne(File base, Map<String, Volume> found, Map<String, Mount> mounts) {
        File[] kids = base.listFiles();
        if (kids == null) return;
        String baseAbs = absolute(base);
        for (File f : kids) {
            String name = f.getName();
            if (name == null || name.isEmpty()) continue;
            if (!f.isDirectory()) continue;
            String abs = absolute(f);
            if (isDeniedPath(abs)) continue;
            // 名字不像可移动介质，或者只是固件预留的空目录 —— 都不算 U 盘
            if (!looksLikeRemovable(name)) continue;
            if (!isDistinctFileSystem(abs, baseAbs)) continue;
            Mount m = mounts.get(abs);
            if (m != null && isInternalFs(m.fs)) continue;   // tmpfs 之类的假挂载点
            add(found, abs, null, m == null ? "" : m.fs);
        }
    }

    private static boolean looksLikeRemovable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches("[0-9a-f]{4}-[0-9a-f]{4}")) return true;   // FAT 卷 UUID
        return n.contains("usb") || n.contains("udisk") || n.contains("sda")
                || n.contains("sdb") || n.contains("sdc") || n.contains("extsd")
                || n.contains("sdcard1") || n.contains("removable") || n.contains("exfat")
                || n.contains("ntfs");
    }

    /**
     * 挂载点和父目录必然不是同一个文件系统；固件预留的空壳目录则和父目录完全相同。
     * 用 StatFs 的块大小 + 总块数比对（不含可用块数，避免边写边变的抖动）。
     */
    private static boolean isDistinctFileSystem(String path, String parentPath) {
        long[] a = statsOf(path);
        long[] b = statsOf(parentPath);
        if (a == null || b == null) return false;
        return a[0] != b[0] || a[1] != b[1];
    }

    private static long[] statsOf(String path) {
        try {
            StatFs sf = new StatFs(path);
            return new long[]{sf.getBlockSize(), sf.getBlockCount()};
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 工具 ====================

    private static boolean isDeniedPath(String abs) {
        if (abs == null || abs.isEmpty() || "/".equals(abs)) return true;
        for (String p : DENY_PREFIX) {
            if (abs.equals(p) || abs.startsWith(p + "/")) return true;
        }
        return false;
    }

    private static void add(Map<String, Volume> found, String path, String label, String fs) {
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        String abs = absolute(f);
        if (isDeniedPath(abs)) return;
        Volume v = found.get(abs);
        if (v == null) {
            v = new Volume();
            v.path = abs;
            found.put(abs, v);
        }
        if (fs != null && !fs.isEmpty()) v.fs = fs;
        if (label != null && !label.trim().isEmpty()) {
            v.label = label.trim();
        } else if (v.label == null || "U 盘".equals(v.label)) {
            v.label = guessLabel(f);
        }
    }

    /** 卷名尽量短：卡片本来就窄，长名字会被截断 */
    private static String guessLabel(File f) {
        String name = f.getName();
        if (name == null || name.isEmpty() || "/".equals(name)) return "U 盘";
        if (name.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
            return "U 盘 " + name.substring(name.length() - 4);
        }
        if (name.length() > 8) name = name.substring(0, 8);
        return "U 盘（" + name + "）";
    }

    private static void fillSpace(Volume v) {
        try {
            StatFs sf = new StatFs(v.path);
            long block = sf.getBlockSize();
            v.total = (long) sf.getBlockCount() * block;
            v.free = (long) sf.getAvailableBlocks() * block;
        } catch (Throwable ignored) {
        }
    }

    private static String primaryStorage() {
        try {
            File f = Environment.getExternalStorageDirectory();
            return f == null ? null : absolute(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String absolute(File f) {
        try {
            return f.getAbsolutePath().replaceAll("/+$", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** /proc/mounts 里的空格等字符是八进制转义（\040 表示空格） */
    private static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                    i += 3;
                    continue;
                } catch (Exception ignored) {
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String human(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
