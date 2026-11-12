package com.letvcast.player;

import java.io.IOException;

import jcifs.smb.SmbRandomAccessFile;

/**
 * SMB1（jcifs）句柄适配器，只用于十几年不升级的老 NAS。
 */
public class Smb1Handle implements SmbHandle {

    private final SmbRandomAccessFile raf;
    private final long size;

    public Smb1Handle(SmbRandomAccessFile raf, long size) {
        this.raf = raf;
        this.size = size;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len <= 0) return 0;
        try {
            int n = raf.read(buf, off, len);
            return n <= 0 ? -1 : n;
        } catch (IOException e) {
            throw e;
        }
    }

    @Override
    public void seek(long pos) throws IOException {
        raf.seek(pos);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (Throwable ignored) {
        }
    }
}
