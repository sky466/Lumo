package com.letvcast.player;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面（v1.26 重做）
 *
 * 布局：左「我的服务器」卡片列 + 中「U 盘」卡片列 + 右操作栏（大号 ＋ / 刷新 U 盘 / 诊断）
 *   · 服务器连接成功一次就自动存成卡片，点一下直连，长按（MENU 键）可编辑/删除
 *   · SMB 与 WebDAV 的地址录入全部收进右侧 「＋」 号弹出的同一个对话框
 *   · U 盘插入后自动出现在中间一列（监听挂载广播 + /proc/mounts 探测），点一下即可浏览/播放
 */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 0x101;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Theme.wrap(base));
    }

    private LinearLayout serverList;
    private LinearLayout usbList;
    private LinearLayout continueRow;
    private View continueSection;
    private TextView continueCount;
    private View serverScroll;
    private View usbScroll;
    private View usbSection;
    private TextView serverEmpty;
    private TextView usbEmpty;
    private TextView serverCount;
    private TextView usbCount;
    private TextView statusText;
    private View addButton;
    private android.widget.ImageView themeButton;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean connecting = false;
    /** 进程内只自动进一次服务器：返回主界面后不会再被弹进去 */
    private static boolean autoConnectConsumed = false;

    /** 连接进度轮询（把「正在连接」换成「正在认证…」这类实时阶段） */
    private Runnable stagePoll;
    private boolean stagePolling = false;
    /** 每次连接一个序号：超时判负之后，晚到的成功回调直接丢弃 */
    private int connectSeq = 0;

    /** 连接超时上限：SMB2 握手本身 30 秒封顶，这里再兜一层，保证绝不会一直转圈 */
    private static final int CONNECT_TIMEOUT_MS = 35000;
    /** 跨进程兜底：距上次自动进入不足这个时间就不再自动进入（防止被回收后重进死循环） */
    private static final long AUTO_CONNECT_COOLDOWN_MS = 20 * 1000L;

    private final BroadcastReceiver mountReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            LogBus.mark("UI", "mount broadcast: " + action + " " + (intent == null ? "" : intent.getDataString()));
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) refreshVolumes();
                }
            }, 1200); // 等系统挂载完成再扫
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverList = (LinearLayout) findViewById(R.id.server_list);
        usbList = (LinearLayout) findViewById(R.id.usb_list);
        continueRow = (LinearLayout) findViewById(R.id.continue_row);
        continueSection = findViewById(R.id.continue_section);
        continueCount = (TextView) findViewById(R.id.continue_count);
        // 手机版布局没有单独的分栏 ScrollView（整块一起滚），所以这两处可能为 null
        serverScroll = findViewById(R.id.server_scroll);
        usbScroll = findViewById(R.id.usb_scroll);
        usbSection = findViewById(R.id.usb_section);
        serverEmpty = (TextView) findViewById(R.id.server_empty);
        usbEmpty = (TextView) findViewById(R.id.usb_empty);
        serverCount = (TextView) findViewById(R.id.server_count);
        usbCount = (TextView) findViewById(R.id.usb_count);
        statusText = (TextView) findViewById(R.id.status_text);
        addButton = findViewById(R.id.btn_add);

        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddServerDialog(-1, null);
            }
        });
        // 齿轮 = 设置（v1.47 起「日志与诊断」只在设置里，主界面不再重复放一个按钮）
        ImageView gear = (ImageView) findViewById(R.id.btn_settings);
        if (gear != null) {
            gear.setImageDrawable(new GearIcon(
                    getResources().getColor(R.color.text_primary),
                    getResources().getColor(R.color.bg)));
            gear.setContentDescription("设置");
            gear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSettingsDialog();
                }
            });
        }
        // 主题按钮：当前是浅色就画太阳，当前是深色就画月亮，点一下切换
        themeButton = (android.widget.ImageView) findViewById(R.id.btn_theme);
        updateThemeIcon();
        themeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Theme.toggle(MainActivity.this);
                // 颜色资源随 uiMode 切换，重建界面即可立刻生效
                recreate();
            }
        });

        migratePrefs();
        refreshServers();
        refreshVolumes();
        refreshContinue();
        // 初始焦点在 onWindowFocusChanged 里给（那时布局已经完成，requestFocus 才有效）

        // 上次播放异常退出 → 直接弹诊断，电视上没法连 adb
        if (App.engineAutoSwitched) {
            setStatus(App.engineSwitchMessage);
            Toast.makeText(this, "上次播放异常退出，已自动切换播放引擎", Toast.LENGTH_LONG).show();
            showSettingsDialog();
        }

        requestStoragePermission();

        // 调试入口（playurl / imgpath）优先，存在时不自动连接
        boolean hasDebugTarget = handleDebugIntent();

        maybeAutoConnect(hasDebugTarget, savedInstanceState);
    }

    private boolean initialFocusDone = false;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (initialFocusDone && getCurrentFocus() != null) return;
        initialFocusDone = true;
        // 布局刚完成时 requestFocus 可能还抢不到，稍后再兜一次
        focusDefault();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && getCurrentFocus() == null) focusDefault();
            }
        }, 350);
    }

    /** 焦点默认落在第一张服务器卡片；没有服务器就落在「＋」号上 */
    private void focusDefault() {
        if (serverList.getChildCount() > 0) {
            serverList.getChildAt(0).requestFocus();
        } else {
            addButton.requestFocus();
        }
    }

    /**
     * 启动自动进入服务器：只在这三种条件同时满足时做一次
     *   1. 用户勾了「自动」
     *   2. 这是本次进程第一次进主界面（从文件浏览返回不会再触发）
     *   3. 不是旋转/重启恢复（savedInstanceState == null），且距上次自动进入超过 20 秒
     */
    private void maybeAutoConnect(boolean hasDebugTarget, Bundle savedInstanceState) {
        if (hasDebugTarget) return;
        if (!Config.isAutoConnect(this)) return;
        if (savedInstanceState != null) return;
        if (autoConnectConsumed) return;

        final List<Config.HistoryItem> history = Config.getHistory(this);
        if (history.isEmpty()) return;

        long last = Config.getLastAutoConnectAt(this);
        long now = System.currentTimeMillis();
        if (last > 0 && now - last < AUTO_CONNECT_COOLDOWN_MS) {
            LogBus.mark("UI", "skip auto-connect (cooldown " + ((now - last) / 1000) + "s)");
            return;
        }

        autoConnectConsumed = true;
        Config.setLastAutoConnectAt(this, now);
        setStatus("正在自动连接 " + history.get(0).host + " …");
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) connectTo(history.get(0), 0);
            }
        }, 700);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 从文件浏览返回主界面：刷新一下卡片（连接成功会把记录挪到最前）
        LogBus.mark("UI", "onNewIntent from browse");
        hideStatus();
        refreshServers();
        refreshVolumes();
        focusDefault();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        try {
            registerReceiver(mountReceiver, f);
        } catch (Throwable ignored) {
        }
        // 插拔 U 盘后可能去别的应用拷贝了文件，回来刷新一下卡片
        refreshVolumes();
        // 从播放器返回：更新「继续观看」的进度
        refreshContinue();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(mountReceiver);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // v1.49：连接中的轮询（stagePoll）每 500ms 自己重新入队，界面销毁后必须掐掉，
        // 否则会一直跑着持有 Activity 和状态栏 TextView；顺带清掉其它挂着的回调
        stopStagePoll();
        if (ui != null) ui.removeCallbacksAndMessages(null);
        if (isFinishing()) {
            LogBus.markEnd();
        }
        super.onDestroy();
    }

    // ==================== 偏好迁移 ====================

    /**
     * v1.26 起主界面改成「服务器卡片 + U 盘」，启动自动连接默认关闭，
     * 否则一进应用就跳走，用户根本看不到新主界面。只迁移一次。
     */
    private void migratePrefs() {
        if (Config.getUiSchema(this) >= Config.UI_SCHEMA) return;
        Config.setAutoConnect(this, false);
        Config.setUiSchema(this, Config.UI_SCHEMA);
        LogBus.mark("UI", "migrated prefs to ui schema " + Config.UI_SCHEMA);
    }

    // ==================== 服务器卡片 ====================

    private void refreshServers() {
        final List<Config.HistoryItem> history = Config.getHistory(this);
        serverList.removeAllViews();
        if (history.isEmpty()) {
            serverEmpty.setVisibility(View.VISIBLE);
            if (serverScroll != null) serverScroll.setVisibility(View.GONE);
            serverCount.setText("0 个");
            return;
        }
        serverEmpty.setVisibility(View.GONE);
        if (serverScroll != null) serverScroll.setVisibility(View.VISIBLE);
        serverCount.setText(history.size() + " 个");
        for (int i = 0; i < history.size(); i++) {
            serverList.addView(buildServerCard(history.get(i), i));
        }
    }

    /** 手机竖屏（窄屏）时卡片要更紧凑，否则地址被截得太狠 */
    private boolean isNarrow() {
        return getResources().getConfiguration().screenWidthDp < 420;
    }

    private View buildServerCard(final Config.HistoryItem h, final int index) {
        final boolean isSmb = h.mode == 0;
        final boolean narrow = isNarrow();
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.card_server);
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);

        // 类型徽标
        TextView badge = new TextView(this);
        badge.setText(isSmb ? "SMB" : "DAV");
        badge.setTextSize(11);
        badge.setTextColor(getResources().getColor(R.color.badge_text));
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setBackgroundResource(isSmb ? R.drawable.badge_smb : R.drawable.badge_webdav);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                dp(narrow ? 44 : 52), dp(narrow ? 36 : 40));
        row.addView(badge, badgeLp);

        // 文本区
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(14);
        row.addView(textCol, colLp);

        final TextView title = new TextView(this);
        String displayName = (h.name == null || h.name.trim().isEmpty())
                ? prettyHost(h.host) : h.name.trim();
        title.setText(displayName);
        title.setTextSize(narrow ? 16 : 18);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitleOf(h));
        sub.setTextSize(12);
        sub.setTextColor(getResources().getColor(R.color.text_muted));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(3), 0, 0);
        textCol.addView(sub);

        // 右侧：当前使用 / 箭头
        TextView tail = new TextView(this);
        boolean isCurrent = h.mode == Config.getMode(this) && eq(h.host, Config.getHost(this))
                && eq(h.share == null ? "" : h.share, Config.getShare(this));
        // 窄屏放不下「当前」两个字，用小圆点表示
        tail.setText(isCurrent ? (narrow ? "●" : "当前") : "›");
        tail.setTextSize(isCurrent ? (narrow ? 10 : 12) : 22);
        tail.setTextColor(isCurrent
                ? getResources().getColor(R.color.accent2)
                : getResources().getColor(R.color.text_tertiary));
        tail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tailLp.leftMargin = dp(10);
        row.addView(tail, tailLp);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setTextColor(hasFocus
                        ? getResources().getColor(R.color.accent_light)
                        : getResources().getColor(R.color.text_primary));
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectTo(h, index);
            }
        });
        // 长按（触摸按住 / 遥控器按住 OK）= 管理这条服务器；遥控器的「菜单」键同样打开
        Ui.bindLong(row, new Runnable() {
            @Override
            public void run() {
                showServerActions(h, index);
            }
        }, new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                    showServerActions(h, index);
                    return true;
                }
                return false;
            }
        });
        return row;
    }

    private static boolean eq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static String prettyHost(String host) {
        if (host == null) return "";
        String h = host.trim();
        if (h.startsWith("http://")) h = h.substring(7);
        else if (h.startsWith("https://")) h = h.substring(8);
        if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private String subtitleOf(Config.HistoryItem h) {
        StringBuilder sb = new StringBuilder();
        // 起了名字就把地址挪到副标题里，一眼能看出是哪台机器
        if (h.name != null && !h.name.trim().isEmpty()) {
            sb.append(prettyHost(h.host)).append(" · ");
        }
        if (h.mode == 0) {
            sb.append("共享 ").append(h.share == null || h.share.isEmpty() ? "-" : h.share);
        } else {
            sb.append("WebDAV");
        }
        sb.append(" · ");
        sb.append(h.user == null || h.user.isEmpty() ? "匿名访问" : h.user);
        return sb.toString();
    }

    /** 长按（或遥控器 MENU 键）弹出的管理菜单 */
    private void showServerActions(final Config.HistoryItem h, final int index) {
        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, prettyHost(h.host)));
        root.addView(Ui.subtitle(this, (h.mode == 0 ? "SMB" : "WebDAV") + " · " + subtitleOf(h)));
        Ui.gap(this, root, 16);

        final Dialog dialog = Ui.dialog(this, root, 0.36);

        final TextView open = Ui.action(this, "打开", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                connectTo(h, index);
            }
        });
        root.addView(open);
        Ui.gap(this, root, 10);

        TextView edit = Ui.action(this, "编辑地址 / 账号", false);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showAddServerDialog(index, h);
            }
        });
        root.addView(edit);
        Ui.gap(this, root, 10);

        TextView delete = Ui.action(this, "删除这条记录", true);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                confirmDelete(h, index);
            }
        });
        root.addView(delete);
        Ui.gap(this, root, 16);

        TextView cancel = Ui.ghost(this, "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(cancel);

        dialog.show();
        Ui.relayout(this, dialog, 0.36);
        open.requestFocus();
    }

    private void confirmDelete(final Config.HistoryItem h, final int index) {
        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "删除服务器"));
        Ui.gap(this, root, 8);
        root.addView(Ui.body(this, "确定删除 " + prettyHost(h.host)
                + (h.share != null && !h.share.isEmpty() ? " / " + h.share : "") + " ？"));

        final Dialog dialog = Ui.dialog(this, root, 0.4);

        Ui.gap(this, root, 20);
        TextView del = Ui.primary(this, "删除");
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                Config.removeHistory(MainActivity.this, index);
                refreshServers();
                focusFirst();
                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
            }
        });
        TextView cancel = Ui.ghost(this, "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(Ui.row(this, cancel, del, 12));

        dialog.show();
        Ui.relayout(this, dialog, 0.4);
        cancel.requestFocus();
    }

    private void focusFirst() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (serverList.getChildCount() > 0) serverList.getChildAt(0).requestFocus();
                else addButton.requestFocus();
            }
        });
    }

    /** 连接并进入文件浏览 */
    private void connectTo(final Config.HistoryItem h, final int index) {
        if (connecting) return;
        connecting = true;
        final int connectMode = h.mode;
        final String host = normalizeHost(connectMode, h.host == null ? "" : h.host.trim());
        final String share = h.share == null ? "" : h.share.trim();
        final String user = h.user == null ? "" : h.user.trim();
        final String pass = h.pass == null ? "" : h.pass;

        if (host.isEmpty()) {
            connecting = false;
            Toast.makeText(this, "这条记录没有地址，请长按编辑", Toast.LENGTH_LONG).show();
            return;
        }
        final String pretty = prettyHost(host);
        setStatus("正在连接 " + pretty + " …");
        final int seq = ++connectSeq;
        startStagePoll(statusText, "正在连接 " + pretty);
        // 兜底：后端已经把每次读超时压到 8~10 秒，这里再加一层，绝不出现「一直正在连接」
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seq != connectSeq || !connecting) return;
                connecting = false;
                stopStagePoll();
                String stage = Smb2Client.stageText();
                setStatus("连接超时：卡在「" + (stage == null ? "未知" : stage)
                        + "」，请检查地址/端口/共享名与账号");
                Toast.makeText(MainActivity.this,
                        "连接超时，已中止", Toast.LENGTH_LONG).show();
            }
        }, CONNECT_TIMEOUT_MS);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (connectMode == 0) {
                        SmbClient c = new SmbClient(host, share, user, pass);
                        try {
                            c.testConnection();
                            LogBus.mark("SMB", "connected " + host + "/" + share + " via " + c.info());
                        } finally {
                            c.close();
                        }
                    } else {
                        new WebDavClient(host, user, pass).testConnection();
                    }
                } catch (Throwable e) {
                    err = e.getMessage();
                    if (err == null || err.isEmpty()) err = "连接失败（" + e.getClass().getSimpleName() + "）";
                }
                final String finalErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != connectSeq) return;   // 已经判超时了，晚到的结果丢掉
                        connecting = false;
                        stopStagePoll();
                        if (isFinishing()) return;
                        if (finalErr == null) {
                            Config.saveCurrent(MainActivity.this, h.name, connectMode, host, share, user, pass);
                            Config.addHistory(MainActivity.this, h.name, connectMode, host, share, user, pass);
                            hideStatus();
                            refreshServers();
                            openBrowse(connectMode, host, share, user, pass);
                        } else {
                            setStatus("连接失败：" + finalErr);
                            Toast.makeText(MainActivity.this, "连接失败：" + finalErr, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "connect-test").start();
    }

    private void openBrowse(int mode, String host, String share, String user, String pass) {
        Intent intent = new Intent(this, BrowseActivity.class);
        intent.putExtra("mode", mode);
        intent.putExtra("host", host);
        intent.putExtra("share", share);
        intent.putExtra("user", user);
        intent.putExtra("pass", pass);
        intent.putExtra("path", "");
        startActivity(intent);
    }

    private static String normalizeHost(int mode, String host) {
        if (mode == 1 && !host.isEmpty()
                && !host.startsWith("http://") && !host.startsWith("https://")) {
            return "http://" + host;
        }
        return host;
    }

    // ==================== U 盘卡片 ====================

    /**
     * 刷新 U 盘列表：整块「U 盘 / 移动存储」只有在真的插着 U 盘时才出现，
     * 没插的时候主界面就只剩服务器列表，界面更干净。
     */
    private int refreshVolumes() {
        final List<UsbStorage.Volume> vols = UsbStorage.list(this);
        usbList.removeAllViews();
        usbCount.setText(vols.size() + " 个");

        if (vols.isEmpty()) {
            if (usbSection != null) usbSection.setVisibility(View.GONE);
            if (usbEmpty != null) usbEmpty.setVisibility(View.GONE);
            if (usbScroll != null) usbScroll.setVisibility(View.GONE);
            LogBus.mark("USB", "no removable volume");
            return 0;
        }
        if (usbSection != null) usbSection.setVisibility(View.VISIBLE);
        if (usbEmpty != null) usbEmpty.setVisibility(View.GONE);
        if (usbScroll != null) usbScroll.setVisibility(View.VISIBLE);
        for (UsbStorage.Volume v : vols) {
            usbList.addView(buildUsbCard(v));
        }
        // 记进日志：电视上没 adb，用户报「U 盘不显示」时翻诊断就能看到扫到了哪些挂载点
        StringBuilder sb = new StringBuilder("volumes=");
        for (UsbStorage.Volume v : vols) {
            sb.append(v.path).append("(").append(v.label).append(",")
                    .append(v.fs == null || v.fs.isEmpty() ? "?" : v.fs).append(",")
                    .append(v.total).append("B) ");
        }
        LogBus.mark("USB", sb.toString());
        return vols.size();
    }

    // ==================== 继续观看（v1.45） ====================

    /**
     * 最近播放过的几条：卡片显示「文件名 + 看到 xx:xx / xx:xx」和一条进度线，
     * 点一下直接续播（电视上焦点可选中），长按移除。
     */
    private void refreshContinue() {
        if (continueRow == null) return;
        continueRow.removeAllViews();
        java.util.List<Config.ContinueItem> items = Config.isContinueEnabled(this)
                ? Config.getContinue(this, 6) : new java.util.ArrayList<Config.ContinueItem>();
        if (items.isEmpty()) {
            if (continueSection != null) continueSection.setVisibility(View.GONE);
            return;
        }
        if (continueSection != null) continueSection.setVisibility(View.VISIBLE);
        if (continueCount != null) continueCount.setText(items.size() + " 个");
        for (int i = 0; i < items.size(); i++) {
            Config.ContinueItem it = items.get(i);
            continueRow.addView(buildContinueCard(it, i == 0));
        }
        LogBus.mark("UI", "continue watching: " + items.size());
    }

    private View buildContinueCard(final Config.ContinueItem it, boolean first) {
        final boolean narrow = isNarrow();
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackgroundResource(R.drawable.card_server);
        int pad = dp(narrow ? 12 : 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                narrow ? dp(220) : dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        card.setLayoutParams(lp);

        final TextView title = new TextView(this);
        title.setText(it.name == null || it.name.isEmpty() ? it.url : it.name);
        title.setTextSize(narrow ? 14 : 16);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        card.addView(title);

        // 进度线：两条 View 用 weight 表示百分比（不依赖 ProgressBar，老系统表现一致）
        int percent = it.percent();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        barLp.topMargin = dp(9);
        bar.setLayoutParams(barLp);
        View filled = new View(this);
        filled.setBackgroundColor(getResources().getColor(R.color.accent2));
        View rest = new View(this);
        rest.setBackgroundColor(getResources().getColor(R.color.divider));
        bar.addView(filled, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(percent, 2)));
        bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(100 - percent, 1)));
        card.addView(bar);

        TextView sub = new TextView(this);
        sub.setText("看到 " + it.progressText());
        sub.setTextSize(narrow ? 11 : 12);
        sub.setTextColor(getResources().getColor(R.color.text_secondary));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(7), 0, 0);
        card.addView(sub);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setTextColor(hasFocus
                        ? getResources().getColor(R.color.accent_light)
                        : getResources().getColor(R.color.text_primary));
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playContinue(it);
            }
        });
        Ui.bindLong(card, new Runnable() {
            @Override
            public void run() {
                Config.removeContinue(MainActivity.this, it.url);
                Toast.makeText(MainActivity.this, "已从「继续观看」移除", Toast.LENGTH_SHORT).show();
                refreshContinue();
            }
        });
        if (first) card.setTag("first");
        return card;
    }

    /** 直接开播并跳到上次的位置 */
    private void playContinue(Config.ContinueItem it) {
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("url", it.url);
            intent.putExtra("name", it.name);
            intent.putExtra("mode", it.mode);
            intent.putExtra("host", it.host);
            intent.putExtra("share", it.share);
            intent.putExtra("user", it.user);
            intent.putExtra("pass", it.pass);
            intent.putExtra("position", it.pos);
            startActivity(intent);
            LogBus.mark("UI", "continue play " + it.url + " @" + it.pos);
        } catch (Throwable t) {
            Toast.makeText(this, "续播失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private View buildUsbCard(final UsbStorage.Volume v) {
        final boolean narrow = isNarrow();
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.card_server);
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);

        TextView badge = new TextView(this);
        badge.setText("USB");
        badge.setTextSize(11);
        badge.setTextColor(getResources().getColor(R.color.badge_text));
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundResource(R.drawable.badge_usb);
        row.addView(badge, new LinearLayout.LayoutParams(
                dp(narrow ? 44 : 52), dp(narrow ? 36 : 40)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(14);
        row.addView(textCol, colLp);

        final TextView title = new TextView(this);
        title.setText(v.label);
        title.setTextSize(narrow ? 15 : 17);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView sub = new TextView(this);
        String space = v.sizeText();
        sub.setText((space.isEmpty() ? "" : space + " · ") + v.path);
        sub.setTextSize(11);
        sub.setTextColor(getResources().getColor(R.color.text_muted));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sub.setPadding(0, dp(3), 0, 0);
        textCol.addView(sub);

        TextView tail = new TextView(this);
        tail.setText("›");
        tail.setTextSize(22);
        tail.setTextColor(getResources().getColor(R.color.text_tertiary));
        LinearLayout.LayoutParams tailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tailLp.leftMargin = dp(10);
        row.addView(tail, tailLp);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                title.setTextColor(hasFocus
                        ? getResources().getColor(R.color.accent_light)
                        : getResources().getColor(R.color.text_primary));
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openLocal(v);
            }
        });
        return row;
    }

    private void openLocal(UsbStorage.Volume v) {
        Intent intent = new Intent(this, BrowseActivity.class);
        intent.putExtra("mode", 2);
        intent.putExtra("host", v.label);
        intent.putExtra("share", "");
        intent.putExtra("path", v.path);
        intent.putExtra("local_root", v.path);
        intent.putExtra("local_label", v.label);
        startActivity(intent);
    }

    // ==================== 「＋」：添加 / 编辑服务器 ====================

    /**
     * SMB 与 WebDAV 合并到一个入口：顶部两个标签切换协议，下面同一套表单。
     *
     * @param editIndex >=0 表示编辑已有记录（成功后替换而不是新增）
     * @param prefill   编辑时的原始数据
     */
    /** 扫描结果：点一项就把 IP 填进地址框 */
    private void showScanResultDialog(final List<String> hosts, final EditText hostEdit,
                                      final TextView dlgStatus) {
        final LinearLayout root = Ui.column(this);
        // 有些网络（虚拟网卡、企业网关）会对任何 IP 都应答，结果可能上百条：只列前 20 个
        final int total = hosts.size();
        final int limit = Math.min(total, 20);
        root.addView(Ui.title(this, "找到 " + total + " 台开 445 的设备"));
        root.addView(Ui.subtitle(this, total > limit
                ? ("先列前 " + limit + " 个，点一下填入地址")
                : "点一下填入地址，共享名还要在对方机器上看一眼"));

        final Dialog dialog = Ui.dialogScroll(this, root, 0.46, 0.7);
        for (int i = 0; i < limit; i++) {
            final String ip = hosts.get(i);
            Ui.gap(this, root, 10);
            TextView row = Ui.action(this, ip, false);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hostEdit.setText(ip);
                    hostEdit.setSelection(hostEdit.getText().length());
                    if (dlgStatus != null) dlgStatus.setText("");
                    dialog.dismiss();
                }
            });
            root.addView(row);
        }
        Ui.gap(this, root, 16);
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

    private void showAddServerDialog(final int editIndex, final Config.HistoryItem prefill) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = getLayoutInflater().inflate(R.layout.dialog_add_server, null);
        dialog.setContentView(root);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = (int) (dm.widthPixels * 0.52);
            if (width > dp(760)) width = dp(760);
            // 电视只有 540dp 高，表单比屏幕高时会被裁掉底部按钮：短屏直接给固定高度，
            // 里面的 ScrollView 自己滚，按钮就永远看得见
            int height = WindowManager.LayoutParams.WRAP_CONTENT;
            if (dm.heightPixels / dm.density < 640) {
                height = (int) (dm.heightPixels * 0.9);
            }
            win.setLayout(width, height);
        }
        dialog.setCanceledOnTouchOutside(false);

        final TextView tabSmb = (TextView) root.findViewById(R.id.tab_smb);
        final TextView tabWebdav = (TextView) root.findViewById(R.id.tab_webdav);
        final TextView labelHost = (TextView) root.findViewById(R.id.label_host);
        final TextView labelShare = (TextView) root.findViewById(R.id.label_share);
        final EditText hostEdit = (EditText) root.findViewById(R.id.edit_host);
        final EditText shareEdit = (EditText) root.findViewById(R.id.edit_share);
        final EditText userEdit = (EditText) root.findViewById(R.id.edit_user);
        final EditText passEdit = (EditText) root.findViewById(R.id.edit_pass);
        final TextView dlgStatus = (TextView) root.findViewById(R.id.dlg_status);
        final TextView btnScan = (TextView) root.findViewById(R.id.btn_scan);
        final EditText nameEdit = (EditText) root.findViewById(R.id.edit_name);
        if (editIndex >= 0 && prefill != null && prefill.name != null) {
            nameEdit.setText(prefill.name);
        }
        final TextView btnSave = (TextView) root.findViewById(R.id.btn_save);
        final TextView btnCancel = (TextView) root.findViewById(R.id.btn_cancel);
        final TextView dlgTitle = (TextView) root.findViewById(R.id.dlg_title);

        passEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        if (editIndex >= 0) {
            dlgTitle.setText("编辑服务器");
            btnSave.setText("保存并连接");
        }

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnScan.setEnabled(false);
                showDlgStatus(dlgStatus, "正在扫描局域网…");
                LanScan.scan(new LanScan.Callback() {
                    @Override
                    public void onDone(final java.util.List<String> hosts) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                btnScan.setEnabled(true);
                                if (hosts.isEmpty()) {
                                    showDlgStatus(dlgStatus,
                                            "没扫到开 445 的机器：确认电视和电脑在同一 Wi-Fi/路由器下");
                                    return;
                                }
                                showDlgStatus(dlgStatus, "");
                                showScanResultDialog(hosts, hostEdit, dlgStatus);
                            }
                        });
                    }
                });
            }
        });

        String savedHost = Config.getHost(this);
        final int[] mode = new int[]{prefill != null ? prefill.mode
                : (savedHost == null || savedHost.isEmpty() ? 0 : Config.getMode(this))};

        final Runnable applyMode = new Runnable() {
            @Override
            public void run() {
                boolean smb = mode[0] == 0;
                tabSmb.setBackgroundResource(smb ? R.drawable.tab_on : R.drawable.tab_off);
                tabWebdav.setBackgroundResource(smb ? R.drawable.tab_off : R.drawable.tab_on);
                labelHost.setText(smb ? "SMB 地址（IP 或主机名）" : "WebDAV 地址");
                hostEdit.setHint(smb ? getString(R.string.host_hint_smb)
                        : getString(R.string.host_hint_webdav));
                int vis = smb ? View.VISIBLE : View.GONE;
                labelShare.setVisibility(vis);
                shareEdit.setVisibility(vis);
            }
        };
        applyMode.run();

        tabSmb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mode[0] = 0;
                applyMode.run();
            }
        });
        tabWebdav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mode[0] = 1;
                applyMode.run();
            }
        });

        if (prefill != null) {
            hostEdit.setText(prefill.host == null ? "" : prefill.host);
            shareEdit.setText(prefill.share == null ? "" : prefill.share);
            userEdit.setText(prefill.user == null ? "" : prefill.user);
            passEdit.setText(prefill.pass == null ? "" : prefill.pass);
        } else {
            // 新建时带上当前配置做默认值，少打几个字
            hostEdit.setText(Config.getHost(this));
            shareEdit.setText(Config.getShare(this));
            userEdit.setText(Config.getUser(this));
            passEdit.setText(Config.getPass(this));
        }

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int m = mode[0];
                String srvName = nameEdit.getText().toString().trim();
                String host = hostEdit.getText().toString().trim();
                String share = shareEdit.getText().toString().trim();
                final String user = userEdit.getText().toString().trim();
                final String pass = passEdit.getText().toString();

                if (host.isEmpty()) {
                    showDlgStatus(dlgStatus, "请填写服务器地址");
                    hostEdit.requestFocus();
                    return;
                }
                if (m == 0 && share.isEmpty()) {
                    showDlgStatus(dlgStatus, "请填写共享文件夹名（如 video）");
                    shareEdit.requestFocus();
                    return;
                }
                if (m == 1 && !host.startsWith("http://") && !host.startsWith("https://")) {
                    host = "http://" + host;
                    hostEdit.setText(host);
                }
                final String finalHost = host;
                final String finalShare = share;

                btnSave.setEnabled(false);
                btnCancel.setEnabled(false);
                showDlgStatus(dlgStatus, "正在连接测试…");
                startStagePoll(dlgStatus, "正在连接测试");

                final int seq = ++connectSeq;
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != connectSeq) return;
                        stopStagePoll();
                        btnSave.setEnabled(true);
                        btnCancel.setEnabled(true);
                        String stage = Smb2Client.stageText();
                        showDlgStatus(dlgStatus, "连接超时：卡在「"
                                + (stage == null ? "未知" : stage)
                                + "」，检查共享名与账号后重试");
                    }
                }, CONNECT_TIMEOUT_MS);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            if (m == 0) {
                                SmbClient c = new SmbClient(finalHost, finalShare, user, pass);
                                try {
                                    c.testConnection();
                                    LogBus.mark("SMB", "connected " + finalHost + "/" + finalShare
                                            + " via " + c.info());
                                } finally {
                                    c.close();
                                }
                            } else {
                                new WebDavClient(finalHost, user, pass).testConnection();
                            }
                        } catch (Throwable e) {
                            err = e.getMessage();
                            if (err == null || err.isEmpty()) {
                                err = "连接失败（" + e.getClass().getSimpleName() + "）";
                            }
                        }
                        final String finalErr = err;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != connectSeq) return;
                                stopStagePoll();
                                btnSave.setEnabled(true);
                                btnCancel.setEnabled(true);
                                if (isFinishing()) return;
                                if (finalErr != null) {
                                    showDlgStatus(dlgStatus, "连接失败：" + finalErr);
                                    return;
                                }
                                if (editIndex >= 0) {
                                    Config.updateHistory(MainActivity.this, editIndex, srvName, m,
                                            finalHost, finalShare, user, pass);
                                } else {
                                    Config.addHistory(MainActivity.this, srvName, m, finalHost,
                                            finalShare, user, pass);
                                }
                                Config.saveCurrent(MainActivity.this, srvName, m, finalHost,
                                        finalShare, user, pass);
                                dialog.dismiss();
                                refreshServers();
                                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                                openBrowse(m, finalHost, finalShare, user, pass);
                            }
                        });
                    }
                }, "dialog-connect").start();
            }
        });

        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            DisplayMetrics dm2 = getResources().getDisplayMetrics();
            int w2 = (int) (dm2.widthPixels * 0.52);
            if (w2 > dp(760)) w2 = dp(760);
            shown.setLayout(w2, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        hostEdit.requestFocus();
    }

    private void showDlgStatus(TextView tv, String msg) {
        if (tv == null) return;
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    // ==================== 诊断 ====================

    /**
     * 「诊断」按钮 = 设置 + 诊断 合并面板。
     * 主界面只剩「诊断」「＋」两个入口，其它开关都收在这里：
     *   · 启动自动连接（用哪台服务器自动进）
     *   · 记忆播放进度（断点续播）
     *   · 播放引擎切换
     *   · 崩溃日志查看 / 清空
     */
    private void showSettingsDialog() {
        final LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "设置"));
        root.addView(Ui.subtitle(this, "Lumo " + appVersion()));

        final Dialog dialog = Ui.dialogScroll(this, root, 0.72, 0.88);

        // ===== 播放 =====
        root.addView(Ui.section(this, "播放"));
        Ui.gap(this, root, 10);

        final TextView engineRow = Ui.action(this, engineText(), false);
        engineRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (Config.getEngine(MainActivity.this) + 1) % Config.ENGINE_COUNT;
                Config.setEngine(MainActivity.this, next);
                engineRow.setText(engineText());
                Toast.makeText(MainActivity.this,
                        "播放引擎已切换为：" + Config.ENGINE_NAMES[next], Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(engineRow);

        Ui.gap(this, root, 10);
        final TextView renderRow = Ui.action(this, renderText(), false);
        renderRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (Config.getRenderMode(MainActivity.this) + 1) % 3;
                Config.setRenderMode(MainActivity.this, next);
                renderRow.setText(renderText());
                Toast.makeText(MainActivity.this, "渲染方式：" + Config.RENDER_NAMES[next],
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(renderRow);

        Ui.gap(this, root, 10);
        final TextView resumeRow = Ui.action(this, resumeText(), false);
        resumeRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !Config.isResumeEnabled(MainActivity.this);
                Config.setResumeEnabled(MainActivity.this, next);
                resumeRow.setText(resumeText());
            }
        });
        root.addView(resumeRow);

        Ui.gap(this, root, 10);
        final TextView holdRow = Ui.action(this, holdSpeedText(), false);
        holdRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Config.setHoldSpeed(MainActivity.this, Config.nextHoldSpeed(MainActivity.this));
                holdRow.setText(holdSpeedText());
            }
        });
        root.addView(holdRow);

        // ===== 界面 =====
        root.addView(Ui.section(this, "界面"));
        Ui.gap(this, root, 10);

        final TextView continueRow2 = Ui.action(this, continueText(), false);
        continueRow2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !Config.isContinueEnabled(MainActivity.this);
                Config.setContinueEnabled(MainActivity.this, next);
                continueRow2.setText(continueText());
                refreshContinue();
            }
        });
        root.addView(continueRow2);

        Ui.gap(this, root, 10);
        final TextView thumbRow = Ui.action(this, thumbsText(), false);
        thumbRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !Config.getThumbs(MainActivity.this);
                Config.setThumbs(MainActivity.this, next);
                thumbRow.setText(thumbsText());
                if (!next) ThumbCache.clearAll(MainActivity.this);
            }
        });
        root.addView(thumbRow);

        // ===== 网络 =====
        root.addView(Ui.section(this, "网络"));
        Ui.gap(this, root, 10);

        final TextView autoRow = Ui.action(this, autoConnectText(), false);
        autoRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !Config.isAutoConnect(MainActivity.this);
                Config.setAutoConnect(MainActivity.this, next);
                autoRow.setText(autoConnectText());
                Toast.makeText(MainActivity.this,
                        next ? "已开启：下次启动自动进入最近使用的服务器"
                                : "已关闭：启动后停在服务器列表",
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(autoRow);

        Ui.gap(this, root, 10);
        final TextView smbRow = Ui.action(this, smbProtoText(), false);
        smbRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (Config.getSmbProtocol(MainActivity.this) + 1) % 3;
                Config.setSmbProtocol(MainActivity.this, next);
                smbRow.setText(smbProtoText());
            }
        });
        root.addView(smbRow);

        // ===== 维护 =====
        root.addView(Ui.section(this, "维护"));
        Ui.gap(this, root, 10);

        TextView logRow = Ui.action(this, "查看日志与诊断", false);
        logRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog();
            }
        });
        root.addView(logRow);

        Ui.gap(this, root, 10);
        TextView clear = Ui.action(this, "清空崩溃日志", false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBus.clearAbnormal();
                Toast.makeText(MainActivity.this, "已清空历史崩溃日志", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(clear);

        Ui.gap(this, root, 18);
        TextView close = Ui.primary(this, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(close);

        dialog.show();
        scrollDialogToTop(dialog);
        engineRow.post(new Runnable() {
            @Override
            public void run() {
                engineRow.requestFocus();
                scrollDialogToTop(dialog);
            }
        });
    }

    /** 日志页：诊断数据 + 运行日志整在一块（v1.46 从「诊断」面板迁过来） */
    private void showLogDialog() {
        final LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "日志与诊断"));

        final Dialog dialog = Ui.dialogScroll(this, root, 0.72, 0.9);

        String diag = diagnosticsText();
        root.addView(Ui.subtitle(this, diag));

        Ui.gap(this, root, 12);
        TextView tv = new TextView(this);
        String report = LogBus.report(this);
        if (report != null && report.length() > 12000) report = report.substring(0, 12000) + "\n…（已截断）";
        tv.setText(report);
        tv.setTextSize(11);
        tv.setTextColor(getResources().getColor(R.color.text_body));
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(3), 1f);
        root.addView(tv);

        Ui.gap(this, root, 16);
        TextView clear = Ui.ghost(this, "清空崩溃日志");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBus.clearAbnormal();
                dialog.dismiss();
                showLogDialog();
            }
        });
        TextView close = Ui.primary(this, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(Ui.row(this, clear, close, 12));

        dialog.show();
        scrollDialogToTop(dialog);
        close.post(new Runnable() {
            @Override
            public void run() {
                close.requestFocus();
            }
        });
    }

    /** 设备/屏幕/引擎/解码能力这些「诊断数据」，收进日志页顶部 */
    private String diagnosticsText() {
        StringBuilder sb = new StringBuilder();
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            sb.append("屏幕 ").append(dm.widthPixels).append("×").append(dm.heightPixels)
                    .append("  dpi=").append(dm.densityDpi)
                    .append(dm.widthPixels >= 2560 ? "（4K 面板）" : "").append("\n");
        } catch (Throwable ignored) {
        }
        sb.append("设备 ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("引擎 ").append((Config.getEngine(this) + 1)).append("/").append(Config.ENGINE_COUNT)
                .append(" ").append(Config.getEngineName(this)).append("\n");
        sb.append("渲染 ").append(Config.RENDER_NAMES[Config.getRenderMode(this)]).append("\n");
        sb.append(Config.lastScreenInfo == null || "-".equals(Config.lastScreenInfo)
                ? "" : "上次播放 " + Config.lastScreenInfo + "\n");
        sb.append("解码能力\n").append(CodecInfo.describe());
        return sb.toString();
    }

    private void scrollDialogToTop(Dialog dialog) {
        try {
            View sv = dialog.findViewById(R.id.dlg_scroll);
            if (sv instanceof ScrollView) ((ScrollView) sv).scrollTo(0, 0);
        } catch (Throwable ignored) {
        }
    }

    /** 开始刷新「正在连接 …」后面的实时阶段 */
    private void startStagePoll(final TextView target, final String prefix) {
        stopStagePoll();
        stagePolling = true;
        stagePoll = new Runnable() {
            @Override
            public void run() {
                if (!stagePolling || target == null) return;
                String st = Smb2Client.stageText();
                if (st != null && !st.isEmpty() && !"已连接".equals(st)) {
                    target.setText(prefix + "（" + st + "…）");
                } else {
                    target.setText(prefix);
                }
                ui.postDelayed(this, 500);
            }
        };
        ui.post(stagePoll);
    }

    private void stopStagePoll() {
        stagePolling = false;
        if (stagePoll != null) ui.removeCallbacks(stagePoll);
        stagePoll = null;
    }

    private void updateThemeIcon() {
        if (themeButton == null) return;
        boolean light = Theme.isLight(this);
        int color = getResources().getColor(R.color.text_primary);
        themeButton.setImageDrawable(new ThemeIcon(color, light));
        themeButton.setContentDescription(light ? "当前浅色，点击切换深色" : "当前深色，点击切换浅色");
    }

    private String autoConnectText() {
        return "启动自动连接服务器：" + (Config.isAutoConnect(this) ? "开" : "关");
    }

    private String resumeText() {
        return "记忆播放进度：" + (Config.isResumeEnabled(this) ? "开" : "关");
    }

    private String holdSpeedText() {
        float v = Config.getHoldSpeed(this);
        return "长按加速倍速：" + ((int) v) + "×";
    }

    private String engineText() {
        return "播放引擎：" + Config.getEngineName(this);
    }

    private String thumbsText() {
        return "文件列表缩略图：" + (Config.getThumbs(this) ? "开" : "关");
    }

    private String smbProtoText() {
        return "SMB 协议：" + Config.SMB_PROTO_NAMES[Config.getSmbProtocol(this)];
    }

    private String continueText() {
        return "继续观看卡片：" + (Config.isContinueEnabled(this) ? "开" : "关");
    }

    private String renderText() {
        return "渲染方式：" + Config.RENDER_NAMES[Config.getRenderMode(this)];
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 其它 ====================

    private void setStatus(String msg) {
        if (statusText == null) return;
        statusText.setText(msg);
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        if (statusText != null) statusText.setVisibility(View.GONE);
    }

    /** @return 是否有调试入口（playurl / imgpath） */
    private boolean handleDebugIntent() {
        Intent it = getIntent();
        if (it == null) return false;
        String playUrl = it.getStringExtra("playurl");
        String imgPath = it.getStringExtra("imgpath");
        String localPath = it.getStringExtra("localpath");
        boolean hasTarget = (playUrl != null && !playUrl.isEmpty())
                || (imgPath != null && !imgPath.isEmpty())
                || (localPath != null && !localPath.isEmpty());

        if (playUrl != null && !playUrl.isEmpty()) {
            Intent pi = new Intent(this, PlayerActivity.class);
            pi.putExtra("url", playUrl);
            pi.putExtra("name", playUrl);
            pi.putExtra("mode", it.getIntExtra("playmode", 1));
            pi.putExtra("user", it.getStringExtra("playuser"));
            pi.putExtra("pass", it.getStringExtra("playpass"));
            pi.putExtra("engine", it.getIntExtra("playengine", Config.getEngine(this)));
            pi.putExtra("folder", it.getStringExtra("folder"));
            startActivity(pi);
        }
        if (imgPath != null && !imgPath.isEmpty()) {
            Intent ii = new Intent(this, ImageActivity.class);
            ii.putExtra("mode", it.getIntExtra("imgmode", 1));
            ii.putExtra("host", it.getStringExtra("imghost"));
            ii.putExtra("share", it.getStringExtra("imgshare"));
            ii.putExtra("user", it.getStringExtra("imguser"));
            ii.putExtra("pass", it.getStringExtra("imgpass"));
            ii.putExtra("path", imgPath);
            ii.putExtra("name", imgPath);
            startActivity(ii);
        }
        if (localPath != null && !localPath.isEmpty()) {
            Intent bi = new Intent(this, BrowseActivity.class);
            bi.putExtra("mode", 2);
            bi.putExtra("path", localPath);
            bi.putExtra("local_root", localPath);
            bi.putExtra("local_label", it.getStringExtra("locallabel") == null
                    ? "本地存储" : it.getStringExtra("locallabel"));
            startActivity(bi);
        }
        return hasTarget;
    }

    /** 读 U 盘需要存储权限；Android 4.3 上无运行时权限，这句等于空操作 */
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            refreshVolumes();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

}
