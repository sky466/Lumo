package com.letvcast.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 极简字幕解析器：SRT / WebVTT / ASS(SSA)。
 *
 * 为什么自己解析而不是用 ijkplayer 的字幕轨道：
 * 0.8.8 的 java 层没有 addTimedTextSource（只有 native 有），拿不到轨道；
 * 自己解析 + 自己渲染反而能同时支持「系统解码」引擎，字幕位置/大小也好调。
 *
 * ASS 只取 Dialogue 行的纯文本（去掉 {\...} 样式标签、\N 换行符），不做特效排版。
 */
public final class Subtitle {

    public static class Cue {
        public final long start;
        public final long end;
        public final String text;

        Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private Subtitle() {
    }

    public static List<Cue> parse(String content) {
        List<Cue> cues = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) return cues;
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        if (text.contains("[Script Info]") || text.contains("Dialogue:")) {
            parseAss(text, cues);
        } else {
            parseSrt(text, cues);
        }
        Collections.sort(cues, new Comparator<Cue>() {
            @Override
            public int compare(Cue a, Cue b) {
                return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0);
            }
        });
        return cues;
    }

    /** SRT 与 VTT 共用一个解析：靠 "-->" 定位，时间分隔符兼容 "," 和 "." */
    private static void parseSrt(String text, List<Cue> out) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.contains("-->")) continue;
            int arrow = line.indexOf("-->");
            long start = parseTime(line.substring(0, arrow).trim());
            String rest = line.substring(arrow + 3).trim();
            // VTT 可能带 "00:00:04.000 align:middle"
            int sp = rest.indexOf(' ');
            String endStr = sp > 0 ? rest.substring(0, sp) : rest;
            long end = parseTime(endStr);
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(stripTags(lines[j]));
                j++;
            }
            i = j;
            if (end > start && sb.length() > 0) out.add(new Cue(start, end, sb.toString()));
        }
    }

    private static void parseAss(String text, List<Cue> out) {
        String[] lines = text.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("Dialogue:")) continue;
            String body = line.substring("Dialogue:".length());
            String[] parts = body.split(",", 10);
            if (parts.length < 10) continue;
            long start = parseTime(parts[1].trim());
            long end = parseTime(parts[2].trim());
            String content = parts[9];
            content = content.replaceAll("\\{[^}]*\\}", "");      // 样式标签
            content = content.replace("\\N", "\n").replace("\\n", "\n");
            content = content.replaceAll("<[^>]+>", "");
            if (end > start && !content.trim().isEmpty()) {
                out.add(new Cue(start, end, content.trim()));
            }
        }
    }

    private static String stripTags(String s) {
        String t = s.replaceAll("<[^>]+>", "").replaceAll("\\{[^}]*\\}", "");
        return t.trim();
    }

    /** 支持 00:01:02,500 / 0:01:02.50 / 01:02.5 */
    static long parseTime(String s) {
        if (s == null) return 0;
        String t = s.trim().replace(',', '.');
        String[] hms = t.split(":");
        try {
            if (hms.length == 3) {
                return (long) (Double.parseDouble(hms[0]) * 3600000
                        + Double.parseDouble(hms[1]) * 60000
                        + Double.parseDouble(hms[2]) * 1000);
            } else if (hms.length == 2) {
                return (long) (Double.parseDouble(hms[0]) * 60000
                        + Double.parseDouble(hms[1]) * 1000);
            }
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 找到当前时间对应的字幕；找不到返回 null */
    public static String textAt(List<Cue> cues, long positionMs) {
        if (cues == null || cues.isEmpty()) return null;
        // 线性扫描足够快（字幕几百条），并兼容倒着 seek
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            if (positionMs < c.start) break;
            if (positionMs <= c.end) return c.text;
        }
        return null;
    }
}
