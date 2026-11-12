package com.letvcast.player;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解码能力探测：这台设备到底有没有 H.264 / H.265(HEVC) 的硬件解码器。
 *
 * 现在片子大多是 H.265，能不能放得动，关键不是引擎多少，而是「电视芯片有没有
 * HEVC 硬解、固件有没有把 MediaCodec 暴露出来」。这里直接把 MediaCodecList
 * 拉出来看，结果写进设置页和诊断日志。
 */
public final class CodecInfo {

    private CodecInfo() {
    }

    public static final String MIME_AVC = "video/avc";
    public static final String MIME_HEVC = "video/hevc";

    public static class Decoder {
        public String name = "";
        public boolean hardware;
        public int maxWidth;
        public int maxHeight;
    }

    /** 列出某个 mime 的所有视频解码器 */
    public static List<Decoder> videoDecoders(String mime) {
        List<Decoder> out = new ArrayList<Decoder>();
        try {
            int count = MediaCodecList.getCodecCount();
            for (int i = 0; i < count; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info == null || info.isEncoder()) continue;
                String[] types = info.getSupportedTypes();
                if (types == null) continue;
                boolean match = false;
                for (String t : types) {
                    if (t != null && t.equalsIgnoreCase(mime)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                Decoder d = new Decoder();
                d.name = info.getName();
                d.hardware = looksHardware(d.name);
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                    if (caps != null) {
                        d.maxWidth = caps.getVideoCapabilities().getSupportedWidths().getUpper();
                        d.maxHeight = caps.getVideoCapabilities().getSupportedHeights().getUpper();
                    }
                } catch (Throwable ignored) {
                }
                out.add(d);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 名字启发式判断是不是硬件解码器：
     * OMX.google.* / c2.android.* / 带 software 的都是纯软件实现。
     */
    private static boolean looksHardware(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("software") || n.contains("ffmpeg") || n.contains("avcodec")) return false;
        if (n.startsWith("c2.android.") || n.startsWith("omx.google.")) return false;
        if (n.contains("google")) return false;
        return n.startsWith("omx.") || n.startsWith("c2.");
    }

    public static boolean hasHardwareDecoder(String mime) {
        for (Decoder d : videoDecoders(mime)) {
            if (d.hardware) return true;
        }
        return false;
    }

    /** 设置页里显示的一行结论 */
    public static String summary(String mime) {
        List<Decoder> list = videoDecoders(mime);
        if (list.isEmpty()) return "无（系统未提供解码器）";
        for (Decoder d : list) {
            if (d.hardware) {
                String size = (d.maxWidth > 0 && d.maxHeight > 0)
                        ? "  最高 " + d.maxWidth + "×" + d.maxHeight : "";
                return "有" + size + "（" + shortName(d.name) + "）";
            }
        }
        Decoder first = list.get(0);
        String size = (first.maxWidth > 0 && first.maxHeight > 0)
                ? "  最高 " + first.maxWidth + "×" + first.maxHeight : "";
        return "无硬解，只能软解" + size + "（" + shortName(first.name) + "）";
    }

    private static String shortName(String n) {
        if (n == null) return "-";
        return n.length() > 34 ? n.substring(0, 34) + "…" : n;
    }

    /** 给设置页/诊断用的多行文本 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("H.264 硬解：").append(summary(MIME_AVC)).append('\n');
        sb.append("H.265 硬解：").append(summary(MIME_HEVC));
        return sb.toString();
    }

    /**
     * H.265 硬件解码是否能撑起这个分辨率；信息不足时返回 true（宁可不吓唬用户）
     */
    public static boolean canHardwareDecodeHevc(int width, int height) {
        if (width <= 0 || height <= 0) return hasHardwareDecoder(MIME_HEVC);
        for (Decoder d : videoDecoders(MIME_HEVC)) {
            if (!d.hardware) continue;
            if (d.maxWidth <= 0 || d.maxHeight <= 0) return true;
            if (width <= d.maxWidth && height <= d.maxHeight) return true;
        }
        return false;
    }

    public static boolean supportsHevcHardware() {
        return Build.VERSION.SDK_INT >= 16 && hasHardwareDecoder(MIME_HEVC);
    }
}
