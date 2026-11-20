package com.fotufilm.xraw;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Fotofilm X RAW v4.0 —— vivo X100 Pro 专用布局（445×989dp / 20:9）
 *
 * 结构（自上而下）：
 *   ① 顶部会话区：状态芯片(连接) + 导入 + ⋯ ；可折叠的文件卡（名称/尺寸/删除）
 *   ② 预览舞台（最大化）：右上浮层 EXIF+直方图（可开关）、右下对比圆钮、底部配方摘要
 *   ③ 底部抽屉（默认收合，展开＝屏高 35% 上限，内部滚动）
 *   ④ 底部 6 Tab：曝光 / 胶片 / 影调 / 白平衡 / 细节 / 画质
 *
 * 交互：点 Tab 展开对应模块，再点同一 Tab 收合；改动任意参数即自动渲染（350ms 防抖）。
 */
public class MainActivity extends Activity implements CameraEngine.Listener {

    static final String APP_VERSION = "4.5.0";

    private static final String ACTION_USB_PERMISSION = "com.fotufilm.xraw.USB_PERMISSION";
    private static final int REQ_RAF = 1001, REQ_RAF_BATCH = 1002, REQ_TREE = 1003, REQ_RECIPE_JSON = 1004, REQ_SRC_TREE = 1005;

    private static final String[] TAB_NAMES = {"曝光", "胶片", "影调", "白平衡", "细节", "画质"};

    // ---- 跨重建保留 ----
    private static CameraEngine engine;
    private static MainActivity instance;
    private static final Recipe recipe = new Recipe();
    private static byte[] lastPreview;
    private static File loadedRaf;

    // ---- 视图 ----
    private LinearLayout root, sessionBar, fileRow, drawer;
    private LinearLayout[] pages = new LinearLayout[6];
    private TextView[] tabLabels = new TextView[6];
    private TextView statusText, fileName, fileMeta, summaryText, exifText, wbReadout, queueText, imageTag;
    private ZoomImageView preview;
    private TextView previewHint;
    private HistogramView histogram;
    private LinearLayout exifPanel;
    private ProgressBar progress;

    private boolean exifVisible = true, compareOriginal = false, suppress = false;
    private boolean compareDirty = false;
    private int activeTab = -1;
    private Bitmap renderedBmp, originalBmp;
    /** 操作前的基准图：导入后**第一次机身渲染**的结果 */
    private static Bitmap beforeBmp;
    private boolean beforeCaptured = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 参数行 ----
    private Ui.ChoiceRow simRow, wbRow, drRow, drpRow, grainRow, grainSizeRow, chromeRow, fxRow, lmoRow, smoothRow;
    private Ui.ChoiceRow sizeRow, qualityRow, fileTypeRow, csRow;
    private Ui.SeekRow tempRow, hiRow, shRow, colorRow, sharpRow, nrRow, clarityRow, expRow;
    private WbColorPadView colorPad;
    private TextView folderText;
    private ScrollView paramScroll;
    private SelectPanel selectPanel;
    private FrameLayout stageBox;
    private ResultsPanel resultsPanel;

    // ================================================================ 生命周期

    @Override protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        instance = this;

        Fuji.UPLOAD_CHANNEL = getSharedPreferences("xraw", MODE_PRIVATE).getInt("uploadChannel", 2);
        if (engine == null) {
            engine = new CameraEngine(getApplicationContext(), forwarder);
            engine.start();
        }

        try {
            setContentView(buildRoot());
            refreshUiFromRecipe();
            registerUsbReceivers();
            if (lastPreview != null) showRendered(lastPreview);
        } catch (Throwable t) {
            showCrash(t);
            return;
        }

