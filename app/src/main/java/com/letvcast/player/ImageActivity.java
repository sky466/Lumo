package com.letvcast.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;


/**
 * 图片查看界面：从 SMB / WebDAV / 本地 U 盘 读取图片并显示，支持双指缩放、拖动
 */
public class ImageActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Theme.wrap(base));
    }


    private ImageView imageView;
    private TextView titleView;

    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private float scale = 1f;
    private float fitScale = 1f;      // 适应屏幕时的基准倍率
    private float minScale = 0.5f;
    private float maxScale = 8f;
    private android.view.GestureDetector tapDetector;
    private ScaleGestureDetector scaleDetector;

    private int touchMode = 0;
    private int transferMode = 0;   // 0=SMB 1=WebDAV
    private float startX, startY;

    // 同一文件夹内的图片列表（遥控器左右翻页）
    private String[] imageList;
    private String[] imageNames;
    private int imageIndex = -1;
    private String curPath;
    private String curName;
    private int viewMode = 0;      // 0=原始大小 1=适应屏幕 2=2倍
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image);

        imageView = (ImageView) findViewById(R.id.image_view);
        titleView = (TextView) findViewById(R.id.image_title);

        final String name = getIntent().getStringExtra("name");
        imageList = getIntent().getStringArrayExtra("ilist");
        imageNames = getIntent().getStringArrayExtra("inames");
        imageIndex = getIntent().getIntExtra("iindex", -1);
        final int fMode = getIntent().getIntExtra("mode", 0);
        transferMode = fMode;
        final String host = getIntent().getStringExtra("host");
        final String share = getIntent().getStringExtra("share");
        final String user = getIntent().getStringExtra("user");
        final String pass = getIntent().getStringExtra("pass");
        final String path = getIntent().getStringExtra("path");

        titleView.setText(name != null ? name : "正在加载图片…");
        titleView.setVisibility(View.VISIBLE);
        LogBus.mark("IMG", "start mode=" + fMode + " path=" + path);

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float next = Math.max(minScale, Math.min(scale * factor, maxScale));
                float real = next / scale;
                scale = next;
                matrix.postScale(real, real, detector.getFocusX(), detector.getFocusY());
                clampMatrix();
                imageView.setImageMatrix(matrix);
                return true;
            }
        });

        tapDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        // 双击：适应屏幕 ↔ 放大 2 倍
                        if (scale > fitScale * 1.2f) {
                            viewMode = 1;
                            applyViewMode();
                            titleView.setText(curName + "  双击=放大");
                        } else {
                            float target = Math.min(fitScale * 2f, maxScale);
                            float real = target / scale;
                            scale = target;
                            matrix.postScale(real, real, e.getX(), e.getY());
                            clampMatrix();
                            imageView.setImageMatrix(matrix);
                            titleView.setText(curName + "  双击=还原");
                        }
                        return true;
                    }
                });

        imageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleDetector.onTouchEvent(event);
                tapDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchMode = 1;
                        startX = event.getX();
                        startY = event.getY();
                        savedMatrix.set(matrix);
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // 双指开始：单指拖动让位给缩放
                        touchMode = 0;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (touchMode == 1 && event.getPointerCount() == 1
                                && scale > fitScale * 1.02f) {
                            float dx = event.getX() - startX;
                            float dy = event.getY() - startY;
                            matrix.set(savedMatrix);
                            matrix.postTranslate(dx, dy);
                            clampMatrix();
                            imageView.setImageMatrix(matrix);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        touchMode = 0;
                        break;
                }
                return true;
            }
        });

        curPath = path;
        curName = name != null && !name.isEmpty() ? name : path;
        loadCurrent();
    }

    /** 加载当前 curPath 指向的图片（翻页时复用） */
    private void loadCurrent() {
        titleView.setText(curName == null ? "正在加载图片…" : curName + "  (" + (imageIndex + 1) + "/"
                + (imageList == null ? 1 : imageList.length) + ")");
        titleView.setVisibility(View.VISIBLE);
        LogBus.mark("IMG", "load mode=" + transferMode + " path=" + curPath);
        final int fMode = transferMode;
        final String host = getIntent().getStringExtra("host");
        final String share = getIntent().getStringExtra("share");
        final String user = getIntent().getStringExtra("user");
        final String pass = getIntent().getStringExtra("pass");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bmp = loadImage(fMode, host, share, curPath, user, pass);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (bmp != null) {
                                currentBitmap = bmp;
                                imageView.setImageBitmap(bmp);
                                viewMode = 1;   // 默认适应屏幕（电视上最舒服）
                                applyViewMode();
                                imageView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        applyViewMode();
                                    }
                                });
                                titleView.setText(curName + "  (" + (imageIndex + 1) + "/"
                                        + (imageList == null ? 1 : imageList.length) + ")"
                                        + "   确定键=缩放  左右键=翻页");
                                LogBus.mark("IMG", "shown " + bmp.getWidth() + "x" + bmp.getHeight());
                            } else {
                                titleView.setText("图片加载失败");
                                LogBus.mark("IMG", "decode returned null");
                            }
                        }
                    });
                } catch (final Throwable e) {
                    LogBus.mark("IMG", "load failed: " + e);
                    final String msg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            titleView.setText("图片加载失败：" + (msg == null ? e.getClass().getSimpleName() : msg));
                        }
                    });
                }
            }
        }).start();
    }

    /** 0=原始 1=适应屏幕 2=2倍；用矩阵把图居中 */
    private boolean clampMatrixGuard = true;

    /** 把图片拉回可视区域，避免放大后一拖就只剩黑屏 */
    private void clampMatrix() {
        if (currentBitmap == null || imageView == null) return;
        float vw = imageView.getWidth();
        float vh = imageView.getHeight();
        if (vw <= 0 || vh <= 0) return;
        float[] v = new float[9];
        matrix.getValues(v);
        float transX = v[Matrix.MTRANS_X];
        float transY = v[Matrix.MTRANS_Y];
        float scaledW = currentBitmap.getWidth() * v[Matrix.MSCALE_X];
        float scaledH = currentBitmap.getHeight() * v[Matrix.MSCALE_Y];
        float dx = 0, dy = 0;
        if (scaledW <= vw) {
            dx = (vw - scaledW) / 2f - transX;
        } else {
            if (transX > 0) dx = -transX;
            else if (transX + scaledW < vw) dx = vw - (transX + scaledW);
        }
        if (scaledH <= vh) {
            dy = (vh - scaledH) / 2f - transY;
        } else {
            if (transY > 0) dy = -transY;
            else if (transY + scaledH < vh) dy = vh - (transY + scaledH);
        }
        if (dx != 0 || dy != 0) matrix.postTranslate(dx, dy);
    }

    private void applyViewMode() {
        if (currentBitmap == null || imageView == null) return;
        float vw = imageView.getWidth();
        float vh = imageView.getHeight();
        if (vw <= 0 || vh <= 0) {
            // 布局还没完成，等下一帧再算，否则会把图平移到屏幕外（表现为黑屏）
            imageView.post(new Runnable() {
                @Override
                public void run() {
                    applyViewMode();
                }
            });
            return;
        }
        float bw = currentBitmap.getWidth();
        float bh = currentBitmap.getHeight();
        float scale;
        if (viewMode == 0) {
            scale = 1f;
        } else if (viewMode == 1) {
            scale = Math.min(vw / bw, vh / bh);
        } else {
            scale = Math.min(vw / bw, vh / bh) * 2f;
        }
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((vw - bw * scale) / 2f, (vh - bh * scale) / 2f);
        imageView.setImageMatrix(matrix);
        this.scale = scale;
        this.fitScale = Math.min(vw / bw, vh / bh);
        this.minScale = Math.max(0.2f, this.fitScale * 0.5f);
        this.maxScale = Math.max(this.fitScale * 8f, 4f);
        clampMatrixGuard = false;
    }

    private void stepImage(int delta) {
        if (imageList == null || imageList.length == 0) return;
        int next = imageIndex + delta;
        if (next < 0 || next >= imageList.length) {
            Toast.makeText(this, next < 0 ? "已经是第一张" : "已经是最后一张", Toast.LENGTH_SHORT).show();
            return;
        }
        imageIndex = next;
        curPath = imageList[next];
        curName = imageNames != null && next < imageNames.length ? imageNames[next] : curPath;
        loadCurrent();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                stepImage(-1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                stepImage(1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
                viewMode = (viewMode + 1) % 3;
                applyViewMode();
                Toast.makeText(this, viewMode == 0 ? "原始大小" : (viewMode == 1 ? "适应屏幕" : "放大 2 倍"),
                        Toast.LENGTH_SHORT).show();
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                matrix.postTranslate(0, imageView.getHeight() * 0.12f);
                imageView.setImageMatrix(matrix);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                matrix.postTranslate(0, -imageView.getHeight() * 0.12f);
                imageView.setImageMatrix(matrix);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private Bitmap loadImage(int mode, String host, String share, String path, String user, String pass) throws Exception {
        byte[] data;
        if (mode == 2) {
            // 本地（U 盘 / SD 卡）：直接读文件
            data = LocalClient.read(path);
        } else if (mode == 0) {
            // SMB：一次性读入内存（流不支持 mark/reset，decodeStream 会读不完整，
            // 统一 readAll 成 byte[] 最可靠）
            SmbClient client = new SmbClient(host, share, user, pass);
            java.io.InputStream in = client.getInputStream(path);
            try {
                data = readAll(in);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
        } else {
            // WebDAV：下载到内存
            WebDavClient client = new WebDavClient(host, user, pass);
            data = client.download(path);
        }
        if (data == null || data.length == 0) {
            throw new Exception("文件为空或读取失败");
        }
        LogBus.mark("IMG", "downloaded " + data.length + " bytes, head=" + hexHead(data));
        // 解码完立即释放字节数组，降低内存峰值
        try {
            return decodeSampled(data);
        } finally {
            data = null;
        }
    }

    /** 对字节数组做采样解码，用 RGB_565 降低内存占用；OOM 时自动提高采样率重试 */
    private Bitmap decodeSampled(byte[] data) {
        // 第一次：只探测尺寸
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            LogBus.mark("IMG", "bad image header: " + hexHead(data));
            return null; // 不是合法图片或格式不支持
        }
        LogBus.mark("IMG", "bounds " + bounds.outWidth + "x" + bounds.outHeight);

        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        // 电视屏幕 1080p，超过 1920 就采样，避免超大图占满内存
        while (maxDim / sample > 1920) sample *= 2;

        // 用采样解码，若 OOM 则逐级提高采样率重试
        Bitmap bmp = null;
        while (bmp == null && sample <= 64) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            try {
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } catch (OutOfMemoryError oom) {
                sample *= 2; // 提高采样率，降低内存占用
            }
            if (bmp == null && sample <= 64) {
                sample *= 2; // 解码失败也可能因内存不足，逐级降采样
            }
        }
        return bmp;
    }

    /** 前 12 字节十六进制：用于判断服务器返回的到底是不是图片（可能是 HTML 错误页） */
    private static String hexHead(byte[] data) {
        if (data == null) return "-";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(12, data.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
