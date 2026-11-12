package com.letvcast.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 配置持久化：保存当前连接配置 + 历史地址列表（SharedPreferences 存储，覆盖安装不丢失）
 */
public class Config {

    private static final String PREFS = "letv_player_config";
    private static final String KEY_MODE = "mode";
    private static final String KEY_HOST = "host";
    private static final String KEY_NAME = "name";
    private static final String KEY_SHARE = "share";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_IN_PLAYBACK = "in_playback";
    private static final String KEY_ENGINE_SCHEMA = "engine_schema";
    private static final String KEY_INTRO_SKIP = "intro_skip_ms";
    private static final String KEY_OUTRO_SKIP = "outro_skip_ms";
    private static final String KEY_AUTO_CONNECT = "auto_connect";
    private static final String KEY_SORT_KEY = "sort_key";
    private static final String KEY_SORT_DESC = "sort_desc";
    // v1.47 比例改成「适应屏幕 / 16:9 / 4:3 / 原始像素」（都不裁切），换 key 让老取值一律回到「适应屏幕」
    private static final String KEY_ASPECT = "aspect_v3";
    private static final String KEY_SUB_SIZE = "sub_size";
    private static final String KEY_SUB_POS = "sub_pos";
    private static final String KEY_SMB_PROTO = "smb_proto";
    private static final String KEY_THEME = "theme";
    private static final String KEY_THUMBS = "thumbs";
    // v1.43 语义变了（0=自动/1=总是Texture/2=总是Surface），换 key 免得把老值读成反义
    private static final String KEY_RENDER_MODE = "render_mode_v2";
    /** 引擎列表变更版本：变更时把用户的引擎选择重置到默认（最优）引擎 */
    public static final int ENGINE_SCHEMA = 145;

    /**
     * 播放引擎（v1.42：删掉 VLC 硬解/软解，并把「VideoView」并进系统解码）。
     *
     * <p>为什么砍 VLC：老电视上 VLC 的 armv7 原生库既吃 CPU 又常崩，实际没人用，
     * 却让 APK 多背了 30 多 MB 的 .so。ijkplayer 软解已经能兜住 RMVB/WMV/FLV/MPEG2。
     *
     * <p>为什么砍 VideoView：它自己管 Surface，转不了、缩放不了、seek 也慢，
     * 和「系统解码」是同一条解码路径，改成 TextureView 渲染后没有存在的理由。
     * 真正需要老渲染路径的机器，用「诊断 → 渲染方式 → SurfaceView 兼容」。
     */
    /** 引擎顺序 = 自愈降级顺序：先试最流畅的，失败再退到兼容性最好的 */
    public static final int ENGINE_IJK_HW = 0;        // ijkplayer 硬解（mediacodec）
    public static final int ENGINE_IJK_SW = 1;        // ijkplayer 软解（全格式）
    public static final int ENGINE_MP = 2;            // 系统 MediaPlayer（可旋转/缩放）
    public static final int ENGINE_VIDEOVIEW = 3;     // 系统 MediaPlayer + VideoView（老电视最省，不能旋转）
    public static final int ENGINE_IJK_RV32 = 4;      // ijkplayer 软解 + RGB32 叠加层
    public static final int ENGINE_COUNT = 5;

    /** 引擎名（v1.48 精简）：只留「实际是什么」，不写优缺点，长按菜单里也只显示这一串 */
    public static final String[] ENGINE_NAMES = {
            "ijkplayer 硬解",
            "ijkplayer 软解",
            "系统解码",
            "系统解码 + VideoView",
            "ijkplayer 软解 RGB32"
    };

    /** 最近一次播放界面记录的屏幕信息（供诊断报告用） */
    public static volatile String lastScreenInfo = "-";

