package com.letvcast.player;

import java.io.IOException;

/**
 * 本地流代理的统一接口（SMB / WebDAV 共用）
 */
public interface IStreamProxy {
    String start() throws IOException;
    void stop();
}
