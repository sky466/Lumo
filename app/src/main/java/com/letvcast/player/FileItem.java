package com.letvcast.player;

/**
 * 统一的文件/目录条目模型（SMB 和 WebDAV 共用）
 */
public class FileItem {
    public String name;      // 显示名
    public String path;      // 完整路径（用于继续浏览/播放）
    public boolean isDirectory;
    public long size;        // 字节
    public String modified;  // 修改时间

    public FileItem(String name, String path, boolean isDirectory, long size, String modified) {
        this.name = name;
        this.path = path;
        this.isDirectory = isDirectory;
        this.size = size;
        this.modified = modified;
    }
}