        if (engine.isConnected() && engine.session() != null) onSession(engine.session());
        else {
            status("请用 USB-OTG 连接相机，并把相机设为 USB RAW CONV./BACKUP RESTORE");
            tryAutoConnect();
        }
    }

    /** 把崩溃堆栈画在屏幕上，并写入文件，便于用户截图/回传 */
    private void showCrash(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String trace = sw.toString();
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "crash.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(trace.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) { }

        LinearLayout box = Ui.column(this);
        box.setBackgroundColor(0xFF1A1A1A);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 40), Ui.dp(this, 14), Ui.dp(this, 14));
        box.addView(Ui.text(this, "启动失败（请截图发我）", 0xFFE0574D, 15f, true));
        TextView tv = Ui.text(this, trace, 0xFFE6E6E6, 9f, false);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(box);
    }

    @Override protected void onDestroy() {
        instance = null;
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) { }
                getSharedPreferences("xraw", MODE_PRIVATE).edit()
                        .putString("exportTree", tree.toString()).apply();
                if (folderText != null) folderText.setText(exportFolderLabel());
                toast("导出目录已设置");
            }
            return;
        }
        if (req == REQ_SRC_TREE) {
            Uri tree = data.getData();
            if (tree == null) return;
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            getSharedPreferences("xraw", MODE_PRIVATE).edit().putString("srcTree", tree.toString()).apply();
            if (selectPanel != null) selectPanel.loadFolder(tree);
            return;
        }
        if (req == REQ_RECIPE_JSON) {
            Uri u = data.getData();
            if (u == null) return;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                Recipe r = Recipe.fromStudioJson(bos.toString("UTF-8"));
                copyInto(recipe, r);
                refreshUiFromRecipe();
                toast("已导入配方：" + r.name);
                status("已导入配方 " + r.name + " · " + recipe.summaryText());
                if (engine.isConnected() && loadedRaf != null) engine.preview(recipe);
            } catch (Exception e) {
                toast("配方解析失败：" + e.getMessage());
            }
            return;
        }
        if (req == REQ_RAF) {
            if (data.getData() != null) importOne(data.getData());
            return;
        }
        if (req == REQ_RAF_BATCH) {
            final List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            } else uris.add(data.getData());
            status("正在导入 " + uris.size() + " 个文件…");
            new Thread(() -> {
                final List<File> files = new ArrayList<>();
                for (Uri u : uris) { try { files.add(copyToCache(u)); } catch (IOException ignored) { } }
                ui.post(() -> {
                    if (files.isEmpty()) { status("批量导入失败"); return; }
                    status("批量处理 " + files.size() + " 张…");
                    engine.batch(files, recipe);
                });
            }, "import-batch").start();
        }
    }

    // ================================================================ 根布局

    private View buildRoot() {
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        root.addView(buildSessionBar());

        // 预览舞台（最大化）
        FrameLayout stage = new FrameLayout(this);
        stageBox = stage;
        stage.setBackgroundColor(0xFF0F0F0F);

        preview = new ZoomImageView(this);
        preview.setScaleType(ImageView.ScaleType.MATRIX);
        preview.setOnCompareHold(holding -> {
            Bitmap b4 = beforeImage();
            if (holding && b4 != null && !compareOriginal) {
                preview.setImageBitmap(b4);
                if (imageTag != null) imageTag.setText("操作前");
            } else if (!holding) {
                applyDisplay();
            }
        });
        stage.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewHint = Ui.text(this, "尚无预览\n\n① 连接相机 → ② 导入一张本机拍的 RAW → ③ 调整参数",
                Ui.TEXT_DIM, 13f, false);
        previewHint.setGravity(Gravity.CENTER);
        stage.addView(previewHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右上：EXIF + 直方图 融合浮层
        exifPanel = Ui.column(this);
        exifPanel.setBackground(Ui.bg(0xDC0C0C0D, 7, Ui.LINE, this));
        exifPanel.setPadding(Ui.dp(this, 9), Ui.dp(this, 7), Ui.dp(this, 9), Ui.dp(this, 6));
        exifText = Ui.text(this, "—", 0xFFDCDCDC, 10.5f, false);
        exifText.setLineSpacing(Ui.dp(this, 2), 1f);
        exifPanel.addView(exifText);
        View sep = new View(this);
        sep.setBackgroundColor(Ui.LINE);
        exifPanel.addView(sep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.7f))));
        histogram = new HistogramView(this);
        exifPanel.addView(histogram, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                Ui.dp(this, 172), ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.TOP | Gravity.END;
        elp.setMargins(0, Ui.dp(this, 10), Ui.dp(this, 10), 0);
        busyDot = Ui.text(this, "● 渲染中", Ui.ACCENT, 10f, true);
        busyDot.setVisibility(View.GONE);
        busyDot.setBackground(Ui.bg(0xCC000000, 4, this));
        busyDot.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.setMargins(Ui.dp(this, 12), Ui.dp(this, 10), 0, 0);
        stage.addView(busyDot, blp);

        exifPanel.setOnClickListener(v -> {
            exifVisible = false;
            exifPanel.setVisibility(View.GONE);
            toast("信息面板已隐藏，点顶栏 ▥ 可恢复");
        });
        stage.addView(exifPanel, elp);

        // 右下：对比圆钮
        TextView cmp = Ui.text(this, "◐", Ui.TEXT, 15f, true);
        cmp.setGravity(Gravity.CENTER);
        cmp.setBackground(Ui.bg(0xCC121212, 19, 0xFF4A4A4A, this));
        cmp.setOnClickListener(v -> {
            if (beforeImage() == null) { toast("还没有基准图：等第一张预览出来后再对比"); return; }
            compareOriginal = !compareOriginal;
            applyDisplay();
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        clp.gravity = Gravity.BOTTOM | Gravity.END;
        clp.setMargins(0, 0, Ui.dp(this, 12), Ui.dp(this, 12));
        stage.addView(cmp, clp);

        // 底部：配方摘要 + 图像标识
        summaryText = Ui.text(this, "", 0xFFC9C9C9, 11f, false);
        summaryText.setBackground(Ui.bg(0x6B000000, 4, this));
        summaryText.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.START;
        slp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 60), Ui.dp(this, 14));
        stage.addView(summaryText, slp);

        imageTag = Ui.text(this, "", Ui.TEXT_DIM, 9.5f, false);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.BOTTOM | Gravity.END;
        ilp.setMargins(0, 0, Ui.dp(this, 60), Ui.dp(this, 4));
        stage.addView(imageTag, ilp);

        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildDrawer());
        root.addView(buildTabBar());
        return root;
    }

    // ---------------------------------------------------------------- 顶部会话区

    private View buildSessionBar() {
        sessionBar = Ui.column(this);
        sessionBar.setBackgroundColor(0xFF1B1B1B);

        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        LinearLayout titleCol = Ui.column(this);
        titleCol.addView(Ui.text(this, "X RAW v" + APP_VERSION, Ui.TEXT, 12f, true));
        statusText = Ui.text(this, "未连接", Ui.TEXT_DIM, 10f, false);
        statusText.setMaxLines(2);
        titleCol.addView(statusText);
        row.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        android.widget.Button connect = Ui.button(this, "连接", true);
        connect.setOnClickListener(v -> tryAutoConnect());
        row.addView(connect);

        android.widget.Button imp = Ui.button(this, "导入", false);
        imp.setOnClickListener(v -> pickFile(REQ_RAF));
        row.addView(imp);

        android.widget.Button expTop = Ui.button(this, "导出", false);
        expTop.setOnClickListener(v -> doExport());
        row.addView(expTop);

        android.widget.Button more = Ui.button(this, "⋯", false);
        more.setOnClickListener(v -> showMoreMenu());
        row.addView(more);

        TextView fold = Ui.text(this, "▾", Ui.TEXT_DIM, 13f, false);
        fold.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        row.addView(fold);
        sessionBar.addView(row);

        // 文件卡（可折叠）
        fileRow = Ui.row(this);
        fileRow.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 8));
        fileRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));
        LinearLayout meta = Ui.column(this);
        fileName = Ui.text(this, "未导入 RAF", 0xFFE0E0E0, 12f, false);
        fileMeta = Ui.text(this, "连接相机后点「导入」", Ui.TEXT_DIM, 10f, false);
        meta.addView(fileName);
        meta.addView(fileMeta);
        fileRow.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = Ui.text(this, "✕", 0xFFC0726B, 13f, false);
        del.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        del.setOnClickListener(v -> clearRaf());
        fileRow.addView(del);
        sessionBar.addView(fileRow);

        fold.setOnClickListener(v -> {
            boolean show = fileRow.getVisibility() != View.VISIBLE;
            fileRow.setVisibility(show ? View.VISIBLE : View.GONE);
            fold.setText(show ? "▾" : "▸");
        });
        return sessionBar;
    }

    private void clearRaf() {
        loadedRaf = null;
        fileName.setText("未导入 RAF");
        fileMeta.setText("连接相机后点「导入」");
        originalBmp = null;
        renderedBmp = null;
        beforeBmp = null;
        beforeCaptured = false;
        lastPreview = null;
        preview.setImageDrawable(null);
        previewHint.setVisibility(View.VISIBLE);
        summaryText.setText("");
        if (engine != null) engine.disconnect();
        status("已清除当前 RAF");
    }

    // ---------------------------------------------------------------- 抽屉 + Tab

    private int drawerHeightPx() {
        return (int) (getResources().getDisplayMetrics().heightPixels * 0.35f);
    }

    private View buildDrawer() {
        drawer = Ui.column(this);
        drawer.setBackgroundColor(Ui.PANEL2);
        drawer.setVisibility(View.GONE);
        drawer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));

        LinearLayout info = Ui.row(this);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));
        info.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        queueText = Ui.text(this, "队列空闲", Ui.TEXT_DIM, 10.5f, false);
        info.addView(queueText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        info.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 12)));
        drawer.addView(info);

        // 6 个页面一次性建好（必须先初始化，之前漏了这一步导致启动 NPE 闪退）
        for (int i = 0; i < 6; i++) {
            pages[i] = Ui.column(this);
            pages[i].setBackgroundColor(Ui.PANEL);
            pages[i].setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        }
        buildPages();

        paramScroll = new ScrollView(this);
        paramScroll.setBackgroundColor(Ui.PANEL);
        paramScroll.setFillViewport(true);
        drawer.addView(paramScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return drawer;
    }

    private View buildTabBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(0xFF1B1B1B);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            TextView t = Ui.text(this, TAB_NAMES[i], Ui.TEXT_DIM, 11f, false);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            t.setOnClickListener(v -> toggleDrawer(idx));
            tabLabels[i] = t;
            bar.addView(t);
        }
        return bar;
    }

    private void toggleDrawer(int idx) {
        boolean collapse = (drawer.getVisibility() == View.VISIBLE && activeTab == idx);
        if (collapse) {
            drawer.setVisibility(View.GONE);
            drawer.getLayoutParams().height = 0;
            activeTab = -1;
        } else {
            drawer.setVisibility(View.VISIBLE);
            drawer.getLayoutParams().height = drawerHeightPx();
            activeTab = idx;
            if (paramScroll != null && pages[idx] != null) {
                if (pages[idx].getParent() != null) {
                    ((ViewGroup) pages[idx].getParent()).removeView(pages[idx]);
                }
                paramScroll.removeAllViews();
                paramScroll.addView(pages[idx], new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                paramScroll.scrollTo(0, 0);
            }
        }
        drawer.requestLayout();
        if (root != null) root.requestLayout();
        for (int i = 0; i < 6; i++) {
            if (tabLabels[i] == null) continue;
            boolean on = (i == activeTab);
            tabLabels[i].setTextColor(on ? 0xFFFFFFFF : Ui.TEXT_DIM);
            tabLabels[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ================================================================ 6 个页面

    private void buildPages() {
        pageExposure(pages[0]);
        pageFilm(pages[1]);
        pageTone(pages[2]);
        pageWb(pages[3]);
        pageDetail(pages[4]);
        pageOutput(pages[5]);
    }

    private void pageExposure(LinearLayout c) {
        c.addView(Ui.section(this, "曝光补偿"));
        expRow = new Ui.SeekRow(this, "曝光补偿 (1/3 档，±5EV)", -15, 15, recipe.expBiasThirds, " EV", true,
                v -> { recipe.expBiasThirds = v; schedulePreview(); }, this::schedulePreview);
        expRow.scale(3);                      // 线上单位=1/3EV，显示成 +1.0 EV
        c.addView(expRow);

        c.addView(Ui.section(this, "动态范围"));
        drRow = new Ui.ChoiceRow(this, "动态范围", new String[]{"DR100", "DR200", "DR400"},
                recipe.dr == 200 ? 1 : recipe.dr == 400 ? 2 : 0, i -> {
                    recipe.dr = i == 1 ? 200 : i == 2 ? 400 : 100;
                    schedulePreview();
                });
        c.addView(drRow);
        drpRow = new Ui.ChoiceRow(this, "动态范围优先", new String[]{"关闭", "弱", "强"},
                Math.max(0, Math.min(2, recipe.drPriority)), i -> {
                    recipe.drPriority = i;
                    schedulePreview();
                });
        c.addView(drpRow);
        TextView hint = Ui.text(this, "注：「动态范围优先」需在相机内设置；DR 不能高于原片",

                Ui.TEXT_DIM, 10f, false);
        hint.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 8));
        c.addView(hint);
        c.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 16)));
    }

    private void pageFilm(LinearLayout c) {
        c.addView(Ui.section(this, "胶片模拟"));
        simRow = new Ui.ChoiceRow(this, "胶片模拟", D185.SIM_NAMES,
                indexOf(D185.SIM_VALUES, recipe.filmSim), i -> {
                    recipe.filmSim = D185.SIM_VALUES[i];
                    schedulePreview();
                });
        c.addView(simRow);

        c.addView(Ui.section(this, "颗粒"));
        grainRow = new Ui.ChoiceRow(this, "颗粒效果", new String[]{"关闭", "弱", "强"},
                Math.max(0, Math.min(2, recipe.grainStrength)), i -> {
                    recipe.grainStrength = i;
                    schedulePreview();
                });
        c.addView(grainRow);
        grainSizeRow = new Ui.ChoiceRow(this, "颗粒大小", new String[]{"小", "大"},
                recipe.grainLarge ? 1 : 0, i -> { recipe.grainLarge = i == 1; schedulePreview(); });
        c.addView(grainSizeRow);

        c.addView(Ui.section(this, "色彩效果"));
        chromeRow = new Ui.ChoiceRow(this, "彩色铬效应", new String[]{"关闭", "弱", "强"},
                Math.max(0, Math.min(2, recipe.chrome)), i -> { recipe.chrome = i; schedulePreview(); });
        c.addView(chromeRow);
        fxRow = new Ui.ChoiceRow(this, "彩色 FX 蓝色", new String[]{"关闭", "弱", "强"},
                Math.max(0, Math.min(2, recipe.fxBlue)), i -> { recipe.fxBlue = i; schedulePreview(); });
        c.addView(fxRow);

        c.addView(Ui.section(this, "肤色优化（平滑皮肤）"));
        smoothRow = new Ui.ChoiceRow(this, "肤色优化", new String[]{"关闭", "弱", "强"},
                Math.max(0, Math.min(2, recipe.smoothSkin)), i -> {
                    recipe.smoothSkin = i;
                    schedulePreview();
                });
        c.addView(smoothRow);
        c.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 16)));
    }

    private void pageTone(LinearLayout c) {
        c.addView(Ui.section(this, "色调曲线（0.5 档：-2.0 ~ +4.0）"));
        hiRow = new Ui.SeekRow(this, "高光色调", -20, 40, recipe.highlight, "", true,
                v -> { recipe.highlight = v; schedulePreview(); }, this::schedulePreview);
        hiRow.scale(10);
        hiRow.setRange(-20, 40, 5, true, recipe.highlight);
        c.addView(hiRow);
        shRow = new Ui.SeekRow(this, "阴影色调", -20, 40, recipe.shadow, "", true,
                v -> { recipe.shadow = v; schedulePreview(); }, this::schedulePreview);
        shRow.scale(10);
        shRow.setRange(-20, 40, 5, true, recipe.shadow);
        c.addView(shRow);

        c.addView(Ui.section(this, "色彩与清晰度"));
        colorRow = new Ui.SeekRow(this, "色彩", -4, 4, recipe.color, "", false,
                v -> { recipe.color = v; schedulePreview(); }, this::schedulePreview);
        c.addView(colorRow);
        clarityRow = new Ui.SeekRow(this, "清晰度", -5, 5, recipe.clarity, "", false,
                v -> { recipe.clarity = v; schedulePreview(); }, this::schedulePreview);
        c.addView(clarityRow);
        c.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 16)));
    }

    private void pageWb(LinearLayout c) {
        c.addView(Ui.section(this, "白平衡"));
        wbRow = new Ui.ChoiceRow(this, "白平衡模式", D185.WB_NAMES, recipe.wbIndex, i -> {
            recipe.wbIndex = i;
            updateWbReadout();
            schedulePreview();
        });
        c.addView(wbRow);

        tempRow = new Ui.SeekRow(this, "色温 (K)", 2500, 10000, Math.max(2500, recipe.wbTemp), "K", false,
                v -> { recipe.wbTemp = v; recipe.wbTempTouched = true; schedulePreview(); },
                this::schedulePreview);
        c.addView(tempRow);

        wbReadout = Ui.text(this, "", Ui.ACCENT, 12.5f, true);
        wbReadout.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), 0);
        c.addView(wbReadout);

        colorPad = new WbColorPadView(this);
        int size = (int) (getResources().getDisplayMetrics().widthPixels * 0.52f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(size, size);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = Ui.dp(this, 4);
        colorPad.setLayoutParams(plp);
        colorPad.setValue(recipe.wbR, recipe.wbB);
        colorPad.setListener((r, bb, committed) -> {
            recipe.wbR = r; recipe.wbB = bb;
            updateWbReadout();
            if (committed) schedulePreview();
        });
        c.addView(colorPad);
        updateWbReadout();
        c.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 16)));
    }

    private void pageDetail(LinearLayout c) {
        c.addView(Ui.section(this, "细节"));
        sharpRow = new Ui.SeekRow(this, "锐度", -4, 4, recipe.sharpness, "", false,
                v -> { recipe.sharpness = v; schedulePreview(); }, this::schedulePreview);
        c.addView(sharpRow);
        nrRow = new Ui.SeekRow(this, "降噪", -4, 4, recipe.nr, "", false,
                v -> { recipe.nr = v; schedulePreview(); }, this::schedulePreview);
        c.addView(nrRow);
        clarityRowLite(c);

        c.addView(Ui.section(this, "镜头"));
        lmoRow = new Ui.ChoiceRow(this, "镜头调制优化器 (LMO)", new String[]{"开", "关"},
                recipe.lmo == D185.LMO_OFF ? 1 : 0, i -> {
                    recipe.lmo = i == 0 ? D185.LMO_ON : D185.LMO_OFF;
                    schedulePreview();
                });
        c.addView(lmoRow);
        TextView hint = Ui.text(this, "镜头畸变/暗角/色差由机身自动校正；LMO 补偿衍射与镜头模糊",
                Ui.TEXT_DIM, 10f, false);
        hint.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 8));
        c.addView(hint);
        c.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 16)));
    }

    /** 细节页也放一个清晰度（与影调页联动同一份 recipe 值） */
    private void clarityRowLite(LinearLayout c) {
        Ui.SeekRow row = new Ui.SeekRow(this, "清晰度", -5, 5, recipe.clarity, "", false,
                v -> { recipe.clarity = v; schedulePreview(); }, this::schedulePreview);
        c.addView(row);
    }

    private void pageOutput(LinearLayout c) {
        c.addView(Ui.section(this, "输出（机身原生）"));
        fileTypeRow = new Ui.ChoiceRow(this, "文件类型", Recipe.FILETYPE_NAMES, recipe.fileTypeIdx, i -> {
            recipe.fileTypeIdx = i;
            schedulePreview();
        });
        c.addView(fileTypeRow);
        sizeRow = new Ui.ChoiceRow(this, "图像尺寸", Recipe.SIZE_NAMES, recipe.imageSizeIdx, i -> {
            recipe.imageSizeIdx = i;
            schedulePreview();
        });
        c.addView(sizeRow);
        qualityRow = new Ui.ChoiceRow(this, "图像质量", Recipe.QUALITY_NAMES, recipe.qualityIdx, i -> {
            recipe.qualityIdx = i;
            schedulePreview();
        });
        c.addView(qualityRow);
        csRow = new Ui.ChoiceRow(this, "色彩空间", Recipe.CSPACE_NAMES, recipe.colorSpaceIdx, i -> {
            recipe.colorSpaceIdx = i;
            schedulePreview();
        });
        c.addView(csRow);

        c.addView(Ui.section(this, "导出"));
        folderText = Ui.text(this, exportFolderLabel(), Ui.TEXT_DIM, 10.5f, false);
        folderText.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 2));
        c.addView(folderText);

        LinearLayout btnRow = Ui.row(this);
        btnRow.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        android.widget.Button pick = Ui.button(this, "选择文件夹", false);
        pick.setOnClickListener(v -> pickExportFolder());
        btnRow.addView(pick);
        android.widget.Button saveR = Ui.button(this, "存为配方", false);
        saveR.setOnClickListener(v -> saveRecipeDialog());
        btnRow.addView(saveR);
        android.widget.Button loadR = Ui.button(this, "配方库", false);
        loadR.setOnClickListener(v -> loadRecipeDialog());
        btnRow.addView(loadR);
        c.addView(btnRow);

        LinearLayout btnRow2 = Ui.row(this);
        btnRow2.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 6));
        android.widget.Button impR = Ui.button(this, "导入配方 JSON", false);
        impR.setOnClickListener(v -> pickRecipeJson());
        btnRow2.addView(impR);
        android.widget.Button expR = Ui.button(this, "导出配方 JSON", false);
        expR.setOnClickListener(v -> shareRecipeJson());
        btnRow2.addView(expR);
        c.addView(btnRow2);

        c.addView(Ui.section(this, "相机槽位（配方工坊）"));
        LinearLayout slotRow = Ui.row(this);
        slotRow.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 10));
        android.widget.Button read = Ui.button(this, "读取 C1–C4", false);
        read.setOnClickListener(v -> {
            if (!engine.isConnected()) { toast("请先连接相机"); return; }
            status("读取相机槽位…");
            engine.readSlots();
        });
        slotRow.addView(read);
        android.widget.Button write = Ui.button(this, "写入 C1", true);
        write.setOnClickListener(v -> {
            if (!engine.isConnected()) { toast("请先连接相机"); return; }
            engine.pushSlot(1, recipe, recipe.name);
        });
        slotRow.addView(write);
        c.addView(slotRow);

        c.addView(Ui.section(this, "导出全图"));
        android.widget.Button exp = Ui.button(this, "开始导出", true);
        exp.setOnClickListener(v -> {
            if (!engine.isConnected()) { toast("请先连接相机"); return; }
            if (loadedRaf == null) { toast("请先导入并上传一张 RAF"); return; }
            status("导出中：" + recipe.summaryText());
            engine.export(recipe);
        });
        c.addView(exp);
        c.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 20)));
    }

    // ================================================================ 参数 → UI 同步

    private void refreshUiFromRecipe() {
        suppress = true;
        if (simRow != null) simRow.setIndex(indexOf(D185.SIM_VALUES, recipe.filmSim));
        if (drRow != null) drRow.setIndex(recipe.dr == 200 ? 1 : recipe.dr == 400 ? 2 : 0);
        if (drpRow != null) drpRow.setIndex(Math.max(0, Math.min(2, recipe.drPriority)));
        if (grainRow != null) grainRow.setIndex(Math.max(0, Math.min(2, recipe.grainStrength)));
        if (grainSizeRow != null) grainSizeRow.setIndex(recipe.grainLarge ? 1 : 0);
        if (chromeRow != null) chromeRow.setIndex(Math.max(0, Math.min(2, recipe.chrome)));
        if (fxRow != null) fxRow.setIndex(Math.max(0, Math.min(2, recipe.fxBlue)));
        if (smoothRow != null) smoothRow.setIndex(Math.max(0, Math.min(2, recipe.smoothSkin)));
        if (wbRow != null) wbRow.setIndex(Math.max(0, recipe.wbIndex));
        if (lmoRow != null) lmoRow.setIndex(recipe.lmo == D185.LMO_OFF ? 1 : 0);
        if (fileTypeRow != null) fileTypeRow.setIndex(recipe.fileTypeIdx);
        if (sizeRow != null) sizeRow.setIndex(recipe.imageSizeIdx);
        if (qualityRow != null) qualityRow.setIndex(recipe.qualityIdx);
        if (csRow != null) csRow.setIndex(recipe.colorSpaceIdx);

        if (tempRow != null) tempRow.setValue(Math.max(2500, recipe.wbTemp), 2500);
        if (hiRow != null) hiRow.setValue(recipe.highlight, -20);
        if (shRow != null) shRow.setValue(recipe.shadow, -20);
        if (colorRow != null) colorRow.setValue(recipe.color, -4);
        if (sharpRow != null) sharpRow.setValue(recipe.sharpness, -4);
        if (nrRow != null) nrRow.setValue(recipe.nr, -4);
        if (clarityRow != null) clarityRow.setValue(recipe.clarity, -5);
        if (expRow != null) expRow.setValue(recipe.expBiasThirds, -15);
        if (colorPad != null) { colorPad.setValue(recipe.wbR, recipe.wbB); updateWbReadout(); }
        suppress = false;
    }

    private void updateWbReadout() {
        if (wbReadout == null) return;
        int r = recipe.wbR, b = recipe.wbB;
        String hint;
        if (r == 0 && b == 0) hint = "中性";
        else if (r > 0 && b > 0) hint = "偏洋红（M）· 暖";
        else if (r > 0 && b < 0) hint = "偏红 · 暖";
        else if (r < 0 && b > 0) hint = "偏青 · 冷";
        else hint = "偏绿（G）· 冷";
        boolean asShot = recipe.wbIndex == D185.WB_VALUES.length - 1;
        wbReadout.setText(String.format(java.util.Locale.US, "%s   R %+d  B %+d   %s",
                asShot ? "按拍摄时" : "自定义", r, b, hint));
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    // ================================================================ 渲染调度

    private final Runnable previewTick = () -> {
        if (engine != null && engine.isConnected() && loadedRaf != null) {
            startBusy();
            status("⟳ 机身渲染中…");
            engine.preview(recipe);
        }
    };

    private long renderStartAt = 0;
    private TextView busyDot;

    private void schedulePreview() {
        if (suppress) return;
        compareDirty = true;
        ui.removeCallbacks(previewTick);
        ui.postDelayed(previewTick, 350);
        status("… 参数已改，等待渲染");
    }

    private void startBusy() {
        renderStartAt = android.os.SystemClock.elapsedRealtime();
        if (busyDot != null) busyDot.setVisibility(View.VISIBLE);
    }

    private void stopBusy() {
        if (busyDot != null) busyDot.setVisibility(View.GONE);
    }

    // ================================================================ 显示

    private void showRendered(byte[] jpeg) {
        Bitmap bm = decodeOriented(jpeg);
        if (bm == null) return;
        renderedBmp = bm;
        applyDisplay();
        if (exifText != null) exifText.setText(exifLine(jpeg));
    }

    private void setOriginal(byte[] jpeg) {
        Bitmap bm = decodeOriented(jpeg);
        if (bm == null) return;
        originalBmp = bm;
        if (renderedBmp == null) applyDisplay();
    }

    /** 取「操作前」基准：优先用导入后第一张渲染，其次才是内嵌 JPEG */
    private Bitmap beforeImage() {
        if (beforeBmp != null) return beforeBmp;
        return originalBmp;
    }

    private void applyDisplay() {
        Bitmap b4 = beforeImage();
        Bitmap show = (compareOriginal && b4 != null) ? b4 : renderedBmp;
        if (show == null) show = b4;
        if (show == null) return;
        preview.setImageBitmap(show);
        previewHint.setVisibility(View.GONE);
        if (histogram != null && exifVisible) histogram.setBitmap(show);
        if (imageTag != null) imageTag.setText(show == b4 && b4 != null ? "操作前" : "机身渲染");
    }

    private Bitmap decodeOriented(byte[] data) {
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bm == null) return null;
        int deg = 0;
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(data)) {
            android.media.ExifInterface ex = new android.media.ExifInterface(bis);
            int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) { }
        if (deg == 0) return bm;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
    }

    private String exifLine(byte[] jpeg) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(
                    new java.io.ByteArrayInputStream(jpeg));
            String f = ex.getAttribute(android.media.ExifInterface.TAG_F_NUMBER);
            String t = ex.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME);
            String iso = ex.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS);
            StringBuilder sb = new StringBuilder();
            sb.append(f != null ? "f/" + f : "f/—").append("  ");
            sb.append(t != null ? t + "s" : "—").append("  ISO ").append(iso != null ? iso : "—");
            return sb.toString();
        } catch (Exception e) {
            return "EXIF 读取失败";
        }
    }

    // ================================================================ USB

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (granted && dev != null) engine.connect(dev);
                else status("USB 权限被拒绝");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                engine.disconnect();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                tryAutoConnect();
            }
        }
    };

    private void registerUsbReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
    }

    private UsbDevice findCamera() {
        UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() != Ptp.VID_FUJIFILM) continue;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                if (d.getInterface(i).getInterfaceClass() == Ptp.USB_CLASS_STILL_IMAGE) return d;
            }
        }
        return null;
    }

    private void tryAutoConnect() {
        UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usb == null) return;
        UsbDevice dev = findCamera();
        if (dev == null) {
            status("未发现相机：请确认①数据线②相机已开机③CONNECTION MODE = USB RAW CONV./BACKUP RESTORE");
            return;
        }
        if (usb.hasPermission(dev)) { engine.connect(dev); return; }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        usb.requestPermission(dev, pi);
    }

    // ================================================================ 导入 / 文件

    private void pickFile(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (req == REQ_RAF_BATCH) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, req); } catch (Exception e) { toast("没有可用的文件选择器"); }
    }

    private void importOne(Uri uri) {
        status("导入中…");
        new Thread(() -> {
            try {
                final File f = copyToCache(uri);
                ui.post(() -> {
                    loadedRaf = f;
                    fileName.setText(f.getName());
                    fileMeta.setText((f.length() / 1048576) + " MB · 上传中…");
                    beforeBmp = null;
                    beforeCaptured = false;
                    compareDirty = false;
                    try {
                        Raf raf = Raf.inspect(f);
                        byte[] thumb = raf.embeddedJpeg(f);
                        if (thumb != null) setOriginal(thumb);
                        fileMeta.setText(raf.model + " · " + (f.length() / 1048576) + " MB");
                    } catch (IOException ignored) { }
                    engine.upload(f);
                });
            } catch (IOException e) {
                ui.post(() -> status("复制文件失败：" + e.getMessage()));
            }
        }, "import").start();
    }

    private File copyToCache(Uri uri) throws IOException {
        String name = "import.raf";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) name = c.getString(idx);
            }
        }
        File out = new File(getCacheDir(), name.replaceAll("[^\\p{L}\\p{N}._\\-]", "_"));
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开文件流");
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            fos.close();
        }
        return out;
    }

    private String exportFolderLabel() {
        String t = getSharedPreferences("xraw", MODE_PRIVATE).getString("exportTree", "");
        return (t == null || t.isEmpty()) ? "导出到：默认（相册 / 下载 FotofilmXRAW）" : "导出到：已选文件夹";
    }

    private void pickExportFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_TREE); } catch (Exception e) { toast("系统无文件夹选择器"); }
    }

    // ================================================================ 菜单 / 对话框

    /** 选片页：覆盖预览区，缩略图网格 + 多选 + 批量渲染 */
    private void showSelectPanel() {
        if (selectPanel == null) {
            selectPanel = new SelectPanel(this, getContentResolver());
            selectPanel.setCallback(new SelectPanel.Callback() {
                @Override public void onPickFolder() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try { startActivityForResult(i, REQ_SRC_TREE); } catch (Exception e) { toast("系统无文件夹选择器"); }
                }

                @Override public void onRender(java.util.List<Uri> uris) {
                    if (!engine.isConnected()) { toast("请先连接相机"); return; }
                    if (uris.isEmpty()) { toast("请先勾选要渲染的照片"); return; }
                    status("准备批量渲染 " + uris.size() + " 张…");
                    new Thread(() -> {
                        final List<File> files = new ArrayList<>();
                        for (Uri u : uris) {
                            try { files.add(copyToCache(u)); } catch (IOException ignored) { }
                        }
                        ui.post(() -> {
                            if (files.isEmpty()) { status("读取文件失败"); return; }
                            status("批量渲染 " + files.size() + " 张 · 配方 " + recipe.name);
                            engine.batch(files, recipe);
                        });
                    }, "batch-copy").start();
                }
            });
            stageBox.addView(selectPanel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            String tree = getSharedPreferences("xraw", MODE_PRIVATE).getString("srcTree", "");
            if (tree != null && !tree.isEmpty()) selectPanel.loadFolder(Uri.parse(tree));
        }
        boolean show = selectPanel.getVisibility() != View.VISIBLE;
        selectPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            String tree = getSharedPreferences("xraw", MODE_PRIVATE).getString("srcTree", "");
            if (tree != null && !tree.isEmpty()) {
                Uri t = Uri.parse(tree);
                try { getContentResolver().takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
                selectPanel.loadFolder(t);
            } else toast("点「选择文件夹」挑选存有 RAF 的目录");
        }
    }

    private void showResults(List<File> files) {
        if (resultsPanel == null) {
            resultsPanel = new ResultsPanel(this);
            stageBox.addView(resultsPanel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        resultsPanel.setResults(files);
    }

    private void doExport() {
        if (!engine.isConnected()) { toast("请先连接相机"); return; }
        if (loadedRaf == null) { toast("请先导入并上传一张 RAW"); return; }
        startBusy();
        status("⟳ 机身渲染全分辨率中…（TIFF 可能需要 30 秒以上）");
        engine.export(recipe);
    }

    private void showMoreMenu() {
        final String[] items = {
                "选片（文件夹批量）", "批量导入 RAF", "读取相机槽位（C1–C4）", "配方库（本地）",
                "自检与日志", "导出诊断日志"
        };
        new AlertDialog.Builder(this).setTitle("更多").setItems(items, (d, w) -> {
            switch (w) {
                case 0: showSelectPanel(); break;
                case 1: pickFile(REQ_RAF_BATCH); break;
                case 2:
                    if (!engine.isConnected()) { toast("请先连接相机"); return; }
                    engine.readSlots();
                    break;
                case 3: loadRecipeDialog(); break;
                case 4: probeDialog(); break;
                case 5: engine.probeDump(); break;
            }
        }).setNegativeButton("取消", null).show();
    }

    private void saveRecipeDialog() {
        EditText et = new EditText(this);
        et.setText(recipe.name);
        et.setTextColor(Ui.TEXT);
        new AlertDialog.Builder(this).setTitle("保存配方").setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    recipe.name = et.getText().toString().trim();
                    if (recipe.name.isEmpty()) recipe.name = "未命名";
                    try {
                        Recipe.save(this, recipe);
                        status("配方已保存：" + recipe.name);
                    } catch (IOException e) { status("保存失败：" + e.getMessage()); }
                }).setNegativeButton("取消", null).show();
    }

    private void pickRecipeJson() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_RECIPE_JSON); } catch (Exception e) { toast("没有可用的文件选择器"); }
    }

    private void shareRecipeJson() {
        try {
            File f = new File(getCacheDir(), (recipe.name.isEmpty() ? "recipe" : recipe.name) + ".json");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(recipe.toStudioJson().getBytes("UTF-8"));
            fos.close();
            // 工程无 AndroidX：用 MediaSaver 把 JSON 落到下载目录再分享
            MediaSaver.Exported ex = MediaSaver.exportText(this, f.getName(), new String(
                    java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_STREAM, ex.uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "导出配方"));
        } catch (Exception e) {
            // 没有 FileProvider 时退化为直接显示 JSON
            new AlertDialog.Builder(this).setTitle("配方 JSON")
                    .setMessage(recipe.toStudioJson()).setPositiveButton("知道了", null).show();
        }
    }

    private void loadRecipeDialog() {
        final List<Recipe> list = Recipe.list(this);
        if (list.isEmpty()) { toast("配方库是空的，先调好参数点「存为配方」"); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name + " — " + list.get(i).summaryText();
        new AlertDialog.Builder(this).setTitle("配方库").setItems(names, (d, which) -> {
            Recipe r = list.get(which);
            copyInto(recipe, r);
            refreshUiFromRecipe();
            status("已载入配方：" + r.name);
            if (engine.isConnected() && loadedRaf != null) engine.preview(recipe);
        }).setNegativeButton("关闭", null).show();
    }

    private void probeDialog() {
        new AlertDialog.Builder(this).setTitle("自检 / 调试").setItems(new String[]{
                "① 导出诊断日志（probe_dump.txt）",
                "② 读取相机能力（GetDevicePropDesc）",
                "③ 胶片模拟扫描",
                "④ 从相机读回当前参数",
                "⑦ 字段绑定自校准",
                "⑧ d185 参数快照",
                "⑨ 参数生效自检（A/B 双渲染对比）",
                "⑩ 肤色优化字段探测（找出该机型真正生效的索引）"
        }, (d, which) -> {
            if (which == 0) { engine.probeDump(); return; }
            if (!engine.isConnected()) { toast("请先连接相机"); return; }
            switch (which) {
                case 1: engine.probeCaps(); break;
                case 2:
                    if (loadedRaf == null) { toast("请先导入 RAF"); return; }
                    engine.probeSims(recipe);
                    break;
                case 3:
                    if (loadedRaf == null) { toast("请先导入 RAF"); return; }
                    engine.upload(loadedRaf);
                    break;
                case 4:
                    if (loadedRaf == null) { toast("请先导入 RAF"); return; }
                    engine.calibrate();
                    break;
                case 5:
                    if (loadedRaf == null) { toast("请先导入 RAF"); return; }
                    engine.dumpD185();
                    break;
                case 6:
                    if (loadedRaf == null) { toast("请先导入 RAF"); return; }
                    toast("开始自检，约 40 秒");
                    engine.selfTest();
                    break;
                default:
                    if (loadedRaf == null) { toast("请先导入 RAF"); return; }
                    toast("探测中，约 20 秒");
                    engine.probeSkinTone();
                    break;
            }
        }).setNegativeButton("关闭", null).show();
    }

    private static void copyInto(Recipe dst, Recipe src) {
        dst.name = src.name;
        dst.filmSim = src.filmSim; dst.dr = src.dr; dst.drPriority = src.drPriority;
        dst.grainStrength = src.grainStrength; dst.grainLarge = src.grainLarge;
        dst.chrome = src.chrome; dst.fxBlue = src.fxBlue;
        dst.wbIndex = src.wbIndex; dst.wbTemp = src.wbTemp; dst.wbR = src.wbR; dst.wbB = src.wbB;
        dst.highlight = src.highlight; dst.shadow = src.shadow;
        dst.color = src.color; dst.sharpness = src.sharpness;
        dst.nr = src.nr; dst.clarity = src.clarity; dst.lmo = src.lmo;
        dst.imageSizeIdx = src.imageSizeIdx; dst.qualityIdx = src.qualityIdx;
        dst.fileTypeIdx = src.fileTypeIdx; dst.colorSpaceIdx = src.colorSpaceIdx;
        dst.expBiasThirds = src.expBiasThirds;
        dst.smoothSkin = src.smoothSkin;
    }

    // ================================================================ 引擎回调

    @Override public void onStatus(String msg) { status(msg); }

    @Override public void onSession(Fuji.Session s) {
        status("已连接 " + s.title());
    }

    @Override public void onCaps(java.util.Map<Integer, PropDesc> c) {
        if (c.size() > 0) toast("已读取 " + c.size() + " 条相机能力");
    }

    @Override public void onBaseProfile(D185 base, Recipe fromCam) {
        copyInto(recipe, fromCam);
        refreshUiFromRecipe();
        status("机内原始：" + fromCam.summaryText());
        summaryText.setText(fromCam.summaryText());
        engine.preview(recipe);
    }

    @Override public void onPreview(byte[] jpeg, String summary) {
        lastPreview = jpeg;
        showRendered(jpeg);
        // 第一次渲染结果 = 「操作前」基准（用户随后改参数都算「操作后」）
        if (!beforeCaptured && !compareDirty) {
            beforeBmp = renderedBmp;
            beforeCaptured = true;
            if (imageTag != null) imageTag.setText("基准已记录");
        }
        long ms = renderStartAt > 0 ? (android.os.SystemClock.elapsedRealtime() - renderStartAt) : 0;
        renderStartAt = 0;
        String tail = " · " + jpeg.length / 1024 + "KB" + (ms > 0 ? " · " + ms + "ms" : "");
        summaryText.setText(summary + tail);
        status("✔ 已应用" + tail);
        stopBusy();
    }

    @Override public void onExport(File file, String summary) {
        stopBusy();
        progress.setVisibility(View.GONE);
        status("已保存 " + file.getName());
        toast("导出完成：" + file.getName());
    }

    @Override public void onProgress(String what, long done, long total) {
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(total <= 0 ? 0 : (int) (done * 1000 / total));
        queueText.setText(what + "  " + (done / 1048576) + "/" + (total / 1048576) + " MB");
    }

    @Override public void onError(String msg) {
        stopBusy();
        progress.setVisibility(View.GONE);
        status("⚠ " + msg);
        toast(msg);
    }

    @Override public void onQueueChanged(List<String> items, int active) {
        if (queueText == null) return;
        queueText.setText(items.isEmpty()
                ? ("队列空闲" + (loadedRaf != null ? " · 已装载 " + loadedRaf.getName() : ""))
                : ("待处理 " + items.size() + " 项"));
    }

    @Override public void onDisconnected() { status("已断开"); }

    @Override public void onProbeDone(String path) {
        new AlertDialog.Builder(this).setTitle("已导出")
                .setMessage("位置：" + path)
                .setPositiveButton("分享", (d, w) -> shareLastLog())
                .setNegativeButton("知道了", null).show();
    }

    @Override public void onSlots(List<SlotData> slots) {
        StringBuilder sb = new StringBuilder();
        for (SlotData d : slots) {
            sb.append("C").append(d.slot).append("  ");
            if (d.missing.containsKey(-1)) sb.append("读取失败");
            else sb.append(d.summary());
            sb.append('\n');
        }
        new AlertDialog.Builder(this).setTitle("相机槽位（已回读）")
                .setMessage(sb.toString())
                .setPositiveButton("把当前配方写入 C1", (x, w) -> engine.pushSlot(1, recipe, recipe.name))
                .setNeutralButton("写入 C2", (x, w) -> engine.pushSlot(2, recipe, recipe.name))
                .setNegativeButton("关闭", null).show();
    }

    @Override public void onBatchDone(List<File> files) {
        stopBusy();
        status("批量完成：" + files.size() + " 张已导出");
        if (!files.isEmpty()) showResults(files);
    }

    @Override public void onSlotWritten(String summary) {
        status("槽位写入：" + summary);
        toast(summary);
    }

    private void shareLastLog() {
        Uri u = engine != null ? engine.lastLogUri() : null;
        if (u == null) { toast("日志在：" + (engine != null ? engine.lastLogPath() : "?")); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "分享日志")); } catch (Exception e) { toast("无分享目标"); }
    }

    private void status(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private static final CameraEngine.Listener forwarder = new CameraEngine.Listener() {
        private MainActivity cur() { return instance; }
        @Override public void onStatus(String m) { MainActivity a = cur(); if (a != null) a.onStatus(m); }
        @Override public void onSession(Fuji.Session s) { MainActivity a = cur(); if (a != null) a.onSession(s); }
        @Override public void onCaps(java.util.Map<Integer, PropDesc> c) { MainActivity a = cur(); if (a != null) a.onCaps(c); }
        @Override public void onBaseProfile(D185 b, Recipe r) { MainActivity a = cur(); if (a != null) a.onBaseProfile(b, r); }
        @Override public void onPreview(byte[] j, String s) { MainActivity a = cur(); if (a != null) a.onPreview(j, s); }
        @Override public void onExport(File f, String s) { MainActivity a = cur(); if (a != null) a.onExport(f, s); }
        @Override public void onProgress(String w, long d, long t) { MainActivity a = cur(); if (a != null) a.onProgress(w, d, t); }
        @Override public void onError(String m) { MainActivity a = cur(); if (a != null) a.onError(m); }
        @Override public void onQueueChanged(List<String> i, int act) { MainActivity a = cur(); if (a != null) a.onQueueChanged(i, act); }
        @Override public void onDisconnected() { MainActivity a = cur(); if (a != null) a.onDisconnected(); }
        @Override public void onProbeDone(String p) { MainActivity a = cur(); if (a != null) a.onProbeDone(p); }
        @Override public void onSlots(List<SlotData> s) { MainActivity a = cur(); if (a != null) a.onSlots(s); }
        @Override public void onSlotWritten(String s) { MainActivity a = cur(); if (a != null) a.onSlotWritten(s); }
        @Override public void onBatchDone(List<File> f) { MainActivity a = cur(); if (a != null) a.onBatchDone(f); }
    };
}
