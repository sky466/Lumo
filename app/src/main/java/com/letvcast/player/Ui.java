package com.letvcast.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 统一的毛玻璃深色对话框小工具：主界面 / 文件浏览的弹窗都走这里，
 * 避免系统 Holo 白底弹窗和深色界面打架。
 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 包一层深色圆角对话框；widthRatio 是占屏宽比例 */
    public static Dialog dialog(Activity a, View content, double widthRatio) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int width = (int) (dm.widthPixels * widthRatio);
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    /**
     * 内容可能很长的对话框：外面套一层 ScrollView，并把窗口高度固定成屏幕的一个比例，
     * 这样电视和手机上都不会出现「按钮被顶到屏幕外」的问题。
     */
    public static Dialog dialogScroll(Activity a, View content, double widthRatio, double heightRatio) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(a);
        sv.setId(R.id.dlg_scroll);
        sv.setFillViewport(false);
        sv.addView(content);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout((int) (dm.widthPixels * widthRatio), (int) (dm.heightPixels * heightRatio));
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    /** 改成占位/宽度比例在 show 之后再套一次，兼容部分老固件 */
    public static void relayout(Activity a, Dialog d, double widthRatio) {
        Window w = d.getWindow();
        if (w == null) return;
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        w.setLayout((int) (dm.widthPixels * widthRatio), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.dialog_bg);
        int p = dp(c, 24);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static TextView title(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTextColor(c.getResources().getColor(R.color.text_primary));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    public static TextView subtitle(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(c.getResources().getColor(R.color.text_muted));
        tv.setSingleLine(true);
        tv.setPadding(0, dp(c, 6), 0, 0);
        return tv;
    }

    public static TextView body(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(c.getResources().getColor(R.color.text_body));
        tv.setLineSpacing(dp(c, 3), 1f);
        return tv;
    }

    /** 竖排动作项（像卡片里的菜单行） */
    public static TextView action(Context c, String text, boolean danger) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(c.getResources().getColor(danger ? R.color.text_danger : R.color.text_primary));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        tv.setBackgroundResource(R.drawable.rail_btn);
        tv.setFocusable(true);
        tv.setClickable(true);
        return tv;
    }

    /** 主按钮（蓝色实心） */
    public static TextView primary(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(c.getResources().getColor(R.color.text_primary));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        tv.setBackgroundResource(R.drawable.btn_primary);
        tv.setFocusable(true);
        tv.setClickable(true);
        return tv;
    }

    /** 次要按钮（描边） */
    public static TextView ghost(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(c.getResources().getColor(R.color.text_body));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        tv.setBackgroundResource(R.drawable.btn_ghost);
        tv.setFocusable(true);
        tv.setClickable(true);
        return tv;
    }

    public static LinearLayout row(Context c, View left, View right, int gapDp) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (left != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(left, lp);
        }
        if (right != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (left != null) lp.leftMargin = dp(c, gapDp);
            row.addView(right, lp);
        }
        return row;
    }

    /** 分组小标题：左侧一条强调色竖线 + 灰字（v1.46 设置面板分组用） */
    public static LinearLayout section(Context c, String text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 18), 0, 0);

        View bar = new View(c);
        bar.setBackgroundColor(c.getResources().getColor(R.color.accent));
        row.addView(bar, new LinearLayout.LayoutParams(dp(c, 3), dp(c, 14)));

        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(c.getResources().getColor(R.color.text_muted));
        tv.setPadding(dp(c, 8), 0, 0, 0);
        row.addView(tv);
        return row;
    }

    public static void gap(Context c, LinearLayout parent, int dp) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, dp));
        parent.addView(v, lp);
    }

    public static EditText input(Context c, String hint) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setTextSize(16);
        et.setTextColor(c.getResources().getColor(R.color.text_primary));
        et.setHintTextColor(c.getResources().getColor(R.color.text_tertiary));
        et.setBackgroundResource(R.drawable.input_bg);
        et.setPadding(dp(c, 13), dp(c, 13), dp(c, 13), dp(c, 13));
        et.setSingleLine(true);
        return et;
    }

    /** 按住多久算长按（遥控器和触摸用同一个阈值） */
    public static final int HOLD_MS = 550;

    /**
     * 绑定「长按」：<b>触摸长按</b>和<b>遥控器按住 OK</b>都触发（v1.49）。
     *
     * <p>遥控器上的长按不是系统的 long click：按住 OK 键时系统只会不断送来
     * {@code ACTION_DOWN + repeatCount>0} 的重复事件，{@code OnLongClickListener} 永远不会被调用，
     * 所以电视上「长按某个按钮出全部选项」一直是失效的。
     * 这里改成自己计时：第一次 DOWN 起一个 {@link #HOLD_MS} 的定时器，
     * 定时器到点就执行动作；松手时如果已经执行过就吞掉这次 UP（避免再触发一次普通点击），
     * 没执行过则补一次 {@code performClick()}，短按行为完全不变。
     */
    public static void bindLong(final View v, final Runnable action) {
        bindLong(v, action, null);
    }

    /**
     * 同上，{@code extra} 用来处理别的按键（例如服务器的「菜单」键），
     * 因为一个 View 只能有一个 OnKeyListener，所以合并到一个监听器里。
     */
    public static void bindLong(final View v, final Runnable action,
                                final View.OnKeyListener extra) {
        if (v == null || action == null) return;
        // 触摸长按：老路径照旧保留
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View x) {
                action.run();
                return true;
            }
        });
        v.setOnKeyListener(new View.OnKeyListener() {
            private boolean fired;
            private final Runnable fire = new Runnable() {
                @Override
                public void run() {
                    // 松手/失焦之后才到点的情况直接丢掉，避免在别的界面上误触发
                    if (!v.isFocused()) return;
                    fired = true;
                    LogBus.mark("UI", "long press " + v.getClass().getSimpleName());
                    action.run();
                }
            };

            @Override
            public boolean onKey(View x, int keyCode, android.view.KeyEvent e) {
                boolean ok = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A;
                if (!ok) return extra != null && extra.onKey(x, keyCode, e);
                int a = e.getAction();
                if (a == android.view.KeyEvent.ACTION_DOWN) {
                    x.setPressed(true);
                    if (e.getRepeatCount() == 0) {
                        fired = false;
                        x.removeCallbacks(fire);
                        x.postDelayed(fire, HOLD_MS);
                    } else if (!fired) {
                        // 兜底：有些遥控器/盒子会先连发 repeat 事件而不等我们那个定时器
                        fire.run();
                    }
                    return true;
                }
                x.removeCallbacks(fire);
                x.setPressed(false);
                if (a == android.view.KeyEvent.ACTION_UP) {
                    if (fired) {
                        fired = false;
                        return true;
                    }
                    x.performClick();
                    return true;
                }
                fired = false;
                return true;
            }
        });
    }
}