    /**
     * 没存过引擎时的默认值（v1.45）。
     *
     * 4K 面板 + 老芯片上，视频层每帧要处理的像素是 1080p 的 4 倍，
     * 实测「系统解码 + VideoView」（控件自己管 Surface，不走 View 合成）最稳，
     * 所以 4K 屏默认就用它；1080p 及以下仍然用 ijk 硬解（格式最全、最流畅）。
     */
    public static int defaultEngine(Context ctx) {
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            if (Math.max(dm.widthPixels, dm.heightPixels) >= 2560) {
                return ENGINE_VIDEOVIEW;
            }
        } catch (Throwable ignored) {
        }
        return ENGINE_IJK_HW;
    }

    public static int getEngine(Context ctx) {
        int def = defaultEngine(ctx);
        int e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ENGINE, def);
        if (e < 0 || e >= ENGINE_COUNT) e = def;
        return e;
    }

    public static void setEngine(Context ctx, int engine) {
        if (engine < 0 || engine >= ENGINE_COUNT) engine = ENGINE_IJK_HW;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_ENGINE, engine).commit();
    }

    public static int getEngineSchema(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ENGINE_SCHEMA, 0);
    }

    public static void setEngineSchema(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_ENGINE_SCHEMA, v).apply();
    }

    /* ==================== 片头/片尾：按文件夹生效 ====================
     * 同一个文件夹（同一部剧/同一批番）共用一套片头片尾；换文件夹互不影响。
     * key 用「文件夹相对路径」的 hash+长度，避免中文长路径直接当 prefs key。
     */
    private static String folderKey(String kind, String folder) {
        String f = folder == null ? "" : folder.trim();
        return kind + "_" + Integer.toHexString(f.hashCode()) + "_" + f.length();
    }

    public static long getIntroSkipMs(Context ctx, String folder) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(folderKey("trim_in", folder), 0L);
    }

    public static void setIntroSkipMs(Context ctx, String folder, long ms) {
        if (ms < 0) ms = 0;
        if (ms > 10 * 60 * 1000L) ms = 10 * 60 * 1000L;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(folderKey("trim_in", folder), ms).apply();
    }

    public static long getOutroSkipMs(Context ctx, String folder) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(folderKey("trim_out", folder), 0L);
    }

    public static void setOutroSkipMs(Context ctx, String folder, long ms) {
        if (ms < 0) ms = 0;
        if (ms > 10 * 60 * 1000L) ms = 10 * 60 * 1000L;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(folderKey("trim_out", folder), ms).apply();
    }

    /* ==================== 断点续播（按文件） ==================== */
    private static String resumeKey(String url) {
        String u = url == null ? "" : url;
        return "resume_" + Integer.toHexString(u.hashCode()) + "_" + u.length();
    }

    public static long getResumeMs(Context ctx, String url) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(resumeKey(url), 0L);
    }

    /** ms<=0 表示清除该文件的续播点 */
    public static void setResumeMs(Context ctx, String url, long ms) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (ms <= 0) e.remove(resumeKey(url));
        else e.putLong(resumeKey(url), ms);
        e.apply();
    }

    /* ==================== 继续观看（v1.45） ====================
     * 存最近播放的几条（文件名 / 地址 / 进度 / 总时长），主界面直接给一张卡片续播。
     * 用 JSON 数组存 prefs：条数很少（8 条封顶），不需要数据库。
     */
    private static final String KEY_CONTINUE = "continue_watch";
    private static final String KEY_CONTINUE_ENABLED = "continue_enabled";
    private static final int CONTINUE_MAX = 8;

    public static class ContinueItem {
        public String url = "";
        public String name = "";
        public String host = "";       // 服务器地址（展示用）
        public String share = "";
        public String user = "";
        public String pass = "";
        public int mode;               // 0=SMB 1=WebDAV 2=本地
        public long pos;               // 看到哪儿
        public long dur;               // 总长（0 表示未知）
        public long at;                // 更新时间戳

        public String progressText() {
            return fmt(pos) + (dur > 0 ? " / " + fmt(dur) : "");
        }

        public int percent() {
            if (dur <= 0) return 0;
            int p = (int) (pos * 100 / dur);
            return p < 0 ? 0 : (p > 100 ? 100 : p);
        }

        private static String fmt(long ms) {
            if (ms < 0) ms = 0;
            long t = ms / 1000;
            long h = t / 3600, m = (t % 3600) / 60, sec = t % 60;
            if (h > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec);
            return String.format(java.util.Locale.ROOT, "%02d:%02d", m, sec);
        }
    }

    /** 记录/更新一条「继续观看」；pos<=0 或者已播到 95% 以上自动从列表里移除 */
    /** 主界面「继续观看」卡片开关（设置里可关） */
    public static boolean isContinueEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CONTINUE_ENABLED, true);
    }

    public static void setContinueEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_CONTINUE_ENABLED, v).apply();
    }

    public static void recordContinue(Context ctx, ContinueItem it) {
        if (it == null || it.url == null || it.url.isEmpty()) return;
        try {
            List<ContinueItem> list = getContinue(ctx, CONTINUE_MAX);
            // 先删掉旧记录（同一个地址只留一条）
            for (int i = list.size() - 1; i >= 0; i--) {
                if (it.url.equals(list.get(i).url)) list.remove(i);
            }
            boolean finished = it.dur > 0 && it.pos >= it.dur * 95 / 100;
            if (!finished && it.pos > 3000) {
                it.at = System.currentTimeMillis();
                list.add(0, it);
            }
            while (list.size() > CONTINUE_MAX) list.remove(list.size() - 1);
            saveContinue(ctx, list);
        } catch (Throwable ignored) {
        }
    }

    public static void removeContinue(Context ctx, String url) {
        try {
            List<ContinueItem> list = getContinue(ctx, CONTINUE_MAX);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).url.equals(url)) list.remove(i);
            }
            saveContinue(ctx, list);
        } catch (Throwable ignored) {
        }
    }

    public static List<ContinueItem> getContinue(Context ctx, int limit) {
        List<ContinueItem> out = new ArrayList<ContinueItem>();
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CONTINUE, "");
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ContinueItem it = new ContinueItem();
                it.url = o.optString("url", "");
                it.name = o.optString("name", "");
                it.host = o.optString("host", "");
                it.share = o.optString("share", "");
                it.user = o.optString("user", "");
                it.pass = o.optString("pass", "");
                it.mode = o.optInt("mode", 0);
                it.pos = o.optLong("pos", 0);
                it.dur = o.optLong("dur", 0);
                it.at = o.optLong("at", 0);
                if (!it.url.isEmpty()) out.add(it);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveContinue(Context ctx, List<ContinueItem> list) {
        try {
            JSONArray arr = new JSONArray();
            for (ContinueItem it : list) {
                JSONObject o = new JSONObject();
                o.put("url", it.url);
                o.put("name", it.name);
                o.put("host", it.host);
                o.put("share", it.share);
                o.put("user", it.user);
                o.put("pass", it.pass);
                o.put("mode", it.mode);
                o.put("pos", it.pos);
                o.put("dur", it.dur);
                o.put("at", it.at);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CONTINUE, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static final String KEY_HOLD_SPEED = "hold_speed";

    /** 长按屏幕加速的倍速，可选 2 / 3 / 4 倍，默认 3 */
    public static float getHoldSpeed(Context ctx) {
        float v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_HOLD_SPEED, 3f);
        return (v == 2f || v == 3f || v == 4f) ? v : 3f;
    }

    public static void setHoldSpeed(Context ctx, float v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_HOLD_SPEED, v).apply();
    }

    /** 在 2× / 3× / 4× 之间轮换 */
    public static float nextHoldSpeed(Context ctx) {
        float v = getHoldSpeed(ctx);
        if (v == 2f) return 3f;
        if (v == 3f) return 4f;
        return 2f;
    }

    private static final String KEY_RESUME_ENABLED = "resume_enabled";

    /** 是否记忆播放进度（断点续播），默认开 */
    public static boolean isResumeEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RESUME_ENABLED, true);
    }

    public static void setResumeEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RESUME_ENABLED, v).apply();
    }

    private static final String KEY_LAST_AUTO_CONNECT = "last_auto_connect_at";

    /** 上次「启动自动进服务器」的时间戳，用于防止返回主界面后又被自动弹进去 */
    public static long getLastAutoConnectAt(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO_CONNECT, 0L);
    }

    public static void setLastAutoConnectAt(Context ctx, long when) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_AUTO_CONNECT, when).apply();
    }

    /** 启动时自动连接上次的服务器 */
    public static boolean isAutoConnect(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CONNECT, true);
    }

    public static void setAutoConnect(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_CONNECT, v).commit();
    }

    /** 字幕字号（sp）与位置（0 底部 1 中间 2 顶部） */
    public static float getSubtitleSize(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SUB_SIZE, 26f);
    }

    public static void setSubtitleSize(Context ctx, float sp) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SUB_SIZE, sp).apply();
    }

    public static int getSubtitlePos(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SUB_POS, 0);
    }

    public static void setSubtitlePos(Context ctx, int pos) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SUB_POS, pos).apply();
    }

    /* ==================== 目录排序（全局一套设置） ==================== */
    public static final int SORT_NAME = 0;
    public static final int SORT_DATE = 1;
    public static final int SORT_SIZE = 2;
    public static final String[] SORT_NAMES = {"名称", "日期", "大小"};

    public static int getSortKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SORT_KEY, SORT_NAME);
    }

    public static boolean isSortDesc(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SORT_DESC, false);
    }

    public static void setSort(Context ctx, int key, boolean desc) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SORT_KEY, key).putBoolean(KEY_SORT_DESC, desc).apply();
    }

    public static String sortLabel(Context ctx) {
        int k = getSortKey(ctx);
        if (k < 0 || k >= SORT_NAMES.length) k = SORT_NAME;
        return SORT_NAMES[k] + (isSortDesc(ctx) ? " ↓" : " ↑");
    }

    /* ==================== 画面比例 / 旋转（记住上次选择） ==================== */
    /**
     * 画面比例（v1.47）：四档都是「先按本机屏幕算画面框，再把视频等比完整装进去」，
     * 任何一档都不裁切、不变形、不超出屏幕。
     * <ul>
     *   <li>0 适应屏幕（默认）：画面框 = 整块屏幕</li>
     *   <li>1 16:9：画面框 = 屏幕内能放下的最大 16:9 矩形（4:3 的设备上会上下加黑）</li>
     *   <li>2 4:3：画面框 = 屏幕内能放下的最大 4:3 矩形（16:9 设备上会左右加黑）</li>
     *   <li>3 原始像素：1:1 像素，屏幕装不下才等比缩小</li>
     * </ul>
     */
    public static final int ASPECT_FIT = 0;
    public static final int ASPECT_16_9 = 1;
    public static final int ASPECT_4_3 = 2;
    public static final int ASPECT_PIXEL = 3;
    public static final String[] ASPECT_NAMES = {"适应屏幕", "16:9", "4:3", "原始像素"};
    public static int getAspect(Context ctx) {
        int a = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ASPECT, ASPECT_FIT);
        return (a < 0 || a >= ASPECT_NAMES.length) ? ASPECT_FIT : a;
    }

    public static void setAspect(Context ctx, int a) {
        if (a < 0 || a >= ASPECT_NAMES.length) a = ASPECT_FIT;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_ASPECT, a).apply();
    }

    /* ==================== 界面版本（用于一次性迁移旧偏好） ==================== */
    private static final String KEY_UI_SCHEMA = "ui_schema";
    /** 126：主界面改为「服务器卡片 + U 盘」布局，自动连接默认关闭 */
    public static final int UI_SCHEMA = 126;

    public static int getUiSchema(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_UI_SCHEMA, 0);
    }

    public static void setUiSchema(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_UI_SCHEMA, v).apply();
    }

    /** 替换某条服务器记录（编辑用），失败返回 false */
    public static boolean updateHistory(Context ctx, int index, int mode, String host, String share,
                                        String user, String pass) {
        return updateHistory(ctx, index, "", mode, host, share, user, pass);
    }

    public static boolean updateHistory(Context ctx, int index, String name, int mode, String host,
                                        String share, String user, String pass) {
        List<HistoryItem> list = getHistory(ctx);
        if (index < 0 || index >= list.size()) return false;
        HistoryItem item = new HistoryItem();
        item.mode = mode;
        item.name = name == null ? "" : name.trim();
        item.host = host == null ? "" : host;
        item.share = share == null ? "" : share;
        item.user = user == null ? "" : user;
        item.pass = pass == null ? "" : pass;
        list.remove(index);
        // 去重：同 mode+host+share 的旧记录删掉，再把编辑后的放最前
        for (int i = 0; i < list.size(); i++) {
            HistoryItem h = list.get(i);
            if (h.mode == item.mode && eq(h.host, item.host) && eq(h.share, item.share)) {
                list.remove(i);
                i--;
            }
        }
        list.add(0, item);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HISTORY, toJson(list)).apply();
        return true;
    }

    /** 删除一条历史/服务器记录 */
    public static void removeHistory(Context ctx, int index) {
        List<HistoryItem> list = getHistory(ctx);
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HISTORY, toJson(list)).apply();
    }

    public static String getEngineName(Context ctx) {
        return ENGINE_NAMES[getEngine(ctx)];
    }

    /** SMB 协议偏好文案（设置里一行点按切换） */
    public static final String[] SMB_PROTO_NAMES = {"自动", "SMB2/3", "SMB1"};

    public static int getSmbProtocol(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SMB_PROTO, 0);
        return (v < 0 || v > 2) ? 0 : v;
    }

    /** 文件列表缩略图 / 视频首帧海报（默认开，可关掉省内存） */
    /**
     * 旋转方式：0 = 原样旋转（像素大小完全不变，超出屏幕的部分居中裁掉）
     *           1 = 适应屏幕（转过来后等比缩到屏幕内）
     *           2 = 铺满屏幕（转过来后等比放大到填满可用区域）
     */
    public static boolean getThumbs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_THUMBS, true);
    }

    public static void setThumbs(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_THUMBS, v).apply();
    }

    /** 主题：0 = 深色（影院），1 = 浅色（白天） */
    /* ==================== 视频渲染方式（v1.41） ====================
     * 0 = TextureView：旋转/缩放/平移都是 View 变换，老电视上能不能吃住因固件而异；
     * 1 = SurfaceView：老版本一直在用的路径，性能最稳，但不支持旋转（转不了）。
     */
    public static final int RENDER_AUTO = 0;       // 平常 SurfaceView（硬件叠加，最流畅），旋转/缩放时自动切 TextureView
    public static final int RENDER_TEXTURE = 1;    // 始终 TextureView（所有机型统一，但 4K 面板上合成开销大）
    public static final int RENDER_SURFACE = 2;    // 始终 SurfaceView（最省 GPU，不能旋转/缩放）

    public static final String[] RENDER_NAMES = {"自动", "TextureView", "SurfaceView"};

    public static int getRenderMode(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RENDER_MODE, RENDER_AUTO);
        return (v < 0 || v > 2) ? RENDER_AUTO : v;
    }

    public static void setRenderMode(Context ctx, int v) {
        if (v < 0 || v > 2) v = RENDER_AUTO;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_RENDER_MODE, v).apply();
    }

    public static int getTheme(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, 0);
        return v == 1 ? 1 : 0;
    }

    public static void setTheme(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_THEME, v == 1 ? 1 : 0).apply();
    }

    public static void setSmbProtocol(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SMB_PROTO, v).apply();
        SmbClient.protocolPreference = v;
    }

    /**
     * 播放中标志：进入播放前置 true，正常退出置 false。
     * 若应用重启时发现还是 true，说明上一次播放进程是被杀死/闪退的，
     * 这时自动切到下一个播放引擎（自愈式降级）。
     */
    public static boolean isInPlayback(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_IN_PLAYBACK, false);
    }

    public static void setInPlayback(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_IN_PLAYBACK, v).commit();
    }

    public static class HistoryItem {
        public int mode;       // 0=SMB, 1=WebDAV
        /** 用户给这台服务器起的名字（可空，空了就显示地址） */
        public String name;
        public String host;
        public String share;
        public String user;
        public String pass;
    }

    public static void saveCurrent(Context ctx, int mode, String host, String share, String user, String pass) {
        saveCurrent(ctx, "", mode, host, share, user, pass);
    }

    public static void saveCurrent(Context ctx, String name, int mode, String host, String share,
                                   String user, String pass) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putInt(KEY_MODE, mode)
                .putString(KEY_HOST, host)
                .putString(KEY_NAME, name == null ? "" : name.trim())
                .putString(KEY_SHARE, share == null ? "" : share)
                .putString(KEY_USER, user == null ? "" : user)
                .putString(KEY_PASS, pass == null ? "" : pass)
                .apply();
    }

    public static int getMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, 1);
    }

    public static String getName(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "");
    }

    public static String getHost(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "");
    }

    public static String getShare(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SHARE, "");
    }

    public static String getUser(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, "");
    }

    public static String getPass(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASS, "");
    }

    /**
     * 连接成功后，把当前配置加入历史（去重，最新的放最前，最多保留 20 条）
     */
    public static void addHistory(Context ctx, int mode, String host, String share, String user, String pass) {
        addHistory(ctx, "", mode, host, share, user, pass);
    }

    public static void addHistory(Context ctx, String name, int mode, String host, String share,
                                  String user, String pass) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<HistoryItem> list = getHistory(ctx);

        // 去重：如果已存在相同 mode+host+share，先移除旧的
        for (int i = 0; i < list.size(); i++) {
            HistoryItem h = list.get(i);
            if (h.mode == mode && eq(h.host, host) && eq(h.share, share)) {
                list.remove(i);
                i--;
            }
        }

        HistoryItem item = new HistoryItem();
        item.mode = mode;
        item.name = name == null ? "" : name.trim();
        item.host = host;
        item.share = share == null ? "" : share;
        item.user = user == null ? "" : user;
        item.pass = pass == null ? "" : pass;
        list.add(0, item);

        // 最多保留 20 条
        while (list.size() > 20) list.remove(list.size() - 1);

        sp.edit().putString(KEY_HISTORY, toJson(list)).apply();
    }

    public static List<HistoryItem> getHistory(Context ctx) {
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, "");
        List<HistoryItem> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                // optJSONObject：某一条坏了就跳过它，而不是整个列表解析失败、后续再被回写覆盖
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                HistoryItem h = new HistoryItem();
                h.mode = o.optInt("mode", 1);
                h.name = o.optString("name", "");
                h.host = o.optString("host", "");
                h.share = o.optString("share", "");
                h.user = o.optString("user", "");
                h.pass = o.optString("pass", "");
                list.add(h);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static String toJson(List<HistoryItem> list) {
        JSONArray arr = new JSONArray();
        for (HistoryItem h : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("mode", h.mode);
                o.put("name", h.name == null ? "" : h.name);
                o.put("host", h.host);
                o.put("share", h.share);
                o.put("user", h.user);
                o.put("pass", h.pass);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    private static boolean eq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }
}
