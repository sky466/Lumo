package com.letvcast.player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 本地文件客户端（U 盘 / SD 卡 / 内部存储）。
 *
 * 和 SMB / WebDAV 走同一套 FileItem 模型，所以 BrowseActivity 只要把 mode 换成 2
 * 就能直接浏览 U 盘，播放器也能复用同一套播放列表 / 字幕逻辑。
 */
public class LocalClient {

    /** 列目录（隐藏文件跳过，避免满屏 .thumbnails / .trash） */
    public static List<FileItem> list(String dir) {
        List<FileItem> items = new ArrayList<FileItem>();
        if (dir == null) dir = "/";
        File d = new File(dir);
        File[] children = d.listFiles();
        if (children == null) {
            // U 盘没挂好 / 没有存储权限时，明确报错比显示「空目录」更有用
            throw new IllegalStateException("无法读取目录：" + dir + "（可能没有存储权限或已拔出）");
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File f : children) {
            String name = f.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.startsWith(".")) continue;
            boolean isDir;
            try {
                isDir = f.isDirectory();
            } catch (Throwable t) {
                isDir = false;
            }
            long size = isDir ? 0 : f.length();
            String modified = "";
            try {
                modified = fmt.format(new Date(f.lastModified()));
            } catch (Throwable ignored) {
            }
            items.add(new FileItem(name, f.getAbsolutePath(), isDir, size, modified));
        }
        return items;
    }

    /** 播放器直接用绝对路径播放本地文件，不需要代理 */
    public static String fileUrl(String path) {
        return path;
    }

    public static boolean exists(String path) {
        try {
            return path != null && new File(path).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读整个文件（字幕 / 图片用） */
    public static byte[] read(String path) throws Exception {
        File f = new File(path);
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), 4L * 1024 * 1024));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 目录大小概览（主界面 U 盘卡片用） */
    public static int countMedia(String dir) {
        List<FileItem> items;
        try {
            items = list(dir);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (FileItem f : items) {
            if (f.isDirectory) continue;
            String lower = f.name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                    || lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".rmvb")
                    || lower.endsWith(".rm") || lower.endsWith(".flv") || lower.endsWith(".wmv")
                    || lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".mpg")
                    || lower.endsWith(".mpeg") || lower.endsWith(".3gp")) {
                n++;
            }
        }
        return n;
    }
}
