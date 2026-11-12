package com.letvcast.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.VideoView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 视频播放界面（遥控器可操作，交互参考 nPlayer）。
 *
 * 五个可切换引擎（顺序即自愈降级顺序）：
 *   0 = ijkplayer + 硬解（mediacodec=auto，1080p 靠电视芯片解码，最流畅）
 *   1 = ijkplayer + 软解（FFmpeg 全格式，CPU 解码，格式兼容最好但 1080p 吃力）
 *   2 = 系统 MediaPlayer + SurfaceView（厂商固件解码）
 *   4 = ijkplayer 软解 + RGB32 叠加层（老 gralloc 不支持 YV12 时用）
 *
 * 遥控器操作：
 *   确定/播放键：播放/暂停          左右键：后退/前进 10 秒
 *   上/下键：呼出控制条（自动 5 秒隐藏）   MENU：控制条 + 日志
 *   控制条内：方向键移动焦点，确定键执行；进度条聚焦时左右键微调位置
 *   控制条按钮：后退10秒 / 播放暂停 / 前进10秒 / 倍速 / 切换引擎
 */
public class PlayerActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Theme.wrap(base));
    }


    private static final String TAG = "PlayerActivity";

    /** 一帧都不出、也没报错时，多久后认定「卡死」并换引擎 */
    private static final long WATCHDOG_MS = 20000;
    private static final long SEEK_STEP_MS = 5000;
    private static final long HIDE_CONTROLLER_MS = 5000;

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    /** 缩放：单击循环用小档，长按列表给全（1×~4×） */
    private static final float[] ZOOM_ALL = {1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};
    private static final long TRIM_STEP_MS = 5000;      // 片头/片尾每次调整 5 秒
    private static final int MAX_AUTO_SWITCH_BEST = 1;  // 软件解码自动升级引擎只做一次
    /**
     * 画面比例（v1.47）：不再有「拉伸 / 裁切」这种破坏性档位。
     * 每一档只决定「目标画面框」有多大，视频永远在这个框里等比完整装入：
     *   0 适应屏幕 —— 框 = 本机整块屏幕（默认）
     *   1 16:9     —— 框 = 屏幕内能放下的最大 16:9 矩形
     *   2 4:3      —— 框 = 屏幕内能放下的最大 4:3 矩形
     *   3 原始像素 —— 1:1，屏幕装不下才等比缩小
     * 框每次都按当前设备（手机竖屏 / 手机横屏 / 电视 16:9）实时算，
     * 所以同一个档位在不同设备上得到的大小本来就不同。
     */
    private static final String[] ASPECT_NAMES = Config.ASPECT_NAMES;
    private static final String[] SUB_EXT = {".srt", ".ass", ".ssa", ".vtt", ".sup"};
    private static final String[] SUB_POS_NAMES = {"底部", "中间", "顶部"};

    /** 一条可选字幕（外挂文件 / 内封轨道 / 关闭） */
    private static final class SubItem {
        int type;            // 0 关 1 外挂 2 内封
        String label;        // 显示文字
        String file;         // 外挂文件名
        int trackIndex = -1; // 内封轨道下标
    }
    private int speedIndex = 2;
    /** 长按屏幕临时加速：按住期间倍速（可在设置里调 2×/3×/4×），松开恢复 */
    private boolean holdingSpeed = false;

    private SurfaceView surfaceView;
    private TextView titleView;
    private TextView logView;

    private View controller;
    private View touchLayer;
    private GestureDetector gestureDetector;
    private TextView hudView;
    private final Runnable hideHudRunnable = new Runnable() {
        @Override
        public void run() {
            if (hudView != null) hudView.setVisibility(View.GONE);
        }
    };

    /* ---- 快进/快退时间胶囊（v1.40：电视上按方向键快进也要看得见时间） ---- */
    private View seekHintView;
    private TextView seekHintTime;
    private TextView seekHintDelta;
    /** 连续按键时累计跳过的时间；停手一段时间后归零 */
    private long seekHintAccumMs = 0;
    private long seekHintLastAt = 0;
    /** 拖进度条之前的播放位置：算「跳了多少」用 */
    private long seekBarBasePos = -1;
    /* ---- 性能看门狗（v1.45）：画面跟不上就自动降级到最省的引擎 ---- */
    private static final long PERF_INTERVAL_MS = 2200;
    private long perfLastPos = -1;
    private long perfLastAt = 0;
    private int perfBadCount = 0;
    private boolean perfDowngraded = false;
    /** 最近一次实测「画面每秒推进多少毫秒」，信息面板显示用 */
    private float perfRatio = 1f;

    /* ---- 信息面板 ---- */
    private View infoPanel;
    private TextView infoText;

    /** 上一次 clamp 的缓冲尺寸（避免重复 setFixedSize 触发 surface 重建） */
    private int lastClampW = -1, lastClampH = -1, lastViewW = -1, lastViewH = -1;

    /** 连续快进的目标位置 + 时间戳（合并 seek 用） */
    private long seekTargetMs = -1;
    private long seekTargetAt = 0;
    private final Runnable hideSeekHintRunnable = new Runnable() {
        @Override
        public void run() {
            if (seekHintView != null) seekHintView.setVisibility(View.GONE);
        }
    };

    /* ---- 触摸手势状态（左右拖 = 调进度，左半屏上下 = 亮度，右半屏上下 = 音量） ---- */
    private static final int G_NONE = 0;
    private static final int G_SEEK = 1;
    private static final int G_VOLUME = 2;
    private static final int G_BRIGHT = 3;
    private int gestureMode = G_NONE;
    private float downX = 0, downY = 0;
    private int touchSlop = 20;
    private long seekBasePos = -1;
    private long seekTargetPos = -1;
    private float seekMsPerPx = 60f;
    private int volMax = 15;
    private int volStart = 0;
    private float brightStart = 0.5f;
    private float brightness = -1f;
    private AudioManager audioManager;
    private SeekBar seekBar;
    private TextView curTimeView;
    private TextView totalTimeView;
    private TextView tipsView;
    private TextView subtitleView;
    private Button btnPlay;
    private Button btnSpeed;
    private Button btnRotate;
    private Button btnAspect;
    private Button btnZoomIn;
    private Button btnZoomOut;
    private Button btnEngine;

    private boolean controllerVisible = false;
    private boolean userSeeking = false;
    private long pendingSeekMs = -1;
    private long durationMs = -1;

    private volatile IStreamProxy proxy;
    private IjkMediaPlayer ijkPlayer;
    private MediaPlayer mpPlayer;
    private SurfaceHolder holder;
    private TextureView textureView;
    private VideoView videoView;
    private Surface textureSurface;
    /** 旋转 90/270 时必须用 TextureView：老 Android 上 SurfaceView 旋转会黑屏 */
    private boolean useTexture = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean released = new AtomicBoolean(false);
    private boolean surfaceReady = false;
    private String pendingUrl = null;

    private String mediaUrl;
    private String displayName;
    private int mode;
    private String user;
    private String pass;

    private int engine;
    private int engineAttempts = 0;
    private int watchdogAttempt = -1;
    private boolean prepared = false;
    private boolean firstFrame = false;
    /** 换引擎/恢复播放时要从这个位置继续 */
    private long resumePositionMs = 0;
    /** 实际生效的解码器（mediacodec 硬解 / avcodec 软解），用于界面提示与排障 */
    private String actualDecoder = "";
    // 画面比例 / 旋转 / 字幕
    private int aspectMode = Config.ASPECT_FIT;   // 0适应屏幕 1 16:9 2 4:3 3原始像素
    private int rotationDeg = 0;         // 0/90/180/270
    /** 旋转方式：0=原样（不缩放） 1=适应屏幕 2=铺满 */
    // 旧版的「旋转档位（原样/适应/铺满）」在 v1.41 取消：只保留原样旋转
    /* 双指缩放 / 平移（画面放大） */
    private float zoom = 1f;
    private float panX, panY;
    private float lastFocusX, lastFocusY;
    private boolean scaling;
    private android.view.ScaleGestureDetector scaleDetector;
    /** TextureView 还没准备好时要补一次 surface 挂载 */
    private boolean pendingSurfaceAttach;
    private int videoW = 0, videoH = 0;
    private boolean subtitleOn = true;
    private java.util.List<Subtitle.Cue> cues;
    private String subtitleName = "";
    private boolean subtitleLoading = false;
    /** 统一字幕/音轨列表：0=关闭 1=外挂 2=内封 */
    private final java.util.List<SubItem> subItems = new java.util.ArrayList<SubItem>();
    private int curSubIndex = -1;
    private String embeddedText = null;
    private int subPos = 0;              // 0 底部 1 中间 2 顶部
    private String loadedSubFile = "";
    private float subtitleSize = 26f;        // sp
    private long subtitleOffsetMs = 0;       // 字幕提前/延后
    private boolean autoNext = true;         // 连播下一集
    private int audioTrackIndex = -1;
    /** 内封字幕序号（-1 不选）；必须在 prepare 前设进内核才会被解码 */
    private long subtitleStreamOption = -1L;
    /** 用户要求「用系统解码显示内封字幕」：切引擎后自动选字幕轨 */
    private boolean wantEmbeddedSubViaMp = false;
    private final java.util.Set<Integer> triedEngines = new java.util.HashSet<Integer>();
    private boolean engineErrorHandled = false;
    /** 片头跳过 / 片尾跳过（毫秒，来自 Config，可现场调整） */
    private String folderKey = "";
    private String[] playlistUrls;
    private String[] playlistNames;
    private int playlistIndex = -1;
    private long introSkipMs = 0;
    private long outroSkipMs = 0;
    private int autoSwitchCount = 0;
    private boolean firstShowController = true;
    private final Runnable outroWatcher = new Runnable() {
        @Override
        public void run() {
            if (released.get() || !prepared) return;
            long d = dur();
            long p = pos();
            if (outroSkipMs > 0 && d > 0 && p > 0 && p >= d - outroSkipMs) {
                LogBus.mark("P", "outro reached: pos=" + p + " dur=" + d + " skip=" + outroSkipMs);
                onPlaybackDone();
                return;
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (released.get() || prepared || firstFrame) return;
            if (watchdogAttempt != engineAttempts) return;
            LogBus.mark("P", "watchdog: no frame in " + WATCHDOG_MS + "ms, prepared=" + prepared);
            onEngineError("20 秒未出画面");
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!controllerVisible || released.get()) return;
            refreshPlaybackInfo();
            mainHandler.postDelayed(this, 500);
        }
    };

    private final Runnable hideControllerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) hideController();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        try {
            surfaceView = (SurfaceView) findViewById(R.id.surface_view);
            titleView = (TextView) findViewById(R.id.player_title);
            logView = (TextView) findViewById(R.id.log_view);
            controller = findViewById(R.id.controller);
            seekBar = (SeekBar) findViewById(R.id.seek_bar);
            curTimeView = (TextView) findViewById(R.id.cur_time);
            totalTimeView = (TextView) findViewById(R.id.total_time);
            tipsView = (TextView) findViewById(R.id.tips_view);
            seekHintView = findViewById(R.id.seek_hint);
            seekHintTime = (TextView) findViewById(R.id.seek_hint_time);
            seekHintDelta = (TextView) findViewById(R.id.seek_hint_delta);
            infoPanel = findViewById(R.id.info_panel);
            infoText = (TextView) findViewById(R.id.info_text);
            subtitleView = (TextView) findViewById(R.id.subtitle_view);
            try {
                subtitleSize = Config.getSubtitleSize(this);
                subtitleView.setTextSize(subtitleSize);
                applySubtitlePosition();
            } catch (Throwable ignored) {
            }
            textureView = (TextureView) findViewById(R.id.texture_view);
            videoView = (VideoView) findViewById(R.id.video_view);
            // 手机上看着看着黑屏很烦，播放界面保持常亮
            try {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Throwable ignored) {
            }
            setupTouch();
            btnPlay = (Button) findViewById(R.id.btn_play);
            btnSpeed = (Button) findViewById(R.id.btn_speed);
            btnEngine = (Button) findViewById(R.id.btn_engine);
            btnRotate = (Button) findViewById(R.id.btn_rotate);
            btnAspect = (Button) findViewById(R.id.btn_aspect);

            mediaUrl = getIntent().getStringExtra("url");
            displayName = getIntent().getStringExtra("name");
            mode = getIntent().getIntExtra("mode", 0);
            user = getIntent().getStringExtra("user");
            pass = getIntent().getStringExtra("pass");
            engine = getIntent().getIntExtra("engine", Config.getEngine(this));
            resumePositionMs = getIntent().getLongExtra("position", 0);
            folderKey = getIntent().getStringExtra("folder");
            if (folderKey == null) folderKey = "";
            playlistUrls = getIntent().getStringArrayExtra("plist");
            playlistNames = getIntent().getStringArrayExtra("pnames");
            playlistIndex = getIntent().getIntExtra("pindex", -1);
            aspectMode = Config.getAspect(this);
            subPos = Config.getSubtitlePos(this);
            // 旋转只对「当前这个视频」生效，不再跨视频记忆：
            // 以前会记住上次的角度，结果一打开竖屏视频就被转成横的，看着像被改了画面。
            rotationDeg = 0;
            introSkipMs = Config.getIntroSkipMs(this, folderKey);
            outroSkipMs = Config.getOutroSkipMs(this, folderKey);

            logScreenInfo();

            if (displayName != null && !displayName.isEmpty()) {
                titleView.setText(displayName);
            } else {
                titleView.setText("正在启动播放器…");
            }
            titleView.setVisibility(View.VISIBLE);

            LogBus.mark("P", "onCreate url=" + mediaUrl + " mode=" + mode + " engine=" + engineName());
            LogBus.mark("P", "trim folder=\"" + folderKey + "\" intro=" + introSkipMs
                    + "ms outro=" + outroSkipMs + "ms playlist="
                    + (playlistUrls == null ? 0 : playlistUrls.length) + "@" + playlistIndex);
            logLibraryMaps();

            Config.setInPlayback(this, true);

            setupSurface();
            setupTexture();
            setupController();

            // 视频层统一走 TextureView（SurfaceView 只在兼容模式下用）。
            // v1.43：渲染层改成「按需」——
            //   平常播放走 SurfaceView（硬件叠加层，4K 面板上也不吃 GPU 合成）；
            //   只有用户按了旋转、或把画面放大/平移时才切到 TextureView（只有它支持 View 变换）。
            // v1.41/1.42 是「一律 TextureView」，在 4K 电视上等于每帧都要 GPU 合成一张
            // 3840×2160 的整屏纹理，老芯片顶不住 → 看着就是「卡卡的」。
            useTexture = shouldUseTexture();
            applyLayerVisibility();

            if (videoView != null) {
                videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        videoW = mp.getVideoWidth();
                        videoH = mp.getVideoHeight();
                        LogBus.mark("P", "VideoView prepared " + videoW + "x" + videoH
                                + " dur=" + mp.getDuration());
                        onEnginePrepared();
                        mp.start();
                    }
                });
                videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        LogBus.mark("P", "VideoView error what=" + what + " extra=" + extra);
                        onEngineError("VideoView what=" + what + " extra=" + extra);
                        return true;
                    }
                });
                videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        LogBus.mark("P", "VideoView completion");
                        onPlaybackDone();
                    }
                });
            }

            startProxyThread();
        } catch (Throwable t) {
            LogBus.mark("P", "onCreate fatal: " + t);
            showError("播放器初始化失败：" + t);
        }
    }

    // ==================== 触摸手势（手机 / 平板） ====================

    /**
     * 触摸屏适配：
     *   单点画面 → 呼出/收起控制条
     *   双击     → 播放 / 暂停
     *   长按     → 临时加速（倍速在设置里调）
     *   左右滑动 → 拖动调整播放进度
     *   左半屏上下滑 → 亮度
     *   右半屏上下滑 → 音量
     * 遥控器那一套按键逻辑完全不受影响（touch_layer 只吃触摸事件）。
     */
    private void setupTouch() {
        touchLayer = findViewById(R.id.touch_layer);
        hudView = (TextView) findViewById(R.id.hud_text);
        if (touchLayer == null) return;
        try {
            touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                volMax = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            }
        } catch (Throwable ignored) {
        }

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                // 已经进入拖动（进度/音量/亮度）就不要加速了，避免慢速滑动误触
                if (gestureMode != G_NONE) return;
                startHoldSpeed();
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                LogBus.mark("P", "touch tap -> toggle controller");
                toggleController();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                LogBus.mark("P", "touch double tap -> toggle pause");
                togglePause();
                return true;
            }
        });
        scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                        scaling = true;
                        gestureMode = G_NONE;
                        stopHoldSpeed();
                        lastFocusX = d.getFocusX();
                        lastFocusY = d.getFocusY();
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        zoom = Math.max(1f, Math.min(4f, zoom * d.getScaleFactor()));
                        panX += d.getFocusX() - lastFocusX;
                        panY += d.getFocusY() - lastFocusY;
                        lastFocusX = d.getFocusX();
                        lastFocusY = d.getFocusY();
                        applyVideoLayout();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(android.view.ScaleGestureDetector d) {
                        scaling = false;
                        if (zoom <= 1.05f) {
                            resetZoom();
                            applyVideoLayout();
                            showHud("画面恢复原大小");
                        } else {
                            applyVideoLayout();
                            updateZoomLabel();
                            showHud("放大 " + String.format(java.util.Locale.US, "%.1f", zoom) + "×");
                        }
                    }
                });

        touchLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                handleTouch(event);
                return true;
            }
        });
        LogBus.mark("P", "touch layer ready, slop=" + touchSlop + " volMax=" + volMax);
    }

    /** 统一触摸分发：手势识别 + 拖动（进度 / 音量 / 亮度） */
    private void handleTouch(MotionEvent event) {
        // 双指：缩放 + 平移，优先级高于单指的进度/音量/亮度
        if (scaleDetector != null && (scaling || event.getPointerCount() >= 2)) {
            gestureMode = G_NONE;
            scaleDetector.onTouchEvent(event);
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureMode = G_NONE;
                seekBasePos = -1;
                seekTargetPos = -1;
                if (gestureDetector != null) gestureDetector.onTouchEvent(event);
                break;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (holdingSpeed) {
                    // 长按误触发后手指开始移动 -> 取消加速，让滑动正常生效
                    if (Math.abs(dx) > touchSlop * 2 || Math.abs(dy) > touchSlop * 2) {
                        LogBus.mark("P", "hold speed canceled by drag");
                        stopHoldSpeed();
                    } else {
                        return;
                    }
                }

                if (gestureMode == G_NONE) {
                    if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) {
                        if (gestureDetector != null) gestureDetector.onTouchEvent(event);
                        return;
                    }
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        beginSeek();
                    } else if (downX < getResources().getDisplayMetrics().widthPixels / 2f) {
                        beginBright();
                    } else {
                        beginVolume();
                    }
                }

                if (gestureMode == G_SEEK) updateSeek(dx);
                else if (gestureMode == G_VOLUME) updateVolume(dy);
                else if (gestureMode == G_BRIGHT) updateBright(dy);
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDrag = gestureMode != G_NONE;
                if (gestureMode == G_SEEK && seekTargetPos >= 0) {
                    LogBus.mark("P", "gesture seek -> " + seekTargetPos + "ms");
                    doSeek(seekTargetPos);
                    if (controllerVisible) refreshPlaybackInfo();
                }
                gestureMode = G_NONE;
                seekBasePos = -1;
                seekTargetPos = -1;
                stopHoldSpeed();
                // 拖动结束时给手势识别器一个 CANCEL，避免被当成「点了一下」
                if (gestureDetector != null) {
                    if (wasDrag) {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        gestureDetector.onTouchEvent(cancel);
                        cancel.recycle();
                    } else {
                        gestureDetector.onTouchEvent(event);
                    }
                }
                if (hudView != null && hudView.getVisibility() == View.VISIBLE) {
                    mainHandler.removeCallbacks(hideHudRunnable);
                    mainHandler.postDelayed(hideHudRunnable, 900);
                }
                break;
            }
            default:
                break;
        }
    }

    private void beginSeek() {
        gestureMode = G_SEEK;
        seekBasePos = pos();
        long d = dur();
        // 全屏横向拖一遍 ≈ 5 分钟，短片按片长 1/10 收敛
        float range = d > 0 ? Math.max(60000f, Math.min(d / 10f, 300000f)) : 120000f;
        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        seekMsPerPx = range / w;
        LogBus.mark("P", "gesture seek start at " + seekBasePos + "ms, range=" + (long) range + "ms");
    }

    private void updateSeek(float dx) {
        long d = dur();
        long target = seekBasePos + (long) (dx * seekMsPerPx);
        long max = d > 1000 ? d - 1000 : (d > 0 ? d : Long.MAX_VALUE);
        if (target < 0) target = 0;
        if (target > max) target = max;
        if (Math.abs(target - seekBasePos) < 1000) {
            // 太小的位移不显示，避免抖动
            return;
        }
        seekTargetPos = target;
        showHud(formatTime(target) + " / " + (d > 0 ? formatTime(d) : "--:--"));
    }

    private void beginVolume() {
        gestureMode = G_VOLUME;
        try {
            volStart = audioManager == null ? 0
                    : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            volStart = 0;
        }
        LogBus.mark("P", "gesture volume start " + volStart + "/" + volMax);
    }

    private void updateVolume(float dy) {
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        int delta = Math.round(-(dy / h) * volMax);
        int target = volStart + delta;
        if (target < 0) target = 0;
        if (target > volMax) target = volMax;
        try {
            if (audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
        } catch (Throwable ignored) {
        }
        showHud("音量 " + Math.round(target * 100f / volMax) + "%");
    }

    private void beginBright() {
        gestureMode = G_BRIGHT;
        brightStart = currentBrightness();
        LogBus.mark("P", "gesture brightness start " + brightStart);
    }

    private void updateBright(float dy) {
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        float target = brightStart - dy / h;
        setBrightness(target);
        showHud("亮度 " + Math.round(brightness * 100f) + "%");
    }

    /** 当前亮度：优先用本窗口的覆盖值，其次读系统值，读不到就按 50% */
    private float currentBrightness() {
        if (brightness >= 0) return brightness;
        float b = -1f;
        try {
            b = getWindow().getAttributes().screenBrightness;
        } catch (Throwable ignored) {
        }
        if (b < 0) {
            try {
                b = android.provider.Settings.System.getInt(getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Throwable ignored) {
                b = 0.5f;
            }
        }
        brightness = Math.max(0.05f, Math.min(1f, b));
        return brightness;
    }

    private void setBrightness(float v) {
        brightness = Math.max(0.05f, Math.min(1f, v));
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = brightness;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /** 顶部轻量反馈：不是弹窗，1 秒后自动消失 */
    private void showHud(final String text) {
        if (hudView == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    hudView.setText(text);
                    hudView.setVisibility(View.VISIBLE);
                    mainHandler.removeCallbacks(hideHudRunnable);
                    mainHandler.postDelayed(hideHudRunnable, 1100);
                } catch (Throwable ignored) {
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    // ==================== 控制条 ====================

    private void setupController() {
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePause();
                scheduleHide();
            }
        });
        findViewById(R.id.btn_rew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(-SEEK_STEP_MS);
                scheduleHide();
            }
        });
        findViewById(R.id.btn_fwd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(SEEK_STEP_MS);
                scheduleHide();
            }
        });
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSpeed();
                scheduleHide();
            }
        });
        btnEngine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (engine + 1) % Config.ENGINE_COUNT;
                resumePositionMs = pos();
                LogBus.mark("P", "manual engine switch " + engine + " -> " + next
                        + " at " + resumePositionMs + "ms");
                switchEngine(next);
                scheduleHide();
            }
        });

        findViewById(R.id.btn_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playIndex(playlistIndex - 1);
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playIndex(playlistIndex + 1);
            }
        });

        findViewById(R.id.btn_aspect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aspectMode = (aspectMode + 1) % ASPECT_NAMES.length;
                Config.setAspect(PlayerActivity.this, aspectMode);
                applyVideoLayout();
                showHud("比例 " + ASPECT_NAMES[aspectMode]);
                refreshPlaybackInfo();
                scheduleHide();
            }
        });
        // 长按旋转：直接选 0/90/180/270，不用一下一下转（遥控器按住 OK 也算长按）
        Ui.bindLong(findViewById(R.id.btn_rotate), new Runnable() {
            @Override
            public void run() {
                // v1.41：旋转只有一种语义 —— 原画面转过去，像素大小一点不变。
                // 「适应屏幕 / 铺满屏幕」两档删掉了：那两档必然要缩放，和需求冲突。
                String[] names = {"不旋转", "90°", "180°", "270°"};
                int cur = rotationDeg / 90;
                showChoice("画面旋转", "当前 " + rotationDeg + "°", names, cur,
                        new Choice() {
                            @Override
                            public void onPick(int index) {
                                rotationDeg = (index * 90) % 360;
                                if (rotationDeg != 0 && !canRotate()) {
                                    showHud(rotateBlockedReason());
                                    rotationDeg = 0;
                                }
                                applyVideoLayout();
                                refreshPlaybackInfo();
                                if (rotationDeg != 0 || useTexture) showHud("旋转 " + rotationDeg + "°");
                            }
                        });
            }
        });

        // 缩放：v1.42 拆成「放大 / 缩小」两个按钮 —— 以前只有一个「缩放」循环键，
        // 只能越点越大再绕回 1×，看着像只会缩小。长按任意一个列出全部档位。
        btnZoomIn = (Button) findViewById(R.id.btn_zoom_in);
        btnZoomOut = (Button) findViewById(R.id.btn_zoom_out);
        if (btnZoomIn != null) {
            btnZoomIn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stepZoom(+1);
                    scheduleHide();
                }
            });
            Ui.bindLong(btnZoomIn, zoomPicker());
        }
        if (btnZoomOut != null) {
            btnZoomOut.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stepZoom(-1);
                    scheduleHide();
                }
            });
            Ui.bindLong(btnZoomOut, zoomPicker());
        }

        findViewById(R.id.btn_rotate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isVideoViewEngine() || Config.getRenderMode(PlayerActivity.this) == Config.RENDER_SURFACE) {
                    showHud(rotateBlockedReason());
                    scheduleHide();
                    return;
                }
                rotationDeg = (rotationDeg + 90) % 360;
                // 纯 View 变换：需要时才把渲染层切成 TextureView，不重建播放器、不换引擎
                applyVideoLayout();
                refreshPlaybackInfo();
                showHud("旋转 " + rotationDeg + "°");
                scheduleHide();
            }
        });
        findViewById(R.id.btn_av).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAvDialog();
                scheduleHide();
            }
        });
        findViewById(R.id.btn_loop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoNext = !autoNext;
                showToast(autoNext ? "连播下一集：开" : "连播下一集：关");
                refreshPlaybackInfo();
                scheduleHide();
            }
        });
        findViewById(R.id.btn_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleInfoPanel();
                scheduleHide();
            }
        });
        findViewById(R.id.btn_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (logView != null && logView.getVisibility() == View.VISIBLE) {
                    logView.setVisibility(View.GONE);
                } else {
                    showLog();
                }
                scheduleHide();
            }
        });

        findViewById(R.id.btn_trim).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTrimDialog();
            }
        });

        // 长按「速度 / 比例 / 引擎」弹出全部选项；长按快进快退连续跳
        Ui.bindLong(btnSpeed, new Runnable() {
            @Override
            public void run() {
                showSpeedPicker();
            }
        });
        Ui.bindLong(findViewById(R.id.btn_aspect), new Runnable() {
            @Override
            public void run() {
                showChoice("画面比例", "当前 " + ASPECT_NAMES[aspectMode], ASPECT_NAMES, aspectMode,
                        new Choice() {
                            @Override
                            public void onPick(int index) {
                                aspectMode = index;
                                Config.setAspect(PlayerActivity.this, aspectMode);
                                applyVideoLayout();
                                refreshPlaybackInfo();
                                showHud("比例 " + ASPECT_NAMES[aspectMode]);
                            }
                        });
            }
        });
        Ui.bindLong(btnEngine, new Runnable() {
            @Override
            public void run() {
                showChoice("播放引擎", "当前 " + engineName(), Config.ENGINE_NAMES, engine,
                        new Choice() {
                            @Override
                            public void onPick(int index) {
                                if (index == engine) return;
                                resumePositionMs = pos();
                                switchEngine(index);
                            }
                        });
            }
        });
        setRepeatingSeek(findViewById(R.id.btn_rew), -SEEK_STEP_MS);
        setRepeatingSeek(findViewById(R.id.btn_fwd), SEEK_STEP_MS);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long d = dur();
                if (d <= 0) return;
                if (seekBarBasePos < 0) seekBarBasePos = pos();
                userSeeking = true;
                pendingSeekMs = d * progress / 1000L;
                curTimeView.setText(formatTime(pendingSeekMs));
                mainHandler.removeCallbacks(applySeekRunnable);
                mainHandler.postDelayed(applySeekRunnable, 350);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                seekBarBasePos = pos();
                mainHandler.removeCallbacks(clearUserSeekingRunnable);
                mainHandler.removeCallbacks(hideControllerRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                applyPendingSeekNow();
                scheduleHide();
            }
        });
    }

    private final Runnable applySeekRunnable = new Runnable() {
        @Override
        public void run() {
            applyPendingSeekNow();
        }
    };

    /** 松手后 800ms 再解除「用户正在拖」，太快会让 tick 把滑块拽回去 */
    private final Runnable clearUserSeekingRunnable = new Runnable() {
        @Override
        public void run() {
            userSeeking = false;
        }
    };

    private void applyPendingSeekNow() {
        if (pendingSeekMs < 0) {
            userSeeking = false;
            return;
        }
        long target = pendingSeekMs;
        pendingSeekMs = -1;
        LogBus.mark("P", "seek -> " + target + "ms");
        long d = dur();
        long base = seekBarBasePos >= 0 ? seekBarBasePos : pos();
        seekBarBasePos = -1;
        doSeek(target);
        // 遥控器在进度条上左右移动时，同样把时间打出来
        showSeekHint(target, target - base, d);
        mainHandler.removeCallbacks(clearUserSeekingRunnable);
        mainHandler.postDelayed(clearUserSeekingRunnable, 800);
    }

    private void showController() {
        if (controller == null) return;
        controllerVisible = true;
        controller.setVisibility(View.VISIBLE);
        // 每次都重新拿焦点，避免焦点留在不可聚焦/已隐藏的控件上导致方向键失效
        if (btnPlay != null) {
            btnPlay.setFocusable(true);
            btnPlay.setFocusableInTouchMode(true);
            // 触摸设备别抢焦点（否则手机上会一直挂个蓝色焦点框），遥控器照旧
            if (controller != null && !controller.isInTouchMode()) btnPlay.requestFocus();
        }
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.post(tickRunnable);
        scheduleHide();
    }

    private void hideController() {
        controllerVisible = false;
        if (controller != null) controller.setVisibility(View.GONE);
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.removeCallbacks(hideControllerRunnable);
    }

    private void toggleController() {
        if (controllerVisible) {
            hideController();
        } else {
            showController();
        }
    }

    private void scheduleHide() {
        mainHandler.removeCallbacks(hideControllerRunnable);
        mainHandler.postDelayed(hideControllerRunnable, HIDE_CONTROLLER_MS);
    }

    private void refreshPlaybackInfo() {
        try {
            long d = dur();
            long p = pos();
            durationMs = d;
            if (d > 0) {
                totalTimeView.setText(formatTime(d));
                if (!userSeeking) {
                    seekBar.setProgress((int) (p * 1000L / d));
                    curTimeView.setText(formatTime(p));
                }
            }
            btnPlay.setText(isPlaying() ? "暂停" : "播放");
            btnSpeed.setText("速度 " + speedLabel());
            btnEngine.setText("引擎 " + (engine + 1) + "/" + Config.ENGINE_COUNT);
            if (btnRotate != null) btnRotate.setText("旋转 " + rotationDeg + "°");
            if (btnAspect != null) btnAspect.setText("比例 " + ASPECT_NAMES[aspectMode]);
            // 状态行只留「有用的」，去掉常驻噪声（v1.46 精简，原来一行塞了七八段）
            StringBuilder tip = new StringBuilder();
            tip.append(Config.ENGINE_NAMES[engine]);
            if (!actualDecoder.isEmpty() && (engine == Config.ENGINE_IJK_HW
                    || engine == Config.ENGINE_IJK_SW || engine == Config.ENGINE_IJK_RV32)) {
                tip.append(" · ").append(actualDecoder);
            }
            tip.append(" · ").append(isPlaying() ? "播放中" : "已暂停");
            if (playlistIndex >= 0 && playlistUrls != null && playlistUrls.length > 1) {
                tip.append(" · 第 ").append(playlistIndex + 1).append("/").append(playlistUrls.length).append(" 集");
                if (!autoNext) tip.append("·不连播");
            }
            if (aspectMode != Config.ASPECT_FIT) tip.append(" · ").append(ASPECT_NAMES[aspectMode]);
            if (rotationDeg != 0) tip.append(" · 旋转").append(rotationDeg).append("°");
            if (zoom > 1.001f) tip.append(" · 缩放").append(zoomLabel(zoom));
            if (subtitleOn && curSubIndex >= 0) {
                tip.append(" · 字幕");
                if (subtitleOffsetMs != 0) tip.append(" ").append(subtitleOffsetMs).append("ms");
            }
            if (introSkipMs > 0 || outroSkipMs > 0) {
                tip.append(" · 片头").append(introSkipMs / 1000).append("s");
                if (outroSkipMs > 0) tip.append("/片尾").append(outroSkipMs / 1000).append("s");
            }
            tipsView.setText(tip.toString());
        } catch (Throwable ignored) {
        }
    }

    private String speedLabel() {
        return labelOf(SPEEDS[speedIndex]);
    }

    /** 0.5 → "0.5×"，1 → "1×"，1.25 → "1.25×" */
    private static String labelOf(float s) {
        if (s == Math.round(s)) return ((int) s) + "×";
        String v = String.format(java.util.Locale.US, "%.2f", s);
        if (v.endsWith("0")) v = v.substring(0, v.length() - 1);
        return v + "×";
    }

    private void togglePause() {
        try {
            if (isPlaying()) {
                if (ijkPlayer != null) ijkPlayer.pause();
                else if (mpPlayer != null) mpPlayer.pause();
                else if (videoView != null && isVideoViewEngine()) videoView.pause();
                LogBus.mark("P", "pause at " + pos());
            } else {
                if (ijkPlayer != null) ijkPlayer.start();
                else if (mpPlayer != null) mpPlayer.start();
                else if (videoView != null && isVideoViewEngine()) videoView.start();
                LogBus.mark("P", "resume at " + pos());
            }
        } catch (Throwable t) {
            LogBus.mark("P", "togglePause fail: " + t);
        }
        scheduleHide();
    }

    private void seekBy(long deltaMs) {
        long d = dur();
        long now = android.os.SystemClock.uptimeMillis();
        // 连续按方向键时基于「上一次的目标」继续累加，而不是等播放器回报的新位置
        long before = (seekTargetMs >= 0 && now - seekTargetAt < 1500) ? seekTargetMs : pos();
        long target = before + deltaMs;
        if (target < 0) target = 0;
        if (d > 0 && target > d - 1000) target = d - 1000;
        seekTargetMs = target;
        seekTargetAt = now;
        // 合并成一次真正的 seek：老设备每次 seek 都要重排缓冲，按一次发一次会越按越卡
        mainHandler.removeCallbacks(applySeekTargetRunnable);
        mainHandler.postDelayed(applySeekTargetRunnable, 140);
        showSeekHint(target, target - before, d);
    }

    private final Runnable applySeekTargetRunnable = new Runnable() {
        @Override
        public void run() {
            if (seekTargetMs >= 0) doSeek(seekTargetMs);
        }
    };

    /**
     * 底部时间胶囊：当前时间 / 总时长 + 这次一共快进了多少。
     * 连续按方向键时累计，停手 1.4 秒后自动消失。
     */
    private void showSeekHint(final long targetMs, final long deltaMs, final long durationMs) {
        if (seekHintView == null || seekHintTime == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - seekHintLastAt > 1600) seekHintAccumMs = 0;
                    seekHintLastAt = now;
                    seekHintAccumMs += deltaMs;
                    long t = Math.max(0, targetMs);
                    String d = durationMs > 0 ? formatTime(durationMs) : "--:--";
                    seekHintTime.setText(formatTime(t) + " / " + d);
                    long step = Math.abs(deltaMs) / 1000;
                    long total = Math.abs(seekHintAccumMs) / 1000;
                    String dir = deltaMs >= 0 ? "快进" : "快退";
                    String txt;
                    if (total > step) {
                        txt = dir + " " + step + " 秒（本次共 " + total + " 秒）";
                    } else {
                        txt = dir + " " + step + " 秒";
                    }
                    if (durationMs > 0) {
                        long left = Math.max(0, durationMs - t) / 1000;
                        txt = txt + " · 还剩 " + (left >= 3600
                                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                                left / 3600, (left % 3600) / 60, left % 60)
                                : String.format(java.util.Locale.ROOT, "%d:%02d",
                                left / 60, left % 60));
                    }
                    seekHintDelta.setText(txt);
                    seekHintView.setVisibility(View.VISIBLE);
                    mainHandler.removeCallbacks(hideSeekHintRunnable);
                    mainHandler.postDelayed(hideSeekHintRunnable, 1400);
                } catch (Throwable ignored) {
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    private void doSeek(long ms) {
        LogBus.mark("P", "seek -> " + ms + "ms (" + engineName() + ")");
        try {
            if (ijkPlayer != null) ijkPlayer.seekTo(ms);
            else if (mpPlayer != null) mpPlayer.seekTo((int) ms);
            else if (videoView != null && isVideoViewEngine()) videoView.seekTo((int) ms);
        } catch (Throwable t) {
            LogBus.mark("P", "seek fail: " + t);
        }
    }

    private interface Choice {
        void onPick(int index);
    }

    /** 通用「全部选项」面板：长按控制条按钮时用，当前项打钩 */
    private void showChoice(String title, String subtitle, String[] names, int current,
                            final Choice cb) {
        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, title));
        if (subtitle != null && !subtitle.isEmpty()) root.addView(Ui.subtitle(this, subtitle));
        final Dialog dialog = Ui.dialogScroll(this, root, 0.44, 0.74);
        for (int i = 0; i < names.length; i++) {
            Ui.gap(this, root, 8);
            final int index = i;
            String label = names[i] + (i == current ? "    ✓" : "");
            TextView row = Ui.action(this, label, false);
            if (i == current) {
                row.setTextColor(getResources().getColor(R.color.accent_light));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    cb.onPick(index);
                }
            });
            root.addView(row);
        }
        Ui.gap(this, root, 14);
        TextView close = Ui.ghost(this, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(close);
        dialog.show();
    }

    private void showSpeedPicker() {
        String[] names = new String[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = labelOf(SPEEDS[i]);
        }
        showChoice("播放速度", engineSupportsRate()
                ? "当前 " + speedLabel()
                : "此引擎不支持变速", names, speedIndex,
                new Choice() {
                    @Override
                    public void onPick(int index) {
                        speedIndex = index;
                        if (applySpeed(SPEEDS[speedIndex])) {
                            showHud("速度 " + speedLabel());
                        } else {
                            showHud("此引擎不支持变速，请长按画面快进");
                        }
                        if (btnSpeed != null) btnSpeed.setText("速度 " + speedLabel());
                        refreshPlaybackInfo();
                    }
                });
    }

    /** 长按快进/快退：按住连续跳 5 秒 */
    private void setRepeatingSeek(View v, final long step) {
        if (v == null) return;
        final Runnable repeat = new Runnable() {
            @Override
            public void run() {
                seekBy(step);
                mainHandler.postDelayed(this, 280);
            }
        };
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mainHandler.postDelayed(repeat, 450);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mainHandler.removeCallbacks(repeat);
                }
                return false;   // 让单击照常生效
            }
        });
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        applySpeed(SPEEDS[speedIndex]);
        btnSpeed.setText("速度 " + speedLabel());
    }

    /** 当前引擎能不能真的改播放速率 */
    private boolean isVideoViewEngine() {
        return engine == Config.ENGINE_VIDEOVIEW;
    }

    private boolean engineSupportsRate() {
        if (ijkPlayer != null) return true;
        // 系统 MediaPlayer 的 setPlaybackParams 是 Android 6.0(API 23) 才有的
        return mpPlayer != null && android.os.Build.VERSION.SDK_INT >= 23;
    }

    /** 真正下发给内核的变速入口（ijk 原生支持，系统解码见 applyMpSpeed） */
    private boolean applySpeed(float s) {
        if (ijkPlayer != null) {
            try {
                ijkPlayer.setSpeed(s);
                LogBus.mark("P", "speed -> " + s);
                return true;
            } catch (Throwable t) {
                LogBus.mark("P", "setSpeed fail: " + t);
                return false;
            }
        }
        if (mpPlayer != null) {
            return applyMpSpeed(mpPlayer, s);
        }
        return false;
    }

    /** 系统解码变速：API 23+ 用 PlaybackParams（反射调用，老系统直接不支持） */
    private boolean applyMpSpeed(Object player, float s) {
        try {
            Class<?> ppCls = Class.forName("android.media.PlaybackParams");
            Object pp = ppCls.getDeclaredConstructor().newInstance();
            ppCls.getMethod("setSpeed", float.class).invoke(pp, s);
            player.getClass().getMethod("setPlaybackParams", ppCls).invoke(player, pp);
            LogBus.mark("P", "mp playbackParams speed -> " + s);
            return true;
        } catch (Throwable t) {
            LogBus.mark("P", "mp speed unsupported: " + t);
            return false;
        }
    }

    private Runnable skipForwardRunnable;
    private boolean holdByRate;

    /**
     * 长按屏幕加速：按住期间按设置的倍速播放，松手回到原来的倍速。
     * ijk 用内核变速；系统解码（尤其 Android 4.3）没有变速接口，
     * 就用「边播边跳」近似快进，保证所有引擎都能用。
     */
    private void startHoldSpeed() {
        if (holdingSpeed) return;
        if (!prepared || !isPlaying()) return;
        float hold = Config.getHoldSpeed(this);
        if (engineSupportsRate() && applySpeed(hold)) {
            holdingSpeed = true;
            holdByRate = true;
            showHud("×" + trimSpeed(hold) + " 倍速播放中（松开恢复）");
            return;
        }
        holdingSpeed = true;
        holdByRate = false;
        skipForwardRunnable = new Runnable() {
            @Override
            public void run() {
                if (!holdingSpeed || released.get() || isFinishing()) return;
                long d = dur();
                long next = pos() + 600;
                if (d > 0 && next > d - 1500) next = Math.max(0, d - 1500);
                doSeek(next);
                mainHandler.postDelayed(this, 300);
            }
        };
        mainHandler.post(skipForwardRunnable);
        showHud("快进中（此引擎不支持变速，改用跳放）");
    }

    private void stopHoldSpeed() {
        if (!holdingSpeed) return;
        holdingSpeed = false;
        if (skipForwardRunnable != null) {
            mainHandler.removeCallbacks(skipForwardRunnable);
            skipForwardRunnable = null;
        }
        if (holdByRate) applySpeed(SPEEDS[speedIndex]);
    }

    private static String trimSpeed(float s) {
        return s == (int) s ? String.valueOf((int) s)
                : String.format(java.util.Locale.US, "%.2f", s).replaceAll("0$", "");
    }

    /**
     * 片头 / 片尾设置面板（原来 4 个按钮合并成一个入口）。
     * 数值按「文件夹」保存：同一部剧/同一批番共用一套片头片尾。
     */
    private void showTrimDialog() {
        LogBus.mark("P", "open trim dialog, folder=" + folderKey
                + " intro=" + introSkipMs + " outro=" + outroSkipMs);
        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "片头 / 片尾"));
        root.addView(Ui.subtitle(this, "按文件夹保存："
                + (folderKey == null || folderKey.isEmpty() ? "根目录" : folderKey)));

        final TextView info = Ui.body(this, "");
        info.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(info);

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                info.setText("片头跳过 " + (introSkipMs / 1000) + " 秒\n片尾跳过 "
                        + (outroSkipMs / 1000) + " 秒");
            }
        };

        Ui.gap(this, root, 14);
        TextView introMinus = Ui.ghost(this, "片头 −5s");
        introMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                introSkipMs = Math.max(0, introSkipMs - TRIM_STEP_MS);
                Config.setIntroSkipMs(PlayerActivity.this, folderKey, introSkipMs);
                doSeek(introSkipMs);
                refresh.run();
                refreshPlaybackInfo();
            }
        });
        TextView introPlus = Ui.ghost(this, "片头 +5s");
        introPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                introSkipMs = introSkipMs + TRIM_STEP_MS;
                Config.setIntroSkipMs(PlayerActivity.this, folderKey, introSkipMs);
                doSeek(introSkipMs);
                refresh.run();
                refreshPlaybackInfo();
            }
        });
        root.addView(Ui.row(this, introMinus, introPlus, 12));

        Ui.gap(this, root, 10);
        TextView outroMinus = Ui.ghost(this, "片尾 −5s");
        outroMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                outroSkipMs = Math.max(0, outroSkipMs - TRIM_STEP_MS);
                Config.setOutroSkipMs(PlayerActivity.this, folderKey, outroSkipMs);
                refresh.run();
                refreshPlaybackInfo();
            }
        });
        TextView outroPlus = Ui.ghost(this, "片尾 +5s");
        outroPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                outroSkipMs = outroSkipMs + TRIM_STEP_MS;
                Config.setOutroSkipMs(PlayerActivity.this, folderKey, outroSkipMs);
                refresh.run();
                refreshPlaybackInfo();
            }
        });
        root.addView(Ui.row(this, outroMinus, outroPlus, 12));

        final Dialog dialog = Ui.dialog(this, root, 0.46);

        Ui.gap(this, root, 16);
        TextView clear = Ui.ghost(this, "全部清零");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                introSkipMs = 0;
                outroSkipMs = 0;
                Config.setIntroSkipMs(PlayerActivity.this, folderKey, 0);
                Config.setOutroSkipMs(PlayerActivity.this, folderKey, 0);
                refresh.run();
                refreshPlaybackInfo();
                showToast("已清除本文件夹的片头片尾");
            }
        });
        TextView done = Ui.primary(this, "完成");
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                scheduleHide();
            }
        });
        root.addView(Ui.row(this, clear, done, 12));

        refresh.run();
        dialog.show();
        Ui.relayout(this, dialog, 0.46);
        if (introPlus != null && !isInTouchModeCompat(dialog)) introPlus.requestFocus();
    }

    private boolean isInTouchModeCompat(Dialog d) {
        try {
            return d != null && d.getWindow() != null && d.getWindow().getDecorView().isInTouchMode();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 编码探测（H.264 / H.265） ====================

    /** 当前视频轨的 mime（拿不到返回 null） */
    private String currentVideoMime() {
        try {
            if (ijkPlayer != null) {
                tv.danmaku.ijk.media.player.misc.IjkTrackInfo[] infos = ijkPlayer.getTrackInfo();
                if (infos == null) return null;
                for (tv.danmaku.ijk.media.player.misc.IjkTrackInfo info : infos) {
                    if (info == null) continue;
                    if (info.getTrackType()
                            != tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) continue;
                    // ijk 没有直接的 getMimeType()：先取 format 里的 mime，取不到就用内联信息（含 h264/h265）
                    try {
                        tv.danmaku.ijk.media.player.misc.IMediaFormat fmt = info.getFormat();
                        if (fmt != null) {
                            String mime = fmt.getString(
                                    tv.danmaku.ijk.media.player.misc.IMediaFormat.KEY_MIME);
                            if (mime != null && !mime.isEmpty()) return mime;
                        }
                    } catch (Throwable ignored) {
                    }
                    return info.getInfoInline();
                }
            } else if (mpPlayer != null) {
                MediaPlayer.TrackInfo[] infos = mpPlayer.getTrackInfo();
                if (infos == null) return null;
                for (MediaPlayer.TrackInfo info : infos) {
                    if (info == null) continue;
                    if (info.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) continue;
                    android.media.MediaFormat f = info.getFormat();
                    return f == null ? null : f.getString("mime");
                }
            }
        } catch (Throwable t) {
            LogBus.mark("P", "currentVideoMime fail: " + t);
        }
        return null;
    }

    /** 把编码能力写进诊断日志：H.265 卡不卡，一眼能从日志判断 */
    private void logCodecInfo() {
        logCodecInfo(0);
    }

    private void logCodecInfo(final int attempt) {
        String m = currentVideoMime();
        // 部分内核只给 "Track 1" 这种名字，本机文件直接用 MediaExtractor 嗅探容器
        if (m != null && m.startsWith("Track")) m = null;
        final String mime = m;
        if (mime == null) {
            // 系统解码在 prepared 瞬间可能还没填好轨道信息，稍后重试一次
            if (attempt < 2 && !released.get()) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        logCodecInfo(attempt + 1);
                    }
                }, 1200);
                return;
            }
            LogBus.mark("P", "codec=unknown engine=" + engineName());
            return;
        }
        final boolean hevc = isHevcMime(mime);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String msg = "codec=" + mime + " size=" + videoW + "x" + videoH
                        + " engine=" + engineName();
                if (hevc) {
                    msg += " hwHevcSupported=" + CodecInfo.supportsHevcHardware()
                            + " canHwDecodeThis=" + CodecInfo.canHardwareDecodeHevc(videoW, videoH);
                }
                LogBus.mark("P", msg);
            }
        });
    }

    private static boolean isHevcMime(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        return m.contains("hevc") || m.contains("265") || m.contains("hvc1") || m.contains("hev1");
    }

    // ==================== 播放位置查询 ====================

    private long pos() {
        try {
            if (ijkPlayer != null) return ijkPlayer.getCurrentPosition();
            if (mpPlayer != null) return mpPlayer.getCurrentPosition();
            if (videoView != null && isVideoViewEngine()) return videoView.getCurrentPosition();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private long dur() {
        try {
            if (ijkPlayer != null) return ijkPlayer.getDuration();
            if (mpPlayer != null) return mpPlayer.getDuration();
            if (videoView != null && isVideoViewEngine()) return videoView.getDuration();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private boolean isPlaying() {
        try {
            if (ijkPlayer != null) return ijkPlayer.isPlaying();
            if (mpPlayer != null) return mpPlayer.isPlaying();
            if (videoView != null && isVideoViewEngine()) return videoView.isPlaying();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    // ==================== 按键 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                if (infoPanel != null && infoPanel.getVisibility() == View.VISIBLE) {
                    hideInfoPanel();
                }
                // 规则：日志可见 → 先关日志；否则一律「呼出控制条 + 焦点回到播放/暂停」，
                // 保证按完 MENU 立刻能用方向键选功能（原来是只第一次给焦点，之后焦点丢失）
                if (logView != null && logView.getVisibility() == View.VISIBLE) {
                    logView.setVisibility(View.GONE);
                    return true;
                }
                showController();
                return true;
            case KeyEvent.KEYCODE_INFO:
                // 部分电视遥控器有 Info 键，直接弹信息面板
                toggleInfoPanel();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (infoPanel != null && infoPanel.getVisibility() == View.VISIBLE) {
                    hideInfoPanel();
                    return true;
                }
                if (controllerVisible) {
                    hideController();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                playIndex(playlistIndex + 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                playIndex(playlistIndex - 1);
                return true;
            default:
                break;
        }

        if (!controllerVisible) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    togglePause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekBy(-SEEK_STEP_MS);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekBy(SEEK_STEP_MS);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showController();
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== 引擎初始化 ====================

    private String engineName() {
        return engine >= 0 && engine < Config.ENGINE_COUNT ? Config.ENGINE_NAMES[engine] : ("?" + engine);
    }

    private void logScreenInfo() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            String info = dm.widthPixels + "x" + dm.heightPixels + " dpi=" + dm.densityDpi
                    + " density=" + dm.density;
            Config.lastScreenInfo = info;
            LogBus.mark("P", "screen " + info);
        } catch (Throwable t) {
            LogBus.mark("P", "screen info fail: " + t);
        }
    }

    private void logLibraryMaps() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            Set<String> seen = new HashSet<String>();
            int n = 0;
            while ((line = br.readLine()) != null && n < 40) {
                if (!line.contains(".so")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 6) continue;
                if (!"00000000".equals(parts[2])) continue;
                String path = parts[parts.length - 1];
                if (!(path.contains("/data/app/") || path.contains("ijk"))) continue;
                if (seen.contains(path)) continue;
                seen.add(path);
                LogBus.mark("MAP", parts[0] + " " + path);
                n++;
            }
        } catch (Throwable t) {
            LogBus.mark("MAP", "read maps fail: " + t);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void setupTexture() {
        if (textureView == null) return;
        // 不透明：4K 面板上少一次整屏 alpha 混合，能省不少 GPU
        try {
            textureView.setOpaque(true);
        } catch (Throwable ignored) {
        }
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                try {
                    if (textureSurface != null) textureSurface.release();
                    textureSurface = new Surface(st);
                } catch (Throwable t) {
                    LogBus.mark("P", "texture surface fail: " + t);
                }
                LogBus.mark("P", "texture available " + width + "x" + height);
                clampTextureBuffer(st, width, height);
                try {
                    if (ijkPlayer != null) ijkPlayer.setSurface(textureSurface);
                    if (mpPlayer != null && textureSurface != null) mpPlayer.setSurface(textureSurface);
                } catch (Throwable t) {
                    LogBus.mark("P", "texture attach fail: " + t);
                }
                pendingSurfaceAttach = false;
                applyVideoLayout();
                // 之前在等 surface 的情况，补一次启动
                if (!prepared && textureSurface != null && pendingUrl != null) {
                    String u = pendingUrl;
                    pendingUrl = null;
                    startPlayback(u);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                // v1.49：先解绑，再释放 Surface。否则解码线程会往已经销毁的 ANativeWindow 送帧，
                // 表现为黑屏/花屏，部分机型直接 native 崩溃
                try {
                    if (ijkPlayer != null) ijkPlayer.setSurface(null);
                    if (mpPlayer != null) mpPlayer.setDisplay(null);
                } catch (Throwable ignored) {
                }
                if (textureSurface != null) {
                    textureSurface.release();
                    textureSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    private void setupSurface() {
        surfaceView.setKeepScreenOn(true);
        holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                // 改比例/旋转会重建 Surface，重建后必须把显示重新绑给播放器，否则黑屏
                try {
                    if (ijkPlayer != null) {
                        ijkPlayer.setDisplay(h);
                        LogBus.mark("P", "re-attach ijk display");
                    }
                    if (mpPlayer != null) {
                        mpPlayer.setDisplay(h);
                        LogBus.mark("P", "re-attach mp display");
                    }
                } catch (Throwable t) {
                    LogBus.mark("P", "re-attach display fail: " + t);
                }
                LogBus.mark("P", "surfaceCreated " + surfaceView.getWidth() + "x" + surfaceView.getHeight());
                // 从 TextureView 切回 SurfaceView 时，surface 是新建的，得把输出重新接回来
                if (!useTexture && prepared) {
                    attachPlayerToLayer();
                }
                // SurfaceView 模式：surface 建好之前不能开播，这里补一次
                if (!useTexture && pendingUrl != null && !prepared) {
                    String u = pendingUrl;
                    pendingUrl = null;
                    LogBus.mark("P", "surface ready, start pending playback (compat mode)");
                    startPlayback(u);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
                LogBus.mark("P", "surfaceChanged " + width + "x" + height + " fmt=" + format);
                // 4K 面板上把叠加层的缓冲压到 1080p（v1.41 删这段时把 4K 缓冲带回来了，
                // 老芯片每帧要处理 3840×2160 的缓冲，正是「卡卡的」的主要原因之一）
                clampSurfaceSize(h, width, height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                LogBus.mark("P", "surfaceDestroyed");
                // 同上：解绑再让 Surface 走掉
                try {
                    if (ijkPlayer != null) ijkPlayer.setSurface(null);
                    if (mpPlayer != null) mpPlayer.setDisplay(null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 把 SurfaceView 的缓冲限制到 1080p 以内（超出部分由显示芯片免费放大） */
    private void clampSurfaceSize(SurfaceHolder h, int w, int hgt) {
        try {
            if (h == null || w <= 0 || hgt <= 0) return;
            float s0 = Math.min(1f, Math.min(1920f / w, 1080f / hgt));
            if (s0 >= 0.999f) return;
            int bw = Math.max(2, ((int) (w * s0)) & ~1);
            int bh = Math.max(2, ((int) (hgt * s0)) & ~1);
            if (bw == lastClampW && bh == lastClampH && w == lastViewW && hgt == lastViewH) return;
            lastClampW = bw;
            lastClampH = bh;
            lastViewW = w;
            lastViewH = hgt;
            h.setFixedSize(bw, bh);
            LogBus.mark("P", "clamp surface " + w + "x" + hgt + " -> " + bw + "x" + bh);
        } catch (Throwable t) {
            LogBus.mark("P", "clamp surface fail: " + t);
        }
    }

    /**
     * 画面布局：比例（按设备自适应）+ 旋转 + 缩放 + 平移（v1.47 重写）。
     *
     * <p>分两步，任何一档都不会出现拉伸或裁切：
     * <ol>
     *   <li><b>先算画面框</b>：用「本机屏幕实际尺寸」而不是写死的比例。
     *       适应屏幕 = 整块屏幕；16:9 / 4:3 = 屏幕内能放下的最大该比例矩形；
     *       原始像素 = 视频自己多少像素就是多少。</li>
     *   <li><b>再把视频等比完整装入这个框</b>：缩放比取 min(框宽/视频宽, 框高/视频高)，
     *       短的那个方向留黑边。宽高比永远是视频自己的，人脸不会被拉胖，字幕不会被切掉。</li>
     * </ol>
     * 手机竖屏、手机横屏、电视 16:9 的屏幕形状各不相同，框是每次实时算的，
     * 所以同一档位在不同设备上落到的尺寸也不同（这就是「适应设备」）。
     *
     * <p>关键点：<b>只改 View 的尺寸和变换，绝不动 Surface</b>。
     * 旋转用 {@code setRotation}、缩放 {@code setScaleX/Y}、平移 {@code setTranslation}，
     * 全是 GPU 矩阵变换：不重建 Surface、不换图层、不重开播放器，所以瞬时生效、不黑屏。
     */
    private void applyVideoLayout() {
        try {
            // 可用区域：以播放界面根容器实测尺寸为准（根容器没量出来时退回屏幕尺寸）
            int sw = 0, sh = 0;
            View root = findViewById(R.id.player_root);
            if (root != null) {
                sw = root.getWidth();
                sh = root.getHeight();
            }
            if (sw <= 0 || sh <= 0) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                sw = dm.widthPixels;
                sh = dm.heightPixels;
            }
            if (sw <= 0 || sh <= 0) return;

            int vw = videoW > 0 ? videoW : sw;
            int vh = videoH > 0 ? videoH : sh;
            float vr = (float) vw / vh;
            if (vr <= 0.01f) {
                vw = sw;
                vh = sh;
                vr = (float) sw / sh;
            }

            // ① 目标画面框（由档位 + 本机屏幕形状共同决定）
            float boxW = sw;
            float boxH = sh;
            float boxRatio = 0f;
            if (aspectMode == Config.ASPECT_16_9) boxRatio = 16f / 9f;
            else if (aspectMode == Config.ASPECT_4_3) boxRatio = 4f / 3f;
            if (boxRatio > 0f) {
                // 屏幕内能放下的最大「该比例」矩形
                if ((float) sw / sh > boxRatio) {
                    boxH = sh;
                    boxW = sh * boxRatio;
                } else {
                    boxW = sw;
                    boxH = sw / boxRatio;
                }
            }

            // ② 视频等比完整装入框内（取小值 = 完整显示，不会裁掉任何一边）
            int baseW, baseH;
            if (aspectMode == Config.ASPECT_PIXEL) {
                // 1:1 原始像素；只有屏幕装不下时才等比缩小
                float s = Math.min(1f, Math.min((float) sw / vw, (float) sh / vh));
                baseW = Math.max(1, (int) (vw * s));
                baseH = Math.max(1, (int) (vh * s));
            } else {
                float s = Math.min(boxW / vw, boxH / vh);
                baseW = Math.max(1, (int) (vw * s));
                baseH = Math.max(1, (int) (vh * s));
                // 取整保险：任何情况下画面都不越过屏幕
                if (baseW > sw) {
                    baseH = Math.max(1, (int) ((float) baseH * sw / baseW));
                    baseW = sw;
                }
                if (baseH > sh) {
                    baseW = Math.max(1, (int) ((float) baseW * sh / baseH));
                    baseH = sh;
                }
            }

            // 先决定渲染层：需要 View 变换才用 TextureView，否则回 SurfaceView
            switchRenderLayer(shouldUseTexture());

            // 只改尺寸（不换图层、不重建 Surface）；旋转、缩放、平移都是 View 变换
            applyVideoParams(textureView, baseW, baseH);
            applyVideoParams(surfaceView, baseW, baseH);
            applyVideoParams(videoView, baseW, baseH);

            // 旋转 90/270 后画面在屏幕上的外接尺寸（宽高互换），用来限制拖动范围
            boolean rot90 = (rotationDeg == 90 || rotationDeg == 270);
            float visW = (rot90 ? baseH : baseW) * zoom;
            float visH = (rot90 ? baseW : baseH) * zoom;
            int maxPanX = (int) Math.max(0, (visW - sw) / 2f);
            int maxPanY = (int) Math.max(0, (visH - sh) / 2f);
            if (panX > maxPanX) panX = maxPanX;
            if (panX < -maxPanX) panX = -maxPanX;
            if (panY > maxPanY) panY = maxPanY;
            if (panY < -maxPanY) panY = -maxPanY;

            View layer = isVideoViewEngine() ? videoView : (useTexture ? textureView : surfaceView);
            if (layer != null) {
                layer.setPivotX(baseW / 2f);
                layer.setPivotY(baseH / 2f);
                layer.setRotation(rotationDeg);
                layer.setScaleX(zoom);
                layer.setScaleY(zoom);
                layer.setTranslationX(panX);
                layer.setTranslationY(panY);
            }

            LogBus.mark("P", "layout aspect=" + ASPECT_NAMES[aspectMode] + " rot=" + rotationDeg
                    + " zoom=" + zoom + " pan=" + (int) panX + "," + (int) panY
                    + " screen=" + sw + "x" + sh
                    + " box=" + (int) boxW + "x" + (int) boxH
                    + " draw=" + baseW + "x" + baseH + " video=" + videoW + "x" + videoH);
        } catch (Throwable t) {
            LogBus.mark("P", "applyVideoLayout fail: " + t);
        }
    }

    /** TextureView 的缓冲尺寸：4K 面板 + 老芯片时按比例压到 1080p 以内 */
    private void clampTextureBuffer(android.graphics.SurfaceTexture st, int w, int h) {
        try {
            if (st == null || w <= 0 || h <= 0) return;
            float s0 = Math.min(1f, Math.min(1920f / w, 1080f / h));
            int bw = Math.max(1, (int) (w * s0));
            int bh = Math.max(1, (int) (h * s0));
            st.setDefaultBufferSize(bw, bh);
            LogBus.mark("P", "texture buffer " + w + "x" + h + " -> " + bw + "x" + bh);
        } catch (Throwable t) {
            LogBus.mark("P", "clamp texture buffer fail: " + t);
        }
    }

    private void applyVideoParams(View v, int w, int h) {
        if (v == null) return;
        // 尺寸没变就别碰 LayoutParams：改一次就会让 SurfaceView 重建 Surface，
        // 解码器一被重新绑定，音画就容易错位（v1.44 之前每次 onVideoSizeChanged 都重设一遍）
        android.view.ViewGroup.LayoutParams cur = v.getLayoutParams();
        if (cur != null && cur.width == w && cur.height == h) return;
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(w, h);
        lp.gravity = android.view.Gravity.CENTER;
        v.setLayoutParams(lp);
    }

    /** 旋转被禁用的原因：只有 SurfaceView 兼容模式会禁旋转 */
    /** 当前引擎/渲染方式能不能做旋转、缩放这类 View 变换 */
    private boolean canRotate() {
        if (isVideoViewEngine()) return false;
        return Config.getRenderMode(this) != Config.RENDER_SURFACE;
    }

    private String rotateBlockedReason() {
        if (isVideoViewEngine()) {
            return "VideoView 不支持旋转";
        }
        return "SurfaceView 不支持旋转";
    }

    /** 双指缩放归位（换片 / 换引擎时调用） */
    private void resetZoom() {
        zoom = 1f;
        panX = 0;
        panY = 0;
        updateZoomLabel();
    }

    private void setZoom(float z) {
        zoom = Math.max(1f, Math.min(4f, z));
        panX = 0;
        panY = 0;
        applyVideoLayout();
        updateZoomLabel();
        showHud(zoom <= 1.001f ? "画面恢复原始大小" : "画面缩放 " + zoomLabel(zoom));
    }

    /** 放大 / 缩小一档（dir = +1 放大，-1 缩小），到顶到底就不动 */
    private void stepZoom(int dir) {
        float[] steps = ZOOM_ALL;
        float target = zoom;
        if (dir > 0) {
            for (float z : steps) {
                if (z > zoom + 0.01f) {
                    target = z;
                    break;
                }
            }
        } else {
            for (int i = steps.length - 1; i >= 0; i--) {
                if (steps[i] < zoom - 0.01f) {
                    target = steps[i];
                    break;
                }
            }
        }
        if (Math.abs(target - zoom) < 0.005f) {
            showHud(dir > 0 ? "已经放到最大 " + zoomLabel(zoom) : "已经回到 1×");
            return;
        }
        setZoom(target);
        showHud((dir > 0 ? "放大 " : "缩小 ") + zoomLabel(zoom));
    }

    /** 长按缩放按钮：列出全部档位 */
    private Runnable zoomPicker() {
        return new Runnable() {
            @Override
            public void run() {
                String[] names = new String[ZOOM_ALL.length];
                for (int i = 0; i < ZOOM_ALL.length; i++) names[i] = zoomLabel(ZOOM_ALL[i]);
                showChoice("画面缩放", "当前 " + zoomLabel(zoom), names, nearestZoomIndex(), new Choice() {
                    @Override
                    public void onPick(int index) {
                        setZoom(ZOOM_ALL[index]);
                    }
                });
            }
        };
    }

    private int nearestZoomIndex() {
        int best = 0;
        float diff = Float.MAX_VALUE;
        for (int i = 0; i < ZOOM_ALL.length; i++) {
            float d = Math.abs(ZOOM_ALL[i] - zoom);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    private static String zoomLabel(float z) {
        return String.format(java.util.Locale.US, "%.2f", z).replaceAll("0+$", "").replaceAll("\\.$", "") + "×";
    }

    private void updateZoomLabel() {
        // 按钮文字保持定宽（「放大」/「缩小」），倍数显示在控制条上方那行状态里，
        // 否则每点一次按钮宽度就变，电视上用方向键选按钮会乱跳
        if (controllerVisible) refreshPlaybackInfo();
    }

    /**
     * 建立可选字幕列表：
     *  - 关闭
     *  - 同目录外挂字幕（srt/ass/ssa/vtt；.sup 是 PGS 图形字幕，只能标注不支持）
     *  - 内封字幕轨（ijkplayer / MediaPlayer 的 trackInfo）
     */
    private void buildSubItems() {
        subItems.clear();
        SubItem off = new SubItem();
        off.type = 0;
        off.label = "关闭字幕";
        subItems.add(off);

        String[] subs = getIntent().getStringArrayExtra("sublist");
        if (subs != null) {
            for (String name : subs) {
                SubItem it = new SubItem();
                it.type = 1;
                it.file = name;
                String lower = name.toLowerCase(Locale.ROOT);
                it.label = "外挂：" + name + (lower.endsWith(".sup") ? "（PGS 图形字幕，暂不支持）" : "");
                subItems.add(it);
            }
        }

        // 内封字幕轨
        try {
            if (ijkPlayer != null) {
                tv.danmaku.ijk.media.player.misc.IjkTrackInfo[] infos = ijkPlayer.getTrackInfo();
                if (infos != null) {
                    int n = 0;
                    for (int i = 0; i < infos.length; i++) {
                        if (infos[i] == null) continue;
                        int t = infos[i].getTrackType();
                        if (t != tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                                && t != tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                            continue;
                        }
                        SubItem it = new SubItem();
                        it.type = 2;
                        it.trackIndex = i;
                        String lang = infos[i].getLanguage();
                        n++;
                        it.label = "内封字幕 " + n
                                + (lang != null && !lang.isEmpty() && !"und".equals(lang) ? "  [" + lang + "]" : "");
                        subItems.add(it);
                    }
                }
            } else if (mpPlayer != null) {
                MediaPlayer.TrackInfo[] infos = mpPlayer.getTrackInfo();
                if (infos != null) {
                    int n = 0;
                    for (int i = 0; i < infos.length; i++) {
                        if (infos[i] == null) continue;
                        int t = infos[i].getTrackType();
                        if (t != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                                && t != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                            continue;
                        }
                        SubItem it = new SubItem();
                        it.type = 2;
                        it.trackIndex = i;
                        String lang = infos[i].getLanguage();
                        n++;
                        it.label = "内封字幕 " + n
                                + (lang != null && !lang.isEmpty() && !"und".equals(lang) ? "  [" + lang + "]" : "");
                        subItems.add(it);
                    }
                }
            }
        } catch (Throwable t) {
            LogBus.mark("P", "buildSubItems embedded fail: " + t);
        }
    }

    /** 自动挑一条：优先同目录同名外挂，其次内封第一条 */
    private void autoPickSubtitle() {
        if (cues != null || curSubIndex >= 0 || subtitleLoading) return;
        buildSubItems();
        String base = baseName(displayName != null && !displayName.isEmpty() ? displayName : mediaUrl);
        int best = -1, bestScore = -1, firstEmbedded = -1;
        for (int i = 0; i < subItems.size(); i++) {
            SubItem it = subItems.get(i);
            if (it.type == 2 && firstEmbedded < 0) firstEmbedded = i;
            if (it.type != 1) continue;
            String lower = it.file.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".sup")) continue;      // PGS 解析不了，不自动选
            String nb = baseName(it.file);
            int score = -1;
            if (nb.equalsIgnoreCase(base)) score = 100;
            else if (nb.toLowerCase(Locale.ROOT).startsWith(base.toLowerCase(Locale.ROOT))) score = 50;
            if (score < 0) continue;
            if (lower.contains("zh") || lower.contains("chs") || lower.contains("chi")
                    || lower.contains("中文") || lower.contains("简体")) score += 5;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best >= 0) {
            LogBus.mark("P", "auto pick external subtitle " + subItems.get(best).file);
            applySubtitle(best);
        } else if (firstEmbedded >= 0) {
            LogBus.mark("P", "auto pick embedded subtitle " + firstEmbedded);
            applySubtitle(firstEmbedded);
        } else if (subItems.size() > 1) {
            LogBus.mark("P", "no auto subtitle matched");
        }
    }

    /** 应用某条字幕（0 关 / 1 外挂 / 2 内封） */
    private void applySubtitle(int index) {
        if (index < 0 || index >= subItems.size()) return;
        buildSubItems();
        if (index >= subItems.size()) return;
        SubItem it = subItems.get(index);
        // v1.49：先按「旧的」选中项取消内封轨，再切 curSubIndex。
        // 以前顺序反了，deselectEmbeddedSubtitle() 读到的已经是新选项（外挂/关闭），
        // 旧的内封轨一直 selected，内封和外挂每 300ms 互相覆盖，看起来像闪字/串字幕
        deselectEmbeddedSubtitle();
        curSubIndex = index;
        embeddedText = null;
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);
        switch (it.type) {
            case 0:
                cues = null;
                subtitleOn = false;
                LogBus.mark("P", "subtitle off");
                break;
            case 1:
                if (it.file.toLowerCase(Locale.ROOT).endsWith(".sup")) {
                    showToast("PGS(.sup) 是图形字幕，暂不支持；请用 srt/ass");
                    subtitleOn = false;
                    break;
                }
                subtitleOn = true;
                loadExternalSubtitle(it.file);
                break;
            default:
                subtitleOn = true;
                cues = null;
                try {
                    if (ijkPlayer != null) ijkPlayer.selectTrack(it.trackIndex);
                    else if (mpPlayer != null) mpPlayer.selectTrack(it.trackIndex);
                    LogBus.mark("P", "select embedded subtitle track " + it.trackIndex);
                    showToast("已切换：" + it.label);
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (embeddedText == null) {
                                showToast("该内核拿不到内封字幕文本，建议用同名 .srt/.ass 外挂字幕");
                                LogBus.mark("P", "embedded subtitle produced no text in 3s");
                            }
                        }
                    }, 3000);
                } catch (Throwable t) {
                    LogBus.mark("P", "select embedded subtitle fail: " + t);
                    showToast("切换内封字幕失败：" + t);
                }
                break;
        }
        refreshPlaybackInfo();
    }

    private void deselectEmbeddedSubtitle() {
        try {
            if (ijkPlayer != null && curSubIndex >= 0 && curSubIndex < subItems.size()
                    && subItems.get(curSubIndex).type == 2) {
                ijkPlayer.deselectTrack(subItems.get(curSubIndex).trackIndex);
            }
            if (mpPlayer != null && curSubIndex >= 0 && curSubIndex < subItems.size()
                    && subItems.get(curSubIndex).type == 2) {
                mpPlayer.deselectTrack(subItems.get(curSubIndex).trackIndex);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 下载并解析外挂字幕 */
    private void loadExternalSubtitle(final String fileName) {
        if (fileName.equals(loadedSubFile) && cues != null && !cues.isEmpty()) return;
        subtitleLoading = true;
        loadedSubFile = fileName;
        LogBus.mark("P", "load external subtitle " + fileName);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String content = fetchText(fileName);
                    final java.util.List<Subtitle.Cue> parsed = Subtitle.parse(content);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            subtitleLoading = false;
                            cues = parsed;
                            LogBus.mark("P", "subtitle loaded: " + fileName + " cues=" + parsed.size());
                            if (parsed.isEmpty()) showToast("字幕文件为空或格式不支持：" + fileName);
                            else showToast("已加载字幕：" + fileName);
                        }
                    });
                } catch (final Throwable e) {
                    subtitleLoading = false;
                    LogBus.mark("P", "subtitle load failed: " + e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showToast("字幕加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "subtitle-load").start();
    }

    private static String baseName(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String n = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void applySubtitlePosition() {
        if (subtitleView == null) return;
        try {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) subtitleView.getLayoutParams();
            int m = (int) (getResources().getDisplayMetrics().density * 12);
            if (subPos == 1) {
                lp.gravity = android.view.Gravity.CENTER;
                lp.topMargin = 0;
                lp.bottomMargin = 0;
            } else if (subPos == 2) {
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                lp.topMargin = m * 6;
                lp.bottomMargin = 0;
            } else {
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                lp.topMargin = 0;
                lp.bottomMargin = m * 12;
            }
            subtitleView.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    /** 内封字幕轨的文本回调（ijkplayer / MediaPlayer 都会走这里） */
    private void onEmbeddedText(String text) {
        embeddedText = (text == null || text.trim().isEmpty()) ? null : text.trim();
        LogBus.mark("SUB", "embedded text: " + (embeddedText == null ? "null" : embeddedText));
        if (subtitleView == null) return;
        if (!subtitleOn || embeddedText == null) {
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(embeddedText);
            subtitleView.setVisibility(View.VISIBLE);
        }
    }

    /** 下载字幕文本（WebDAV / SMB 都支持） */
    private String fetchText(String subName) throws Exception {
        if (mode == 2) {
            // 本地文件：直接读同一个文件夹里的字幕
            String dir = folderKey == null ? "" : folderKey;
            java.io.File f = new java.io.File(dir, subName);
            if (!f.exists()) {
                // 兜底：用媒体文件所在目录
                String media = mediaUrl == null ? "" : mediaUrl;
                int cut = media.lastIndexOf('/');
                if (cut > 0) f = new java.io.File(media.substring(0, cut), subName);
            }
            LogBus.mark("P", "local subtitle " + f.getAbsolutePath() + " exists=" + f.exists());
            return decodeText(LocalClient.read(f.getAbsolutePath()));
        }
        String host2 = getIntent().getStringExtra("host");
        String share2 = getIntent().getStringExtra("share");
        String dir = folderKey == null ? "" : folderKey;
        String rel = dir;
        if (!rel.startsWith("/")) rel = "/" + rel;
        if (!rel.endsWith("/")) rel = rel + "/";
        rel = rel + subName;
        if (mode == 0) {
            SmbClient client = new SmbClient(host2, share2, user, pass);
            java.io.InputStream in = client.getInputStream(rel);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return decodeText(bos.toByteArray());
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } else {
            WebDavClient client = new WebDavClient(host2, user, pass);
            return decodeText(client.download(rel));
        }
    }

    /** 字幕编码：优先 UTF-8，失败按 GBK（国内字幕常见） */
    private static String decodeText(byte[] data) {
        try {
            String s = new String(data, "UTF-8");
            if (s.indexOf('\uFFFD') < 0) return s;
        } catch (Exception ignored) {
        }
        try {
            return new String(data, "GBK");
        } catch (Exception e) {
            return new String(data);
        }
    }

    /**
     * 字幕 + 音轨统一面板：一屏搞定字幕开关/字号/位置/延时/选轨/音轨切换。
     * 电视上用方向键在按钮间移动、确定键执行。
     */
    private void showAvDialog() {
        buildSubItems();
        final java.util.List<Integer> audioIndices = new java.util.ArrayList<>();
        final java.util.List<String> audioLabels = new java.util.ArrayList<>();
        collectAudioTracks(audioIndices, audioLabels);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        root.setPadding(pad, pad, pad, pad);

        root.addView(sectionLabel("字幕"));
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(dialogButtonSmall((subtitleOn ? "字幕:开" : "字幕:关"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (subtitleOn) {
                    subtitleOn = false;
                    if (subtitleView != null) subtitleView.setVisibility(View.GONE);
                } else {
                    subtitleOn = true;
                    if (curSubIndex < 0) autoPickSubtitle();
                }
                dialog.dismiss();
                showAvDialog();
            }
        }));
        tools.addView(dialogButtonSmall("字号 -", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                subtitleSize = Math.max(14f, subtitleSize - 4f);
                applySubtitleStyle();
                dialog.dismiss();
                showAvDialog();
            }
        }));
        tools.addView(dialogButtonSmall("字号 +", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                subtitleSize = Math.min(48f, subtitleSize + 4f);
                applySubtitleStyle();
                dialog.dismiss();
                showAvDialog();
            }
        }));
        tools.addView(dialogButtonSmall("位置：" + SUB_POS_NAMES[subPos], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                subPos = (subPos + 1) % SUB_POS_NAMES.length;
                Config.setSubtitlePos(PlayerActivity.this, subPos);
                applySubtitlePosition();
                dialog.dismiss();
                showAvDialog();
            }
        }));
        tools.addView(dialogButtonSmall("提前 0.5s", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                subtitleOffsetMs -= 500;
                dialog.dismiss();
                showAvDialog();
            }
        }));
        tools.addView(dialogButtonSmall("延后 0.5s", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                subtitleOffsetMs += 500;
                dialog.dismiss();
                showAvDialog();
            }
        }));
        root.addView(tools);

        for (int i = 0; i < subItems.size(); i++) {
            final int idx = i;
            SubItem it = subItems.get(i);
            String text = (i == curSubIndex && subtitleOn ? "● " : "○ ") + it.label;
            root.addView(dialogButton(text, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applySubtitle(idx);
                    dialog.dismiss();
                    showAvDialog();
                }
            }));
        }

        boolean hasEmbedded = false;
        for (SubItem it : subItems) {
            if (it.type == 2) {
                hasEmbedded = true;
                break;
            }
        }
        if (hasEmbedded) {
            root.addView(dialogButton("内封字幕：用系统解码",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            wantEmbeddedSubViaMp = true;
                            resumePositionMs = Math.max(pos(), introSkipMs);
                            LogBus.mark("P", "user asks embedded subtitle via MP engine");
                            switchEngine(Config.ENGINE_MP);
                            dialog.dismiss();
                        }
                    }));
        }

        root.addView(sectionLabel("音轨"));
        if (audioLabels.isEmpty()) {
            root.addView(sectionLabel("无音轨信息"));
        } else {
            for (int i = 0; i < audioLabels.size(); i++) {
                final int idx = i;
                root.addView(dialogButton(audioLabels.get(i), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectAudioTrack(audioIndices.get(idx), audioLabels.get(idx));
                        dialog.dismiss();
                        showAvDialog();
                    }
                }));
            }
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(root);
        dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("字幕 / 音轨")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
    }

    private android.app.AlertDialog dialog;

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(0xFF66D9FF);
        tv.setPadding(0, (int) (getResources().getDisplayMetrics().density * 8), 0,
                (int) (getResources().getDisplayMetrics().density * 4));
        return tv;
    }

    /** 工具行里的小按钮（wrap_content） */
    private Button dialogButtonSmall(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(8, 2, 8, 2);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (getResources().getDisplayMetrics().density * 6);
        b.setLayoutParams(lp);
        return b;
    }

    private Button dialogButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (getResources().getDisplayMetrics().density * 4);
        b.setLayoutParams(lp);
        return b;
    }

    private void applySubtitleStyle() {
        if (subtitleView == null) return;
        subtitleView.setTextSize(subtitleSize);
        Config.setSubtitleSize(this, subtitleSize);
        applySubtitlePosition();
    }

    /** 收集音轨（ijk / 系统 MediaPlayer） */
    private void collectAudioTracks(java.util.List<Integer> indices, java.util.List<String> labels) {
        try {
            if (ijkPlayer != null) {
                tv.danmaku.ijk.media.player.misc.IjkTrackInfo[] infos = ijkPlayer.getTrackInfo();
                if (infos == null) return;
                for (int i = 0; i < infos.length; i++) {
                    if (infos[i] == null) continue;
                    if (infos[i].getTrackType()
                            != tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                    String lang = infos[i].getLanguage();
                    String info = infos[i].getInfoInline();
                    int sel = -1;
                    try {
                        sel = ijkPlayer.getSelectedTrack(
                                tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                    } catch (Throwable ignored) {
                    }
                    labels.add((sel == i ? "● " : "○ ") + "音轨 " + (indices.size() + 1)
                            + (lang != null && !lang.isEmpty() && !"und".equals(lang) ? "  [" + lang + "]" : "")
                            + (info != null && !info.isEmpty() ? "  " + trim(info, 32) : ""));
                    indices.add(i);
                }
            } else if (mpPlayer != null) {
                MediaPlayer.TrackInfo[] infos = mpPlayer.getTrackInfo();
                if (infos == null) return;
                for (int i = 0; i < infos.length; i++) {
                    if (infos[i] == null) continue;
                    if (infos[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                    String lang = infos[i].getLanguage();
                    labels.add("○ 音轨 " + (indices.size() + 1)
                            + (lang != null && !lang.isEmpty() && !"und".equals(lang) ? "  [" + lang + "]" : ""));
                    indices.add(i);
                }
            }
        } catch (Throwable t) {
            LogBus.mark("P", "collectAudioTracks fail: " + t);
        }
    }

    private void selectAudioTrack(int track, String label) {
        try {
            if (ijkPlayer != null) {
                ijkPlayer.selectTrack(track);
            } else if (mpPlayer != null) {
                MediaPlayer.TrackInfo[] infos = mpPlayer.getTrackInfo();
                if (infos != null) {
                    for (int i = 0; i < infos.length; i++) {
                        if (infos[i] != null && i != track
                                && infos[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                            try {
                                mpPlayer.deselectTrack(i);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                mpPlayer.selectTrack(track);
            } else {
                showToast("当前引擎不支持切换音轨");
                return;
            }
            audioTrackIndex = track;
            showToast("已切换音轨：" + label);
            LogBus.mark("P", "select audio track " + track + " " + label);
        } catch (Throwable t) {
            LogBus.mark("P", "selectTrack fail: " + t);
            showToast("切换失败：" + t);
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private void renderSubtitle() {
        if (subtitleView == null) return;
        try {
            if (!subtitleOn || !prepared) {
                subtitleView.setVisibility(View.GONE);
                return;
            }
            if (cues == null || cues.isEmpty()) {
                // 内封字幕由 timed-text 回调驱动，这里只负责在没文本时隐藏
                if (embeddedText == null) subtitleView.setVisibility(View.GONE);
                return;
            }
            String text = Subtitle.textAt(cues, pos() - subtitleOffsetMs);
            if (text == null || text.isEmpty()) {
                subtitleView.setVisibility(View.GONE);
            } else {
                subtitleView.setText(text);
                subtitleView.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    private final Runnable subtitleRunnable = new Runnable() {
        @Override
        public void run() {
            if (released.get()) return;
            renderSubtitle();
            mainHandler.postDelayed(this, 300);
        }
    };

    /**
     * 每次起播/换集都 +1；后台线程回来时对不上就丢弃。
     *
     * <p>v1.49：没有这个代次时，SMB/WebDAV 代理慢于用户连按「下一集」的速度，
     * 两个线程回来各调一次 startPlayback，两个解码器同时出声抢 CPU，
     * 先起的那个还被直接覆盖、永远不 release。
     */
    private final java.util.concurrent.atomic.AtomicInteger playToken =
            new java.util.concurrent.atomic.AtomicInteger();

    private void startProxyThread() {
        final int token = playToken.incrementAndGet();
        // 本地（U 盘 / SD 卡）文件：内核直接读文件，不用起代理
        if (mode == 2) {
            // 用 file:// URI 包一层，路径里的空格/中文由 Uri 负责转义，
            // ijkplayer(ffmpeg) 和系统 MediaPlayer 都能直接吃
            final String localUrl;
            try {
                localUrl = Uri.fromFile(new java.io.File(mediaUrl)).toString();
            } catch (Throwable t) {
                LogBus.mark("P", "local url build fail: " + t);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != playToken.get()) return;
                        if (!isFinishing()) showError("播放失败：路径无效 " + mediaUrl);
                    }
                });
                return;
            }
            LogBus.mark("P", "local playback, no proxy: " + mediaUrl + " -> " + localUrl);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (token != playToken.get()) return;
                    if (released.get() || isFinishing()) return;
                    if (videoSurfaceReady()) {
                        startPlayback(localUrl);
                    } else {
                        pendingUrl = localUrl;
                    }
                }
            });
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    final IStreamProxy p;
                    if (mode == 0) {
                        p = new SmbStreamProxy(mediaUrl, user, pass);
                    } else {
                        p = new WebDavStreamProxy(mediaUrl, user, pass);
                    }
                    proxy = p;
                    final String playUrl = p.start();
                    LogBus.mark("P", "proxy ready in " + (System.currentTimeMillis() - t0) + "ms -> " + playUrl);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != playToken.get()) return;
                            if (released.get() || isFinishing()) return;
                            if (videoSurfaceReady()) {
                                startPlayback(playUrl);
                            } else {
                                pendingUrl = playUrl;
                                LogBus.mark("P", "surface not ready, wait");
                            }
                        }
                    });
                } catch (final Throwable e) {
                    LogBus.mark("P", "proxy start failed: " + e);
                    // 只有自己还是「当前那一次」才动 proxy，别把别人刚建好的停掉
                    IStreamProxy p = null;
                    if (token == playToken.get()) {
                        p = proxy;
                        proxy = null;
                    }
                    if (p != null) {
                        try {
                            p.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            showError("播放失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "proxy-start").start();
    }

    /** 这一帧需不需要 TextureView：只有旋转/缩放/平移这种 View 变换才需要 */
    private boolean shouldUseTexture() {
        int mode = Config.getRenderMode(this);
        if (mode == Config.RENDER_TEXTURE) return true;
        if (mode == Config.RENDER_SURFACE) return false;
        // 自动：有旋转或放大才上 TextureView，其余时间交给 SurfaceView（硬件叠加，最省）
        return rotationDeg != 0 || zoom > 1.001f || Math.abs(panX) > 0.5f || Math.abs(panY) > 0.5f;
    }

    /**
     * 运行中切换渲染层（不换引擎、不重开播放器，只把解码输出接到另一块 Surface）。
     * 只会在用户第一次旋转/缩放、以及转回 0° 时各发生一次。
     */
    private void switchRenderLayer(boolean toTexture) {
        if (useTexture == toTexture) return;
        useTexture = toTexture;
        applyLayerVisibility();
        LogBus.mark("P", "renderer -> " + (toTexture ? "TextureView" : "SurfaceView")
                + " (engine kept: " + engineName() + ")");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                attachPlayerToLayer();
            }
        });
    }

    /** 把当前播放器的输出接到当前图层上（ijk / MediaPlayer 都支持中途换 Surface） */
    private void attachPlayerToLayer() {
        try {
            if (useTexture) {
                if (textureSurface == null || !textureSurface.isValid()) {
                    pendingSurfaceAttach = true;
                    return;
                }
                if (ijkPlayer != null) ijkPlayer.setSurface(textureSurface);
                else if (mpPlayer != null) mpPlayer.setSurface(textureSurface);
            } else {
                Surface s = surfaceView == null ? null : surfaceView.getHolder().getSurface();
                if (s == null || !s.isValid()) return;
                if (ijkPlayer != null) ijkPlayer.setSurface(s);
                else if (mpPlayer != null) mpPlayer.setSurface(s);
            }
        } catch (Throwable t) {
            LogBus.mark("P", "attachPlayerToLayer fail: " + t);
        }
    }

    /** 决定这一局用哪块视频图层：TextureView 或 SurfaceView */
    private void applyLayerVisibility() {
        boolean vv = engine == Config.ENGINE_VIDEOVIEW;
        if (textureView != null) textureView.setVisibility(!vv && useTexture ? View.VISIBLE : View.GONE);
        if (surfaceView != null) surfaceView.setVisibility(!vv && !useTexture ? View.VISIBLE : View.GONE);
        if (videoView != null) videoView.setVisibility(vv ? View.VISIBLE : View.GONE);
    }

    /** 视频输出是否已经就绪 */
    private boolean videoSurfaceReady() {
        if (engine == Config.ENGINE_VIDEOVIEW) return true;   // VideoView 自己管 Surface
        if (!useTexture) {
            return surfaceView != null && surfaceView.getHolder().getSurface() != null
                    && surfaceView.getHolder().getSurface().isValid();
        }
        return textureSurface != null && textureSurface.isValid();
    }

    private void startPlayback(String playUrl) {
        if (released.get() || isFinishing()) return;
        // 换片 / 换引擎时把双指缩放归位，别把上一部片的状态带过来
        resetZoom();
        engineAttempts++;
        prepared = false;
        firstFrame = false;
        engineErrorHandled = false;
        LogBus.mark("P", "startPlayback engine=" + engineName() + " attempt=" + engineAttempts
                + " surfaceReady=" + surfaceReady
                + (resumePositionMs > 0 ? (" from=" + resumePositionMs + "ms") : ""));
        try {
            if (engine == Config.ENGINE_IJK_HW || engine == Config.ENGINE_IJK_SW
                    || engine == Config.ENGINE_IJK_RV32) {
                startIjk(playUrl);
            } else if (engine == Config.ENGINE_VIDEOVIEW) {
                startVideoView(playUrl);
            } else {
                startMediaPlayer(playUrl);
            }
            startWatchdog();
        } catch (Throwable e) {
            LogBus.mark("P", "startPlayback throwable: " + e);
            onEngineError("启动 " + engineName() + " 失败：" + e);
        }
    }

    private void startWatchdog() {
        watchdogAttempt = engineAttempts;
        mainHandler.removeCallbacks(watchdogRunnable);
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MS);
    }

    /** 各引擎 prepared 后统一处理：恢复位置、应用倍速、隐藏标题 */
    private void onEnginePrepared() {
        prepared = true;
        logCodecInfo();
        durationMs = dur();
        titleView.setVisibility(View.GONE);
        mainHandler.removeCallbacks(watchdogRunnable);
        if (resumePositionMs > 0) {
            long target = resumePositionMs;
            resumePositionMs = 0;
            LogBus.mark("P", "resume at " + target + "ms");
            doSeek(target);
        } else {
            long saved = Config.isResumeEnabled(this) ? Config.getResumeMs(this, mediaUrl) : 0;
            if (saved > 5000) {
                LogBus.mark("P", "resume from last position " + saved + "ms");
                doSeek(saved);
            } else if (introSkipMs > 0) {
                LogBus.mark("P", "skip intro -> " + introSkipMs + "ms");
                doSeek(introSkipMs);
            }
        }
        mainHandler.removeCallbacks(outroWatcher);
        mainHandler.postDelayed(outroWatcher, 1500);
        startPerfWatch();
        applyVideoLayout();
        if (wantEmbeddedSubViaMp) {
            wantEmbeddedSubViaMp = false;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    buildSubItems();
                    for (int i = 0; i < subItems.size(); i++) {
                        if (subItems.get(i).type == 2) {
                            LogBus.mark("P", "auto select embedded subtitle on MP engine: " + subItems.get(i).label);
                            applySubtitle(i);
                            showToast("已切到系统解码显示内封字幕");
                            return;
                        }
                    }
                    showToast("系统解码引擎也没读到内封字幕轨");
                }
            }, 600);
        } else {
            autoPickSubtitle();
        }
        mainHandler.removeCallbacks(subtitleRunnable);
        mainHandler.post(subtitleRunnable);
        if (ijkPlayer != null && SPEEDS[speedIndex] != 1f) {
            try {
                ijkPlayer.setSpeed(SPEEDS[speedIndex]);
            } catch (Throwable t) {
                LogBus.mark("P", "restore speed fail: " + t);
            }
        }
    }

    private void onEngineError(String reason) {
        mainHandler.removeCallbacks(watchdogRunnable);
        if (engineErrorHandled) {
            // 同一个引擎的多次回调（MediaPlayer 会连报多次）只处理第一次
            LogBus.mark("P", "engine error (dup ignored) " + reason);
            return;
        }
        engineErrorHandled = true;
        triedEngines.add(engine);
        LogBus.mark("P", "engine error (" + engineName() + "): " + reason);

        int next = -1;
        for (int i = 1; i <= Config.ENGINE_COUNT; i++) {
            int cand = (engine + i) % Config.ENGINE_COUNT;
            if (!triedEngines.contains(cand)) {
                next = cand;
                break;
            }
        }
        if (next >= 0 && !released.get() && !isFinishing()) {
            LogBus.mark("P", "fallback " + engine + " -> " + next);
            Config.setEngine(this, next);
            showToast("引擎「" + engineName() + "」不可用，自动换用「"
                    + Config.ENGINE_NAMES[next] + "」…");
            if (resumePositionMs <= 0) resumePositionMs = pos();
            switchEngine(next);
        } else {
            showError("无法播放此视频：" + reason + "\n（引擎都试过了，按 MENU→日志 看细节）");
        }
    }

    private void switchEngine(int next) {
        try {
            stopPlayers();
        } catch (Throwable ignored) {
        }
        engine = next;
        // VideoView 内部是 SurfaceView，老系统上 View 变换对它无效：
        // 切过来就把旋转/缩放归零，免得界面写着 90° 而画面不动
        if (isVideoViewEngine() && (rotationDeg != 0 || zoom > 1.001f)) {
            rotationDeg = 0;
            resetZoom();
            LogBus.mark("P", "reset rotation/zoom for VideoView");
        }
        String url = currentPlayUrl != null ? currentPlayUrl : pendingUrl;
        if (url == null) {
            showError("换引擎失败：播放地址为空");
            return;
        }
        useTexture = shouldUseTexture();
        applyLayerVisibility();
        if (videoSurfaceReady()) {
            startPlayback(url);
        } else {
            pendingUrl = url;
            LogBus.mark("P", "wait surface for engine " + engineName());
        }
        if (controllerVisible) refreshPlaybackInfo();
    }

    private String currentPlayUrl;

    private void startIjk(String playUrl) {
        currentPlayUrl = playUrl;
        IjkMediaPlayer mp = new IjkMediaPlayer();

        boolean hw = (engine == Config.ENGINE_IJK_HW);
        // 硬解：交给电视芯片（1080p 用软解必然卡），失败时 ijkplayer 会回退软解
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", hw ? 1L : 0L);
        // 现在片子大多是 H.265：硬解时显式允许 HEVC 走 MediaCodec
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", hw ? 1L : 0L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L);
        // 网络缓冲：本地代理很快，主要靠 ijk 自己的 packet 缓冲抗抖动
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024L * 1024L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", 1024L * 1024L * 4L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 1024L * 1024L * 8L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L);
        // 变速需要 soundtouch 做变速不变调
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L);
        // 让内核解码内封字幕轨（不设这个，selectTrack 后也拿不到 timed text）
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", subtitleStreamOption >= 0 ? 1L : 1L);
        if (engine == Config.ENGINE_IJK_RV32) {
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format",
                    IjkMediaPlayer.SDL_FCC_RV32);
            LogBus.mark("P", "ijk overlay-format = RV32(RGBA)");
        }
        LogBus.mark("P", "ijk options: mediacodec=" + (hw ? 1 : 0));

        if (useTexture) {
            if (textureSurface == null || !textureSurface.isValid()) {
                LogBus.mark("P", "ijk wait texture surface");
                pendingUrl = playUrl;
                pendingSurfaceAttach = true;
                return;
            }
            mp.setSurface(textureSurface);
            LogBus.mark("P", "ijk use texture surface");
        } else {
            mp.setDisplay(holder);
        }

        mp.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer player) {
                String dec = "";
                try {
                    if (player instanceof IjkMediaPlayer) {
                        int d = ((IjkMediaPlayer) player).getVideoDecoder();
                        dec = " decoder=" + (d == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC
                                ? "mediacodec(硬解)" : (d == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC
                                ? "avcodec(软解)" : String.valueOf(d)));
                    }
                } catch (Throwable ignored) {
                }
                if (dec.contains("mediacodec")) actualDecoder = "硬解";
                else if (dec.contains("avcodec")) actualDecoder = "软解";
                // 硬解被拒（回退软解）且分辨率较高时，改走系统解码引擎（厂商硬解），
                // h265/1080p 软解必然卡顿+音画不同步，这一步是根治手段
                if (engine == Config.ENGINE_IJK_HW && "软解".equals(actualDecoder)
                        && autoSwitchCount < MAX_AUTO_SWITCH_BEST
                        && (player.getVideoWidth() >= 1280 || player.getVideoHeight() >= 720)) {
                    autoSwitchCount++;
                    LogBus.mark("P", "hardware decode unavailable at "
                            + player.getVideoWidth() + "x" + player.getVideoHeight()
                            + " -> switch to system decoder engine");
                    resumePositionMs = Math.max(pos(), introSkipMs);
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (released.get() || isFinishing()) return;
                            showToast("硬解不可用，已切换系统解码引擎");
                            switchEngine(Config.ENGINE_MP);
                        }
                    }, 1200);
                }
                LogBus.mark("P", "ijk prepared " + player.getVideoWidth() + "x" + player.getVideoHeight()
                        + " dur=" + player.getDuration() + dec);
                onEnginePrepared();
                titleView.setVisibility(View.VISIBLE);
                titleView.setText("正在播放…");
                try {
                    player.start();
                } catch (Throwable t) {
                    LogBus.mark("P", "ijk start fail: " + t);
                    onEngineError("start 失败 " + t);
                }
            }
        });
        mp.setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
            @Override
            public void onTimedText(IMediaPlayer player, tv.danmaku.ijk.media.player.IjkTimedText text) {
                onEmbeddedText(text == null ? null : text.getText());
            }
        });
        mp.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer player, int what, int extra) {
                LogBus.mark("P", "ijk onError what=" + what + " extra=" + extra);
                onEngineError("ijk what=" + what + " extra=" + extra);
                return true;
            }
        });
        mp.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer player) {
                LogBus.mark("P", "ijk completion");
                onPlaybackDone();
            }
        });
        mp.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer player, int what, int extra) {
                if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    if (!controllerVisible) {
                        titleView.setText("缓冲中…");
                        titleView.setVisibility(View.VISIBLE);
                    }
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    if (!controllerVisible) titleView.setVisibility(View.GONE);
                } else if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrame = true;
                    titleView.setVisibility(View.GONE);
                    LogBus.mark("P", "first frame");
                }
                return false;
            }
        });
        mp.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(IMediaPlayer player, int width, int height,
                                           int sarNum, int sarDen) {
                videoW = width;
                videoH = height;
                LogBus.mark("P", "ijk videoSize " + width + "x" + height);
                applyVideoLayout();
            }
        });

        ijkPlayer = mp;
        try {
            mp.setDataSource(playUrl);
            mp.prepareAsync();
            LogBus.mark("P", "ijk prepareAsync dispatched");
        } catch (Throwable t) {
            LogBus.mark("P", "ijk setDataSource fail: " + t);
            onEngineError("ijk " + t);
        }
    }

    private void startMediaPlayer(String playUrl) {
        currentPlayUrl = playUrl;
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        if (useTexture) {
            if (textureSurface == null || !textureSurface.isValid()) {
                LogBus.mark("P", "mp wait texture surface");
                pendingUrl = playUrl;
                pendingSurfaceAttach = true;
                return;
            }
            mp.setSurface(textureSurface);
            LogBus.mark("P", "mp use texture surface");
        } else {
            mp.setDisplay(holder);
        }
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer player) {
                videoW = player.getVideoWidth();
                videoH = player.getVideoHeight();
                LogBus.mark("P", "mp prepared " + player.getVideoWidth() + "x" + player.getVideoHeight()
                        + " dur=" + player.getDuration());
                onEnginePrepared();
                player.start();
            }
        });
        mp.setOnTimedTextListener(new MediaPlayer.OnTimedTextListener() {
            @Override
            public void onTimedText(MediaPlayer player, android.media.TimedText text) {
                onEmbeddedText(text == null ? null : text.getText());
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer player, int what, int extra) {
                LogBus.mark("P", "mp onError what=" + what + " extra=" + extra);
                onEngineError("MediaPlayer what=" + what + " extra=" + extra);
                return true;
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer player) {
                LogBus.mark("P", "mp completion");
                onPlaybackDone();
            }
        });
        mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer player, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrame = true;
                }
                return false;
            }
        });
        mpPlayer = mp;
        try {
            mp.setDataSource(playUrl);
            mp.prepareAsync();
            LogBus.mark("P", "mp prepareAsync dispatched");
        } catch (Throwable t) {
            LogBus.mark("P", "mp setDataSource fail: " + t);
            onEngineError("MediaPlayer " + t);
        }
    }

    /**
     * 系统解码 + VideoView：老电视上最省的一条路（控件自己管 Surface，播放器不问渲染）。
     * 代价是不能旋转/缩放 —— 这两个功能需要 View 变换，靠 TextureView 才做得到。
     */
    private void startVideoView(String playUrl) {
        currentPlayUrl = playUrl;
        try {
            videoView.setVideoURI(Uri.parse(playUrl));
            videoView.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        firstFrame = true;
                        titleView.setVisibility(View.GONE);
                        LogBus.mark("P", "VideoView first frame");
                    }
                    return false;
                }
            });
            LogBus.mark("P", "videoView setVideoURI ok");
        } catch (Throwable t) {
            LogBus.mark("P", "videoView fail: " + t);
            onEngineError("VideoView " + t);
        }
    }

    /**
     * 释放所有播放内核。
     *
     * <p>v1.49：stop / reset / release 各自 try。
     * 以前三步串在一个 try 里——prepareAsync 还没 prepared 的时候调度器会抛
     * IllegalStateException，后面的 reset()/release() 就被跳过，字段却仍然置 null：
     * 旧实例永久泄漏、还绑着 Surface 和回调，它的 onPrepared 过一会儿又把声音放出来。
     */
    private void stopPlayers() {
        stopPerfWatch();
        prepared = false;
        IjkMediaPlayer ijk = ijkPlayer;
        android.media.MediaPlayer mp = mpPlayer;
        ijkPlayer = null;
        mpPlayer = null;
        if (ijk != null) {
            try {
                ijk.setSurface(null);
            } catch (Throwable ignored) {
            }
            try {
                ijk.stop();
            } catch (Throwable ignored) {
            }
            try {
                ijk.reset();
            } catch (Throwable ignored) {
            }
            try {
                ijk.release();
            } catch (Throwable ignored) {
            }
        }
        if (mp != null) {
            try {
                mp.setDisplay(null);
            } catch (Throwable ignored) {
            }
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            try {
                mp.reset();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
        try {
            if (videoView != null) videoView.stopPlayback();
        } catch (Throwable ignored) {
        }
    }

    private void onPlaybackDone() {
        if (autoNext && playlistUrls != null && playlistIndex >= 0
                && playlistIndex + 1 < playlistUrls.length) {
            LogBus.mark("P", "auto next episode " + (playlistIndex + 1));
            playIndex(playlistIndex + 1);
            return;
        }
        showToast("播放结束");
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 800);
    }

    /** 切到播放列表里的某一集（保持当前引擎） */
    private void playIndex(int idx) {
        if (playlistUrls == null || idx < 0 || idx >= playlistUrls.length) {
            showToast(idx < 0 ? "已经是第一集" : "已经是最后一集");
            return;
        }
        if (idx == playlistIndex) return;
        saveResumePoint();
        LogBus.mark("P", "switch episode " + playlistIndex + " -> " + idx + " " + playlistUrls[idx]);
        playlistIndex = idx;
        mediaUrl = playlistUrls[idx];
        displayName = playlistNames != null && idx < playlistNames.length ? playlistNames[idx] : mediaUrl;
        try {
            if (titleView != null) {
                titleView.setText(displayName);
                titleView.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
        resumePositionMs = 0;
        cues = null;
        subtitleLoading = false;
        subtitleName = "";
        // v1.49：这几项以前不清，导致第二集起不再自动挂字幕、旧 seek 目标打到新一集、
        // 上一集的引擎失败记账（triedEngines）把这一集可用的引擎也跳掉
        curSubIndex = -1;
        subItems.clear();
        loadedSubFile = "";
        embeddedText = null;
        seekTargetMs = -1;
        seekTargetAt = 0;
        pendingSeekMs = -1;
        seekBarBasePos = -1;
        userSeeking = false;
        mainHandler.removeCallbacks(applySeekTargetRunnable);
        mainHandler.removeCallbacks(applySeekRunnable);
        mainHandler.removeCallbacks(clearUserSeekingRunnable);
        triedEngines.clear();
        engineErrorHandled = false;
        autoSwitchCount = 0;
        perfDowngraded = false;
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);
        currentPlayUrl = null;
        pendingUrl = null;
        try {
            if (proxy != null) {
                proxy.stop();
                proxy = null;
            }
        } catch (Throwable ignored) {
        }
        stopPlayers();
        mainHandler.removeCallbacks(watchdogRunnable);
        mainHandler.removeCallbacks(outroWatcher);
        startProxyThread();
    }

    /** 记住当前进度（下次打开这个文件直接从这儿继续） */
    /* ==================== 性能看门狗（v1.45） ==================== */

    /**
     * 每 2.2 秒采一次样：比较「播放位置推进」和「墙上时间」。
     * 画面跟不上时（解码不够 / 渲染太贵），位置推进会明显慢于真实时间——
     * 连续 3 次慢于 75% 就判定这台电视吃不住当前引擎，自动切到最省的
     * 「系统解码 + VideoView」，并记住位置继续播，不再让人手动试引擎。
     */
    private void startPerfWatch() {
        perfLastPos = -1;
        perfLastAt = 0;
        perfBadCount = 0;
        mainHandler.removeCallbacks(perfRunnable);
        mainHandler.postDelayed(perfRunnable, PERF_INTERVAL_MS);
    }

    private void stopPerfWatch() {
        mainHandler.removeCallbacks(perfRunnable);
    }

    private final Runnable perfRunnable = new Runnable() {
        @Override
        public void run() {
            if (released.get() || isFinishing()) return;
            try {
                if (!isPlaying() || userSeeking || pendingSeekMs > 0 || !prepared) {
                    perfLastPos = -1;
                    perfLastAt = 0;
                    perfBadCount = 0;
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    long p = pos();
                    if (perfLastPos >= 0 && perfLastAt > 0) {
                        long wall = now - perfLastAt;
                        long adv = p - perfLastPos;
                        if (wall > 500 && adv >= 0) {
                            perfRatio = (float) adv / wall;
                            if (perfRatio < 0.75f) {
                                perfBadCount++;
                            } else {
                                perfBadCount = 0;
                            }
                            LogBus.mark("P", "perf ratio=" + String.format(java.util.Locale.US, "%.2f", perfRatio)
                                    + " adv=" + adv + "ms/" + wall + "ms bad=" + perfBadCount);
                            if (perfBadCount >= 3 && !perfDowngraded
                                    && engine != Config.ENGINE_VIDEOVIEW) {
                                perfDowngraded = true;
                                long keep = Math.max(p, introSkipMs);
                                LogBus.mark("P", "perf watchdog: video can't keep up -> switch to VideoView at " + keep);
                                resumePositionMs = keep;
                                showHud("画面跟不上，已自动切到更省性能的播放方式");
                                switchEngine(Config.ENGINE_VIDEOVIEW);
                                return;
                            }
                        }
                    }
                    perfLastPos = p;
                    perfLastAt = now;
                }
            } catch (Throwable ignored) {
            }
            mainHandler.postDelayed(this, PERF_INTERVAL_MS);
        }
    };

    /* ==================== 信息面板（v1.45） ==================== */

    private void toggleInfoPanel() {
        if (infoPanel == null) return;
        if (infoPanel.getVisibility() == View.VISIBLE) {
            hideInfoPanel();
        } else {
            showInfoPanel();
        }
    }

    private void showInfoPanel() {
        if (infoPanel == null || infoText == null) return;
        infoText.setText(buildInfoText());
        infoPanel.setVisibility(View.VISIBLE);
        LogBus.mark("P", "info panel shown");
    }

    private void hideInfoPanel() {
        if (infoPanel != null) infoPanel.setVisibility(View.GONE);
    }

    /** 信息面板内容：编码 / 码率 / 引擎 / 渲染层 / 实测推进比 / 缓冲 */
    private String buildInfoText() {
        StringBuilder sb = new StringBuilder();
        String res = (videoW > 0 && videoH > 0) ? (videoW + "×" + videoH) : "未知";
        String mime = currentVideoMime();
        if (mime == null || mime.isEmpty()) mime = sniffMimeFromFile();
        String codec = "未知";
        if (mime != null && !mime.isEmpty()) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("hevc") || m.contains("265") || m.contains("hvc1")) codec = "H.265/HEVC";
            else if (m.contains("avc") || m.contains("264")) codec = "H.264";
            else if (m.contains("mpeg4")) codec = "MPEG-4";
            else if (m.contains("vc1")) codec = "VC-1";
            else if (m.contains("vp9")) codec = "VP9";
            else codec = mime;
            if (!actualDecoder.isEmpty()) codec += " · " + actualDecoder;
        }
        sb.append("分辨率  ").append(res).append("\n");
        sb.append("编码    ").append(codec).append("\n");
        sb.append("码率    ").append(bitrateText()).append("\n");
        sb.append("引擎    ").append((engine + 1)).append("/").append(Config.ENGINE_COUNT).append("  ")
                .append(engineName()).append("\n");
        String layer = isVideoViewEngine() ? "VideoView（最省）"
                : (useTexture ? "TextureView（支持旋转/缩放）" : "SurfaceView（硬件叠加）");
        sb.append("渲染    ").append(layer).append("\n");
        DisplayMetrics dm2 = getResources().getDisplayMetrics();
        sb.append("画面    ").append(ASPECT_NAMES[aspectMode])
                .append("（本机 ").append(dm2.widthPixels).append("×").append(dm2.heightPixels).append("）\n");
        sb.append("实测    画面每秒推进 ").append(String.format(Locale.US, "%.2f", perfRatio)).append("×")
                .append(perfRatio < 0.95f ? "（跟不上）" : "（正常）").append("\n");
        sb.append("缓冲    ").append(bufferText()).append("\n");
        sb.append("时长    ").append(durationMs > 0 ? formatTime(durationMs) : "--:--");
        return sb.toString();
    }

    /**
     * 系统解码有时拿不到 track mime（老固件返回 null），本机文件就直接嗅探容器，
     * 保证信息面板里「编码」这一行不是「未知」。
     */
    private String sniffMimeFromFile() {
        android.media.MediaExtractor ex = null;
        try {
            String path = null;
            if (mediaUrl != null && mediaUrl.startsWith("/")) path = mediaUrl;
            else if (mediaUrl != null && mediaUrl.startsWith("file://")) path = Uri.parse(mediaUrl).getPath();
            if (path == null) return null;
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return null;
            ex = new android.media.MediaExtractor();
            ex.setDataSource(path);
            int n = ex.getTrackCount();
            for (int i = 0; i < n; i++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(i);
                if (fmt == null) continue;
                String m = fmt.getString("mime");
                if (m != null && m.startsWith("video/")) return m;
            }
        } catch (Throwable t) {
            LogBus.mark("P", "sniff mime fail: " + t);
        } finally {
            try {
                if (ex != null) ex.release();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 码率：本地文件按 文件大小×8/时长 估算；网络流拿不到就显示 - */
    private String bitrateText() {
        try {
            long d = dur();
            if (d <= 0) return "-";
            if (mediaUrl != null && mediaUrl.startsWith("/")) {
                java.io.File f = new java.io.File(mediaUrl);
                if (f.exists() && f.isFile()) {
                    double kbps = f.length() * 8.0 / (d / 1000.0) / 1000.0;
                    return String.format(Locale.US, "%.0f kbps（约 %.1f Mbps）", kbps, kbps / 1000.0);
                }
            }
            if (mediaUrl != null && mediaUrl.startsWith("file://")) {
                java.io.File f = new java.io.File(Uri.parse(mediaUrl).getPath());
                if (f.exists() && f.isFile()) {
                    double kbps = f.length() * 8.0 / (d / 1000.0) / 1000.0;
                    return String.format(Locale.US, "%.0f kbps（约 %.1f Mbps）", kbps, kbps / 1000.0);
                }
            }
        } catch (Throwable ignored) {
        }
        return "-（网络流）";
    }

    /** 缓冲：ijk 有缓存时长接口，别的引擎拿不到就显示 - */
    private String bufferText() {
        try {
            if (ijkPlayer != null) {
                long v = ijkPlayer.getVideoCachedDuration();
                long a = ijkPlayer.getAudioCachedDuration();
                long ms = Math.max(v, a);
                if (ms >= 0) return String.format(Locale.US, "%.1f 秒（画面）/ %.1f 秒（声音）", v / 1000.0, a / 1000.0);
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    private String safeExtra(String key) {
        try {
            String v = getIntent().getStringExtra(key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 记一条「继续观看」，主界面卡片用 */
    private void recordContinueWatching() {
        if (!Config.isContinueEnabled(this)) return;
        try {
            long d = dur();
            long p = pos();
            if (d <= 0 || p <= 0) return;
            Config.ContinueItem it = new Config.ContinueItem();
            it.url = mediaUrl == null ? "" : mediaUrl;
            it.name = displayName == null ? "" : displayName;
            it.mode = mode;
            it.host = safeExtra("host");
            it.share = safeExtra("share");
            it.user = user == null ? "" : user;
            it.pass = pass == null ? "" : pass;
            it.pos = p;
            it.dur = d;
            Config.recordContinue(this, it);
            LogBus.mark("P", "continue watching recorded " + p + "/" + d);
        } catch (Throwable ignored) {
        }
    }

    private void saveResumePoint() {
        if (!Config.isResumeEnabled(this)) return;
        try {
            long d = dur();
            long p = pos();
            if (d <= 0) return;
            if (p > 5000 && p < d - 5000) {
                Config.setResumeMs(this, mediaUrl, p);
                LogBus.mark("P", "save resume point " + p + "ms");
            } else {
                Config.setResumeMs(this, mediaUrl, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 播放界面不再用 Toast 弹窗（会挡住画面、而且每次操作都弹一下很烦），
     * 统一改成画面顶部的轻量 HUD，1 秒后自动消失。
     */
    private void showToast(String msg) {
        showHud(msg);
    }

    private void showError(String msg) {
        try {
            titleView.setText(msg);
            titleView.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    private void showLog() {
        if (logView == null) return;
        logView.setText(LogBus.tail(10));
        logView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 屏幕方向/尺寸变了：清掉 surface 尺寸缓存，等根容器重新量完再算一次画面布局
        lastClampW = lastClampH = lastViewW = lastViewH = 0;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                applyVideoLayout();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogBus.mark("P", "onPause");
        // 退出/切后台时先记一条「继续观看」，进程被杀也不丢
        recordContinueWatching();
        saveResumePoint();
        // v1.49：后台不能留着一堆定时器。以前 perf/片尾/字幕/进度轮询照跑，
        // 片尾 watcher 甚至会在后台把下一集拉起来出声
        stopPerfWatch();
        stopHoldSpeed();
        mainHandler.removeCallbacks(outroWatcher);
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.removeCallbacks(watchdogRunnable);
        mainHandler.removeCallbacks(hideControllerRunnable);
        try {
            if (ijkPlayer != null && ijkPlayer.isPlaying()) ijkPlayer.pause();
            if (mpPlayer != null && mpPlayer.isPlaying()) mpPlayer.pause();
            if (videoView != null && isVideoViewEngine() && videoView.isPlaying()) videoView.pause();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 再存一次：有的电视会直接杀进程，不走 onPause 之后的流程
        saveResumePoint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prepared) {
            startPerfWatch();
            mainHandler.removeCallbacks(tickRunnable);
            mainHandler.post(tickRunnable);
            mainHandler.removeCallbacks(watchdogRunnable);
            mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MS);
            if (autoNext) {
                mainHandler.removeCallbacks(outroWatcher);
                mainHandler.postDelayed(outroWatcher, 1500);
            }
        }
        if (controllerVisible) refreshPlaybackInfo();
    }

    @Override
    public void onBackPressed() {
        if (controllerVisible) {
            hideController();
            return;
        }
        release();
        finish();
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        if (!released.compareAndSet(false, true)) return;
        stopPerfWatch();
        stopHoldSpeed();
        recordContinueWatching();
        mainHandler.removeCallbacks(watchdogRunnable);
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.removeCallbacks(outroWatcher);
        mainHandler.removeCallbacks(hideControllerRunnable);
        // 这几个也会读播放器状态（seek / HUD），界面销毁后必须停掉，否则会打到已 release 的内核上
        mainHandler.removeCallbacks(applySeekTargetRunnable);
        mainHandler.removeCallbacks(applySeekRunnable);
        mainHandler.removeCallbacks(hideHudRunnable);
        mainHandler.removeCallbacks(hideSeekHintRunnable);
        saveResumePoint();
        LogBus.mark("P", "release");
        try {
            if (proxy != null) {
                proxy.stop();
                proxy = null;
            }
        } catch (Throwable ignored) {
        }
        stopPlayers();
        Config.setInPlayback(this, false);
        LogBus.mark("P", "release done");
    }
}
