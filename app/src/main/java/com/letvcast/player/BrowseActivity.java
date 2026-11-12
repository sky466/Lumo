package com.letvcast.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件浏览界面（SMB / WebDAV / 本地 U 盘 通用）
 *
 * mode: 0 = SMB, 1 = WebDAV, 2 = 本地（U 盘 / SD 卡，走 LocalClient）
 */
public class BrowseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Theme.wrap(base));
    }

    private ListView listView;
    private TextView pathView;
    private TextView emptyView;
    private LinearLayout headerBar;

    private int mode = 0; // 0=SMB, 1=WebDAV, 2=本地(U盘)
    private String host, share, user, pass;
    private String currentPath = "";
    /** 本地模式的卷根目录（"返回上级"到这里就退出，不会爬到 / ） */
    private String localRoot = "";
    private String localLabel = "";

    private SmbClient smbClient;
    private WebDavClient webdavClient;

    private List<FileItem> items = new ArrayList<>();
    private ItemAdapter adapter;
    private int loadRequestId = 0; // 用于丢弃过期目录加载结果，避免快速切换时乱序
    private TextView sortView;
    private TextView searchView;
    private boolean searching = false;
    private String searchKeyword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);

        listView = (ListView) findViewById(R.id.list_view);
        pathView = (TextView) findViewById(R.id.path_view);
        emptyView = (TextView) findViewById(R.id.empty_view);
        headerBar = (LinearLayout) findViewById(R.id.header_bar);
        sortView = (TextView) findViewById(R.id.sort_view);
        searchView = (TextView) findViewById(R.id.search_view);
        findViewById(R.id.up_view).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goParent();
            }
        });
        sortView.setText("排序：" + Config.sortLabel(this));
        sortView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        searchView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchDialog();
            }
        });

        mode = getIntent().getIntExtra("mode", 0);
        host = getIntent().getStringExtra("host");
        share = getIntent().getStringExtra("share");
        user = getIntent().getStringExtra("user");
        pass = getIntent().getStringExtra("pass");
        currentPath = getIntent().getStringExtra("path");
        if (currentPath == null) currentPath = "";

        if (mode == 0) {
            smbClient = new SmbClient(host, share, user, pass);
        } else if (mode == 1) {
            webdavClient = new WebDavClient(host, user, pass);
        } else {
            localRoot = getIntent().getStringExtra("local_root");
            if (localRoot == null) localRoot = "";
            localLabel = getIntent().getStringExtra("local_label");
            if (localLabel == null || localLabel.isEmpty()) localLabel = "U 盘";
            if (currentPath.isEmpty() || (!localRoot.isEmpty() && !currentPath.startsWith(localRoot))) {
                currentPath = localRoot;
            }
            LogBus.mark("BROWSE", "local root=" + localRoot + " path=" + currentPath);
        }

        adapter = new ItemAdapter();
        listView.setAdapter(adapter);

        // 电视遥控器导航（标准 TV 方案）：
        // - setItemsCanFocus(false)：item 不抢焦点，保证「按确定键」能正常触发 onItemClick
        // - 方向键移动时 ListView 会给选中行 state_selected，行背景（list_item_bg.xml）
        //   自己画蓝色描边高亮，比满屏色块更像卡片
        listView.setItemsCanFocus(false);
        listView.setFocusable(true);
        listView.setFocusableInTouchMode(false);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listView.setDrawSelectorOnTop(false);
        // 延迟 requestFocus，确保 ListView 完成 attach 后再抢焦点
        listView.post(new Runnable() {
            @Override
            public void run() {
                listView.requestFocus();
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                FileItem item = items.get(position);
                BrowseActivity.this.onItemClick(item);
            }
        });

        // headerBar 只用于「点击返回上级」，不抢遥控器焦点
        headerBar.setFocusable(false);
        headerBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goParent();
            }
        });

        loadDir(currentPath);
    }

    private void onItemClick(FileItem item) {
        if (item.isDirectory) {
            // item.path 已经是服务端返回的正确相对路径，直接用，避免手动拼接出错
            loadDir(item.path);
        } else if (isVideo(item.name)) {
            playVideo(item);
        } else if (isImage(item.name)) {
            viewImage(item);
        } else {
            Toast.makeText(this, "无法预览此文件类型", Toast.LENGTH_SHORT).show();
        }
    }

    /** 排序：文件夹永远在前，其余按用户选择（名称/日期/大小，升降序） */
    private void sortItems(List<FileItem> list) {
        final int key = Config.getSortKey(this);
        final boolean desc = Config.isSortDesc(this);
        java.util.Collections.sort(list, new java.util.Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
                int r;
                if (key == Config.SORT_DATE) {
                    r = Long.compare(parseDate(a.modified), parseDate(b.modified));
                } else if (key == Config.SORT_SIZE) {
                    r = Long.compare(a.size, b.size);
                } else {
                    String na = a.name == null ? "" : a.name.toLowerCase(java.util.Locale.ROOT);
                    String nb = b.name == null ? "" : b.name.toLowerCase(java.util.Locale.ROOT);
                    r = na.compareTo(nb);
                }
                return desc ? -r : r;
            }
        });
    }

    /** 解析日期字符串（WebDAV 是 RFC1123，SMB 常见 yyyy-MM-dd HH:mm），解析不了给 0 */
    private static long parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return 0L;
        String[] patterns = {"EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                java.util.Date d = f.parse(s.trim());
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private void showSortDialog() {
        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "排序方式"));
        root.addView(Ui.subtitle(this, "设置一次，所有目录都按这个顺序"));

        final Dialog dialog = Ui.dialog(this, root, 0.4);

        final String[] labels = {"名称 升序", "名称 降序", "日期 升序", "日期 降序", "大小 升序", "大小 降序"};
        Ui.gap(this, root, 16);
        final TextView[] first = new TextView[1];
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            TextView item = Ui.action(this, labels[i], false);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int key = which / 2;
                    boolean desc = (which % 2) == 1;
                    Config.setSort(BrowseActivity.this, key, desc);
                    sortView.setText("排序：" + Config.sortLabel(BrowseActivity.this));
                    sortItems(items);
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                }
            });
            root.addView(item);
            if (i == 0) first[0] = item;
            Ui.gap(this, root, 8);
        }

        TextView cancel = Ui.ghost(this, "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(cancel);

        dialog.show();
        Ui.relayout(this, dialog, 0.4);
        if (first[0] != null) first[0].requestFocus();
    }

    private void showSearchDialog() {
        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "搜索文件"));
        root.addView(Ui.subtitle(this, "递归查找文件名"));

        final Dialog dialog = Ui.dialog(this, root, 0.46);

        final EditText input = Ui.input(this, "输入文件名关键字");
        input.setText(searchKeyword);
        Ui.gap(this, root, 16);
        root.addView(input);

        TextView go = Ui.primary(this, "搜索");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchKeyword = input.getText().toString().trim();
                dialog.dismiss();
                if (!searchKeyword.isEmpty()) doSearch(searchKeyword);
            }
        });
        TextView clear = Ui.ghost(this, "清除");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searching = false;
                searchKeyword = "";
                dialog.dismiss();
                loadDir(currentPath);
            }
        });
        TextView cancel = Ui.ghost(this, "取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        Ui.gap(this, root, 18);
        root.addView(Ui.row(this, clear, go, 12));
        Ui.gap(this, root, 10);
        root.addView(cancel);

        dialog.show();
        Ui.relayout(this, dialog, 0.46);
        input.requestFocus();
    }

    /** 递归搜索当前目录（最多 4 层 / 500 个条目，避免把网盘刷爆） */
    private void doSearch(final String keyword) {
        searching = true;
        emptyView.setText("搜索中…");
        emptyView.setVisibility(View.VISIBLE);
        final int requestId = ++loadRequestId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.List<FileItem> found = new ArrayList<>();
                java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
                java.util.HashMap<String, Integer> depth = new java.util.HashMap<>();
                queue.add(currentPath == null ? "" : currentPath);
                depth.put(currentPath == null ? "" : currentPath, 0);
                int scanned = 0;
                String kw = keyword.toLowerCase(java.util.Locale.ROOT);
                try {
                    while (!queue.isEmpty() && scanned < 500 && found.size() < 200) {
                        String dir = queue.poll();
                        int d = depth.containsKey(dir) ? depth.get(dir) : 0;
                        List<FileItem> list = listDir(dir);
                        if (list == null) continue;
                        for (FileItem f : list) {
                            scanned++;
                            if (f.name != null && f.name.toLowerCase(java.util.Locale.ROOT).contains(kw)) {
                                found.add(f);
                            }
                            if (f.isDirectory && d < 3) {
                                queue.add(f.path);
                                depth.put(f.path, d + 1);
                            }
                        }
                    }
                    final List<FileItem> result = found;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestId != loadRequestId || isFinishing()) return;
                            sortItems(result);
                            items = result;
                            adapter.notifyDataSetChanged();
                            listView.setSelection(0);
                            pathView.setText("搜索：" + keyword + "  (" + result.size() + " 项)");
                            if (result.isEmpty()) {
                                emptyView.setText("没有找到匹配的文件");
                                emptyView.setVisibility(View.VISIBLE);
                            } else {
                                emptyView.setVisibility(View.GONE);
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestId != loadRequestId || isFinishing()) return;
                            emptyView.setText("搜索失败：" + e.getMessage());
                            emptyView.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        // 释放 SMB 会话（SMB2 是一条长连接，别等 GC）
        if (smbClient != null) {
            try {
                smbClient.close();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    /** 统一列目录入口（SMB / WebDAV / 本地） */
    private List<FileItem> listDir(String dir) throws Exception {
        if (mode == 0) return smbClient.list(dir);
        if (mode == 1) return webdavClient.list(dir);
        return LocalClient.list(dir);
    }

    /** 顶部路径显示：本地模式显示「卷名 / 相对路径」 */
    private String displayPath(String path) {
        if (mode != 2) return (path == null || path.isEmpty()) ? "/" : path;
        if (path == null || path.isEmpty()) return localLabel;
        if (!localRoot.isEmpty() && path.startsWith(localRoot)) {
            String rel = path.substring(localRoot.length());
            if (rel.isEmpty()) return localLabel;
            return localLabel + rel;
        }
        return path;
    }

    private void loadDir(final String path) {
        currentPath = path;
        pathView.setText(displayPath(path));
        emptyView.setVisibility(View.GONE);

        final int requestId = ++loadRequestId;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<FileItem> result = listDir(path);
                    if (result != null) sortItems(result);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 丢弃过期请求的结果，避免快速切换目录时列表与路径不一致
                            if (requestId != loadRequestId || isFinishing()) return;
                            items = result;
                            adapter.notifyDataSetChanged();
                            // 换目录后选中态回到第一行，避免停在上一层的第二个条目
                            listView.setSelection(0);
                            if (result.isEmpty()) {
                                emptyView.setText(R.string.empty_dir);
                                emptyView.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestId != loadRequestId || isFinishing()) return;
                            emptyView.setText("加载失败：" + e.getMessage());
                            emptyView.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 回到主界面：显式拉起 MainActivity 而不是只 finish()。
     * 这样即使主界面在后台被系统回收，也一定是回到「我的服务器」列表，
     * 而不是把整个应用退出掉（v1.28 修复）。
     */
    private void goHome() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Throwable t) {
            LogBus.mark("BROWSE", "goHome failed: " + t);
        }
        finish();
    }

    private void goParent() {
        if (mode == 2) {
            // 本地模式：到卷根就回主界面，不再往上爬到整个文件系统
            if (currentPath == null || currentPath.equals(localRoot)
                    || localRoot.isEmpty() || !currentPath.startsWith(localRoot)) {
                goHome();
                return;
            }
            int cut = currentPath.lastIndexOf('/');
            String parent = cut > 0 ? currentPath.substring(0, cut) : localRoot;
            if (parent.length() < localRoot.length()) parent = localRoot;
            loadDir(parent);
            return;
        }
        if (currentPath == null || currentPath.isEmpty() || !currentPath.contains("/")) {
            goHome();
            return;
        }
        int idx = currentPath.lastIndexOf('/');
        String parent = idx > 0 ? currentPath.substring(0, idx) : "";
        loadDir(parent);
    }

    private void playVideo(FileItem item) {
        try {
            String fileUrl;
            if (mode == 0) {
                fileUrl = smbClient.getFileUrl(item.path);
            } else if (mode == 1) {
                fileUrl = webdavClient.buildFileUrl(item.path);
            } else {
                fileUrl = LocalClient.fileUrl(item.path);
            }
            // 把「当前文件夹里的所有视频」做成播放列表带过去，播放器里可以上一集/下一集/自动连播
            ArrayList<String> urls = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            int index = -1;
            for (FileItem f : items) {
                if (f.isDirectory || !isVideo(f.name)) continue;
                try {
                    String u;
                    if (mode == 0) u = smbClient.getFileUrl(f.path);
                    else if (mode == 1) u = webdavClient.buildFileUrl(f.path);
                    else u = LocalClient.fileUrl(f.path);
                    if (f.path != null && f.path.equals(item.path)) index = urls.size();
                    urls.add(u);
                    names.add(f.name);
                } catch (Exception ignored) {
                }
            }
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("url", fileUrl);
            intent.putExtra("name", item.name);
            intent.putExtra("mode", mode);
            intent.putExtra("user", user);
            intent.putExtra("pass", pass);
            intent.putExtra("folder", currentPath == null ? "" : currentPath);
            intent.putExtra("host", host);
            intent.putExtra("share", share);
            ArrayList<String> subNames = new ArrayList<>();
            for (FileItem f : items) {
                if (f.isDirectory || f.name == null) continue;
                String lower = f.name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".srt") || lower.endsWith(".ass")
                        || lower.endsWith(".ssa") || lower.endsWith(".vtt")) {
                    subNames.add(f.name);
                }
            }
            intent.putExtra("sublist", subNames.toArray(new String[0]));
            intent.putExtra("plist", urls.toArray(new String[0]));
            intent.putExtra("pnames", names.toArray(new String[0]));
            intent.putExtra("pindex", index);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法播放：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void viewImage(FileItem item) {
        Intent intent = new Intent(this, ImageActivity.class);
        intent.putExtra("mode", mode);
        intent.putExtra("host", host);
        intent.putExtra("share", share);
        intent.putExtra("user", user);
        intent.putExtra("pass", pass);
        intent.putExtra("path", item.path);
        intent.putExtra("name", item.name);
        // 同目录图片列表：电视上用左右键翻页
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        int index = -1;
        for (FileItem f : items) {
            if (f.isDirectory || !isImage(f.name)) continue;
            if (f.path != null && f.path.equals(item.path)) index = paths.size();
            paths.add(f.path);
            names.add(f.name);
        }
        intent.putExtra("ilist", paths.toArray(new String[0]));
        intent.putExtra("inames", names.toArray(new String[0]));
        intent.putExtra("iindex", index);
        startActivity(intent);
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi")
                || n.endsWith(".mov") || n.endsWith(".flv") || n.endsWith(".ts")
                || n.endsWith(".m4v") || n.endsWith(".3gp") || n.endsWith(".wmv")
                || n.endsWith(".webm") || n.endsWith(".mpg") || n.endsWith(".mpeg")
                || n.endsWith(".rmvb") || n.endsWith(".rm");
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            searching = false;
            searchKeyword = "";
            loadDir(currentPath);
            return;
        }
        goParent();
    }

    // ---------- 列表适配器 ----------

    private class ItemAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(BrowseActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(12), dp(16), dp(12));
                row.setBackgroundResource(R.drawable.list_item_bg);
                // 不抢焦点，焦点交给 ListView 管理，保证 onItemClick 正常触发
                row.setFocusable(false);
                row.setClickable(false);

                TextView badge = new TextView(BrowseActivity.this);
                badge.setTextSize(11);
                badge.setTextColor(getResources().getColor(R.color.badge_text));
                badge.setGravity(android.view.Gravity.CENTER);
                badge.setSingleLine(true);
                badge.setFocusable(false);
                badge.setClickable(false);

                android.widget.FrameLayout thumbBox = new android.widget.FrameLayout(BrowseActivity.this);
                ImageView thumb = new ImageView(BrowseActivity.this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setVisibility(View.GONE);
                thumb.setFocusable(false);
                thumb.setClickable(false);
                thumbBox.addView(badge, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                thumbBox.addView(thumb, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                LinearLayout textCol = new LinearLayout(BrowseActivity.this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                colLp.leftMargin = dp(14);
                textCol.setFocusable(false);
                textCol.setClickable(false);

                TextView name = new TextView(BrowseActivity.this);
                name.setTextSize(17);
                name.setTextColor(getResources().getColor(R.color.text_primary));
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setFocusable(false);
                name.setClickable(false);

                TextView meta = new TextView(BrowseActivity.this);
                meta.setTextSize(12);
                meta.setTextColor(getResources().getColor(R.color.text_muted));
                meta.setSingleLine(true);
                meta.setPadding(0, dp(3), 0, 0);
                meta.setFocusable(false);
                meta.setClickable(false);

                textCol.addView(name);
                textCol.addView(meta);

                TextView arrow = new TextView(BrowseActivity.this);
                arrow.setText("›");
                arrow.setTextSize(20);
                arrow.setTextColor(getResources().getColor(R.color.text_tertiary));
                arrow.setFocusable(false);
                arrow.setClickable(false);
                LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                arrowLp.leftMargin = dp(10);

                row.addView(thumbBox, new LinearLayout.LayoutParams(dp(72), dp(40)));
                row.addView(textCol, colLp);
                row.addView(arrow, arrowLp);

                holder = new ViewHolder();
                holder.badge = badge;
                holder.thumb = thumb;
                holder.name = name;
                holder.meta = meta;
                holder.arrow = arrow;
                convertView = row;
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            FileItem item = items.get(position);
            if (item.isDirectory) {
                holder.badge.setText("目录");
                holder.badge.setBackgroundResource(R.drawable.badge_dir);
                holder.meta.setText("文件夹");
                holder.arrow.setVisibility(View.VISIBLE);
            } else if (isVideo(item.name)) {
                holder.badge.setText("视频");
                holder.badge.setBackgroundResource(R.drawable.badge_video);
                holder.meta.setText(formatSize(item.size) + timeSuffix(item.modified));
                holder.arrow.setVisibility(View.GONE);
            } else if (isImage(item.name)) {
                holder.badge.setText("图片");
                holder.badge.setBackgroundResource(R.drawable.badge_image);
                holder.meta.setText(formatSize(item.size) + timeSuffix(item.modified));
                holder.arrow.setVisibility(View.GONE);
            } else {
                holder.badge.setText("文件");
                holder.badge.setBackgroundResource(R.drawable.badge_file);
                holder.meta.setText(formatSize(item.size) + timeSuffix(item.modified));
                holder.arrow.setVisibility(View.GONE);
            }
            holder.name.setText(item.name);

            // 缩略图：目录不取；视频取首帧、图片取小图，异步 + 缓存
            final String key = thumbKey(item);
            holder.thumb.setTag(key);
            holder.thumb.setImageBitmap(null);
            holder.thumb.setVisibility(View.GONE);
            holder.badge.setVisibility(View.VISIBLE);
            if (Config.getThumbs(BrowseActivity.this) && !item.isDirectory
                    && (isVideo(item.name) || isImage(item.name))) {
                Bitmap cached = ThumbCache.peek(key);
                if (cached != null) {
                    showThumb(holder, cached);
                } else {
                    ThumbCache.request(key, item, mode, host, share, user, pass,
                            new ThumbCache.Callback() {
                                @Override
                                public void onThumb(String k, final Bitmap bmp) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            // 行可能已被复用，用 tag 确认还是同一项
                                            if (k.equals(holder.thumb.getTag())) {
                                                showThumb(holder, bmp);
                                            }
                                        }
                                    });
                                }
                            });
                }
            }
            return convertView;
        }

        private void showThumb(ViewHolder holder, Bitmap bmp) {
            if (bmp == null) return;
            holder.thumb.setImageBitmap(bmp);
            holder.thumb.setVisibility(View.VISIBLE);
            holder.badge.setVisibility(View.GONE);
        }

        private String timeSuffix(String modified) {
            return (modified != null && !modified.isEmpty()) ? "   " + modified : "";
        }

        private String formatSize(long size) {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / 1024.0 / 1024.0);
            return String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0);
        }
    }

    private static class ViewHolder {
        TextView badge;
        ImageView thumb;
        TextView name;
        TextView meta;
        TextView arrow;
    }

    /** 缩略图缓存键：目录 + 文件名（同一文件在 SMB/WebDAV/本地各不相同） */
    private String thumbKey(FileItem item) {
        return mode + "|" + host + "|" + share + "|" + item.path + "|" + item.size;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
