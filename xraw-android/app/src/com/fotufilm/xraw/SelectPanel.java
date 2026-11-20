package com.fotufilm.xraw;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 选片页（对标 fuji-studio 的 SelectPage）：文件夹里列出全部 RAF，
 * 缩略图网格 + 多选勾选 + 批量渲染。
 */
public final class SelectPanel extends LinearLayout {

    public static final class Item {
        final Uri uri;
        final String name;
        boolean selected;
        Bitmap thumb;
        boolean loading;
        Item(Uri u, String n) { uri = u; name = n; }
    }

    public interface Callback {
        void onPickFolder();
        void onRender(List<Uri> uris);
    }

    private final List<Item> items = new ArrayList<>();
    private final Set<Integer> chosen = new HashSet<>();
    private GridAdapter adapter;
    private final TextView info;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ContentResolver cr;
    private Callback cb;

    public SelectPanel(Context c, ContentResolver cr) {
        super(c);
        this.cr = cr;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF141414);
        setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));

        adapter = new GridAdapter(c);

        LinearLayout bar = Ui.row(c);
        android.widget.Button pick = Ui.button(c, "选择文件夹", true);
        pick.setOnClickListener(v -> { if (cb != null) cb.onPickFolder(); });
        bar.addView(pick);

        android.widget.Button all = Ui.button(c, "全选", false);
        all.setOnClickListener(v -> {
            chosen.clear();
            for (int i = 0; i < items.size(); i++) chosen.add(i);
            adapter.notifyDataSetChanged();
            updateInfo();
        });
        bar.addView(all);

        android.widget.Button none = Ui.button(c, "清空", false);
        none.setOnClickListener(v -> {
            chosen.clear();
            adapter.notifyDataSetChanged();
            updateInfo();
        });
        bar.addView(none);

        android.widget.Button run = Ui.button(c, "渲染所选", true);
        run.setOnClickListener(v -> {
            if (cb == null) return;
            List<Uri> sel = new ArrayList<>();
            for (int i : chosen) if (i < items.size()) sel.add(items.get(i).uri);
            cb.onRender(sel);
        });
        bar.addView(run);
        addView(bar);

        info = Ui.text(c, "选择一个包含 .RAF 的文件夹", Ui.TEXT_DIM, 10.5f, false);
        info.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 4));
        addView(info);

        GridView grid = new GridView(c);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(Ui.dp(c, 4));
        grid.setVerticalSpacing(Ui.dp(c, 4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), Ui.dp(c, 2));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= items.size()) return;
            Item it = items.get(pos);
            it.selected = !it.selected;
            if (it.selected) chosen.add(pos); else chosen.remove(pos);
            adapter.notifyDataSetChanged();
            updateInfo();
        });
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void setCallback(Callback c) { cb = c; }

    private void updateInfo() {
        if (items.isEmpty()) { info.setText("选择一个包含 .RAF 的文件夹"); return; }
        info.setText("共 " + items.size() + " 张 RAF · 已选 " + chosen.size() + " 张");
    }

    /** 列出目录下的 RAF（通过 DocumentsContract，遍历子项） */
    public void loadFolder(Uri tree) {
        items.clear();
        chosen.clear();
        adapter.notifyDataSetChanged();
        info.setText("正在读取文件夹…");
        new Thread(() -> {
            List<Item> found = new ArrayList<>();
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                try (android.database.Cursor cur = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            String name = cur.getString(1);
                            String mime = cur.getString(2);
                            if (name == null) continue;
                            if (!name.toLowerCase().endsWith(".raf")) continue;
                            if (mime != null && mime.contains("directory")) continue;
                            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                            found.add(new Item(doc, name));
                        }
                    }
                }
            } catch (Exception ignored) { }
            post(() -> {
                items.addAll(found);
                adapter.notifyDataSetChanged();
                updateInfo();
            });
        }, "scan-raf").start();
    }

    private final class GridAdapter extends BaseAdapter {
        private final Context ctx;
        GridAdapter(Context c) { ctx = c; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Cell cell;
            if (convert instanceof Cell) cell = (Cell) convert;
            else cell = new Cell(ctx);
            Item it = items.get(pos);
            cell.bind(it, pos);
            if (it.thumb == null && !it.loading) loadThumb(pos);
            return cell;
        }
    }

    private void loadThumb(int pos) {
        if (pos >= items.size()) return;
        final Item it = items.get(pos);
        it.loading = true;
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                byte[] jpeg = Raf.prefixJpeg(cr, it.uri, 3 * 1024 * 1024);
                if (jpeg != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;
                    bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                }
            } catch (Throwable ignored) { }
            final Bitmap fb = bm;
            post(() -> {
                it.thumb = fb;
                it.loading = false;
                adapter.notifyDataSetChanged();
            });
        });
    }

    private final class Cell extends LinearLayout {
        private final ImageView img;
        private final TextView mark;
        private final TextView label;

        Cell(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundColor(0xFF1E1E1E);
            setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2));

            android.widget.FrameLayout box = new android.widget.FrameLayout(c);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(img, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            mark = Ui.text(c, "", Color.WHITE, 11f, true);
            mark.setGravity(Gravity.CENTER);
            android.widget.FrameLayout.LayoutParams mlp = new android.widget.FrameLayout.LayoutParams(
                    Ui.dp(c, 20), Ui.dp(c, 20));
            mlp.gravity = Gravity.TOP | Gravity.END;
            mlp.setMargins(0, Ui.dp(c, 3), Ui.dp(c, 3), 0);
            box.addView(mark, mlp);

            addView(box, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            label = Ui.text(c, "", Ui.TEXT_DIM, 8.5f, false);
            label.setMaxLines(1);
            label.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), 0);
            addView(label);
        }

        void bind(Item it, int pos) {
            if (it.thumb != null) img.setImageBitmap(it.thumb);
            else img.setImageDrawable(null);
            label.setText(it.name);
            boolean on = it.selected;
            mark.setText(on ? "✓" : "");
            mark.setBackground(on ? Ui.bg(0xFFE8B44A, 9, getContext()) : null);
            mark.setTextColor(on ? 0xFF1A1A1A : Color.WHITE);
            setBackgroundColor(on ? 0xFF2E2E2E : 0xFF1E1E1E);
        }
    }
}
