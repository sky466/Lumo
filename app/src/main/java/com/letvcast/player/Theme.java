package com.letvcast.player;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 深浅主题切换。
 *
 * <p>实现方式：颜色全部走 {@code @color} 资源，深色值放 {@code res/values-night/}，
 * 浅色值放 {@code res/values/}。切换主题时改的是 {@link Configuration#uiMode} 的
 * night 位，于是同一套布局/drawable 自动取到另一套颜色——不用给每个控件写两遍代码。
 *
 * <p>Activity 在 {@code attachBaseContext} 里调用 {@link #wrap(Context)}，
 * 保证它拿到的 Resources 就是目标主题的。
 */
public final class Theme {

    public static final int DARK = 0;
    public static final int LIGHT = 1;

    private Theme() {
    }

    /** 把 base 包成「当前主题」的 Context（Activity.attachBaseContext 用） */
    public static Context wrap(Context base) {
        try {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            int want = Config.getTheme(base) == LIGHT
                    ? Configuration.UI_MODE_NIGHT_NO
                    : Configuration.UI_MODE_NIGHT_YES;
            if ((cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == want) return base;
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | want;
            Context ctx = base.createConfigurationContext(cfg);
            // 个别老固件的 Resources 缓存不跟着刷新，再打一次补丁
            try {
                ctx.getResources().updateConfiguration(cfg, ctx.getResources().getDisplayMetrics());
            } catch (Throwable ignored) {
            }
            return ctx;
        } catch (Throwable t) {
            return base;
        }
    }

    public static boolean isLight(Context c) {
        return Config.getTheme(c) == LIGHT;
    }

    /** 切换并返回切换后的模式（调用方负责 recreate 界面） */
    public static int toggle(Context c) {
        int next = isLight(c) ? DARK : LIGHT;
        Config.setTheme(c, next);
        return next;
    }

}
