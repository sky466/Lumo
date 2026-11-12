package com.letvcast.player;

import java.io.IOException;

/**
 * 统一的 SMB 文件句柄（SMB2 与 SMB1 都用它，上层不关心底层协议）。
 */
public interface SmbHandle {

    /** 读取数据，返回 -1 表示已到文件尾 */
    int read(byte[] buf, int off, int len) throws IOException;

    /** 定位读指针（支持进度条拖动） */
    void seek(long pos) throws IOException;

    /** 文件总长度 */
    long size();

    void close();
}
