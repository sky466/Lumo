package com.letvcast.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件列表的缩略图 / 视频首帧海报。
 *
 * <p>老电视 CPU 很弱，所以策略是「能省则省」：
 * <ul>
 *   <li>单线程串行生成，绝不同时解码多张；</li>
 *   <li>内存 LRU（约 12MB）+ 磁盘缓存（一次生成，以后直接读文件）；</li>
 *   <li>视频首帧通过本地的 SMB/WebDAV 代理取，取完立刻关掉代理；</li>
 *   <li>只做 200px 宽的缩略图，够列表显示就行。</li>
 * </ul>
 */
public class ThumbCache {

    public interface Callback {
        void onThumb(String key, Bitmap bitmap);
    }

    private static final int TARGET_WIDTH = 200;
    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    /**
     * 正在生成的 key → 等这张图的回调列表。
     *
     * <p>v1.49 前用的是一个 Set，同一 key 第二次请求直接 return 不回调：
     * 列表来回滚动（行被复用）时那几行就永远停在占位色块上。
     * 现在改成登记等待者，图出来后统一回调所有等待者；生成失败也回调一次 null，让行回退到默认图标。
     */
    private static final Map<String, List<Callback>> WAITING =
            Collections.synchronizedMap(new HashMap<String, List<Callback>>());
    private static File cacheDir;

    public static void init(Context c) {
        if (cacheDir == null) {
            cacheDir = new File(c.getApplicationContext().getFilesDir(), "thumbs");
            if (!cacheDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }
        }
    }

    public static Bitmap peek(String key) {
        return key == null ? null : MEM.get(key);
    }

    public static void clearAll(Context c) {
        MEM.evictAll();
        init(c);
        File[] files = cacheDir == null ? null : cacheDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    /**
     * 取缩略图：命中内存直接回调，否则排队生成（磁盘命中就直接读）。
     */
    public static void request(final String key, final FileItem item, final int mode,
                               final String host, final String share,
                               final String user, final String pass, final Callback cb) {
        if (key == null || item == null) return;
        Bitmap cached = MEM.get(key);
        if (cached != null) {
            cb.onThumb(key, cached);
            return;
        }
        synchronized (WAITING) {
            List<Callback> waiters = WAITING.get(key);
            if (waiters != null) {
                waiters.add(cb);      // 已经在生成，等同一张图出来一起回调
                return;
            }
            waiters = new ArrayList<Callback>();
            waiters.add(cb);
            WAITING.put(key, waiters);
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    bmp = loadFromDisk(key);
                    if (bmp == null) {
                        bmp = generate(item, mode, host, share, user, pass);
                        if (bmp != null) saveToDisk(key, bmp);
                    }
                } catch (Throwable t) {
                    LogBus.mark("THUMB", "fail " + key + ": " + t);
                }
                if (bmp != null) MEM.put(key, bmp);
                List<Callback> callbacks;
                synchronized (WAITING) {
                    callbacks = WAITING.remove(key);
                }
                if (callbacks == null) return;
                for (Callback c : callbacks) {
                    try {
                        c.onThumb(key, bmp);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /* ---------------- 生成 ---------------- */

    private static Bitmap generate(FileItem item, int mode, String host, String share,
                                   String user, String pass) throws Exception {
        String name = item.name == null ? "" : item.name.toLowerCase(java.util.Locale.ROOT);
        boolean video = isVideo(name);
        boolean image = isImage(name);
        if (!video && !image) return null;

        IStreamProxy proxy = null;
        String localSource = item.path;
        try {
            String source;
            if (mode == 2) {
                source = item.path;
            } else if (mode == 0) {
                SmbClient client = new SmbClient(host, share, user, pass);
                String url = client.getFileUrl(item.path);
                client.close();
                SmbStreamProxy p = new SmbStreamProxy(url, user, pass);
                source = p.start();
                proxy = p;
            } else {
                WebDavClient client = new WebDavClient(host, user, pass);
                String url = client.buildFileUrl(item.path);
                WebDavStreamProxy p = new WebDavStreamProxy(url, user, pass);
                source = p.start();
                proxy = p;
            }
            localSource = source;

            if (video) {
                return firstFrame(source);
            }
            byte[] data = readAll(source, item, mode);
            return decodeScaled(data);
        } finally {
            if (proxy != null) {
                try {
                    proxy.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 视频首帧（跳 2 秒，避开开头的黑场） */
    private static Bitmap firstFrame(String source) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            try {
                // 网络地址必须带 headers 的那个重载，否则老系统会抛 IllegalArgumentException
                r.setDataSource(source, new java.util.HashMap<String, String>());
            } catch (Throwable t1) {
                r.setDataSource(source);
            }
            Bitmap frame = r.getFrameAtTime(2000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = r.getFrameAtTime(0);
            return scaleToWidth(frame);
        } catch (Throwable t) {
            LogBus.mark("THUMB", "frame fail " + source + ": " + t);
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 图片：走本地代理时用 HTTP 读，其它情况直接读文件字节 */
    private static byte[] readAll(String source, FileItem item, int mode) throws Exception {
        if (mode == 2) {
            return LocalClient.read(item.path);
        }
        if (item.size > 16L * 1024 * 1024) return null;   // 超大图不做（老机器会 OOM）
        java.net.HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(source).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decodeScaled(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        return scaleToWidth(bmp);
    }

    private static Bitmap scaleToWidth(Bitmap src) {
        if (src == null) return null;
        if (src.getWidth() <= TARGET_WIDTH) return src;
        int h = Math.max(1, src.getHeight() * TARGET_WIDTH / src.getWidth());
        Bitmap out = Bitmap.createScaledBitmap(src, TARGET_WIDTH, h, true);
        if (out != src) src.recycle();
        return out;
    }

    /* ---------------- 磁盘缓存 ---------------- */

    private static File fileFor(String key) {
        if (cacheDir == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new File(cacheDir, sb + ".jpg");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap loadFromDisk(String key) {
        File f = fileFor(key);
        if (f == null || !f.exists() || f.length() == 0) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveToDisk(String key, Bitmap bmp) {
        File f = fileFor(key);
        if (f == null) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, fos);
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isVideo(String n) {
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".ts")
                || n.endsWith(".m2ts") || n.endsWith(".mov") || n.endsWith(".flv")
                || n.endsWith(".wmv") || n.endsWith(".mpg") || n.endsWith(".mpeg")
                || n.endsWith(".rmvb") || n.endsWith(".rm") || n.endsWith(".webm")
                || n.endsWith(".m4v") || n.endsWith(".3gp");
    }

    private static boolean isImage(String n) {
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }
}
