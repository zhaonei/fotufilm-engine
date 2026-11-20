package com.fotufilm.xraw;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 渲染结果页（对标 fuji-studio 的 RenderPage 结果网格）：缩略图墙 + 点击分享 */
public final class ResultsPanel extends LinearLayout {

    private final List<File> files = new ArrayList<>();
    private final List<Bitmap> thumbs = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final TextView info;
    private Adapter adapter;

    public ResultsPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF141414);
        setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));

        LinearLayout bar = Ui.row(c);
        TextView title = Ui.text(c, "渲染结果", Ui.TEXT, 13f, true);
        bar.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button close = Ui.button(c, "关闭", false);
        close.setOnClickListener(v -> setVisibility(View.GONE));
        bar.addView(close);
        addView(bar);

        info = Ui.text(c, "", Ui.TEXT_DIM, 10.5f, false);
        info.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), 0, Ui.dp(c, 4));
        addView(info);

        adapter = new Adapter(c);
        GridView grid = new GridView(c);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(Ui.dp(c, 4));
        grid.setVerticalSpacing(Ui.dp(c, 4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= files.size()) return;
            share(files.get(pos));
        });
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setVisibility(GONE);
    }

    private void share(File f) {
        try {
            // 工程无 FileProvider：用 ACTION_VIEW 打开（系统相册/文件管理器承接）
            Uri u = Uri.parse("file://" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(getContext(), "文件在 " + f.getAbsolutePath(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    public void setResults(List<File> list) {
        files.clear();
        files.addAll(list);
        thumbs.clear();
        for (int i = 0; i < files.size(); i++) thumbs.add(null);
        info.setText("共 " + files.size() + " 张 · 点击缩略图分享 · 长按查看路径");
        adapter.notifyDataSetChanged();
        for (int i = 0; i < files.size(); i++) loadThumb(i);
        setVisibility(VISIBLE);
    }

    private void loadThumb(int pos) {
        if (pos >= files.size()) return;
        final File f = files.get(pos);
        final int p = pos;
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            } catch (Throwable ignored) { }
            final Bitmap fb = bm;
            post(() -> {
                if (p < thumbs.size()) {
                    thumbs.set(p, fb);
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private final class Adapter extends BaseAdapter {
        private final Context ctx;
        Adapter(Context c) { ctx = c; }
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell;
            if (convert instanceof LinearLayout) cell = (LinearLayout) convert;
            else {
                cell = new LinearLayout(ctx);
                cell.setOrientation(VERTICAL);
                cell.setBackgroundColor(0xFF1E1E1E);
                cell.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2));
                ImageView iv = new ImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setId(1);
                cell.addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                TextView tv = Ui.text(ctx, "", Ui.TEXT_DIM, 8.5f, false);
                tv.setMaxLines(1);
                tv.setId(2);
                cell.addView(tv);
            }
            ImageView iv = cell.findViewById(1);
            TextView tv = cell.findViewById(2);
            Bitmap bm = pos < thumbs.size() ? thumbs.get(pos) : null;
            if (bm != null) iv.setImageBitmap(bm); else iv.setImageDrawable(null);
            tv.setText(files.get(pos).getName());
            return cell;
        }
    }
}
