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
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
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
 * Fotofilm X RAW —— Android 版「X RAW Studio」（针对 Fujifilm X-M5 / X-Processor 5 调校）
 *
 * 单 Activity，全部 UI 由代码构建（本工程不使用 gradle / AndroidX / 资源）。
 * 三个区域：左/上 = 参数面板（与机内菜单 + X RAW Studio 左栏 1:1 对齐），
 * 中 = 预览舞台，下 = 队列与进度。
 */
public class MainActivity extends Activity implements CameraEngine.Listener {

    /** 版本号直接显示在标题栏，避免"装了旧包却以为在测新包" */
    static final String APP_VERSION = "1.2.2";
    private static final String ACTION_USB_PERMISSION = "com.fotufilm.xraw.USB_PERMISSION";
    private static final int REQ_RAF = 1001;
    private static final int REQ_RAF_BATCH = 1002;

    // ---- 跨重建保留的状态 ----
    private static CameraEngine engine;
    private static MainActivity instance;
    private static final Recipe recipe = new Recipe();
    private static byte[] lastPreview;
    private static File loadedRaf;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean suppress = false;

    private TextView statusText, queueText, previewHint, summaryText, imageTag;
    private ImageView preview;
    private ProgressBar progress;
    private HistogramView histogram;
    private TextView toneInfo;
    private java.util.Map<Integer, PropDesc> caps = java.util.Collections.emptyMap();
    private Bitmap renderedBmp, originalBmp;
    private boolean compareOriginal = false;
    private boolean histVisible = true;

    private Ui.ChoiceRow simRow, wbRow, drRow, drpRow, grainRow, grainSizeRow, chromeRow, fxRow, lmoRow;
    private Ui.ChoiceRow sizeRow, qualityRow, fileTypeRow, csRow;
    private Ui.SeekRow tempRow, wbRRow, wbBRow, hiRow, shRow, colorRow, sharpRow, nrRow, clarityRow,
            expRow, wcRow, mgRow;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        instance = this;

        if (engine == null) {
            engine = new CameraEngine(getApplicationContext(), forwarder);
            engine.start();
        }

        setContentView(buildRoot());
        refreshUiFromRecipe();
        if (lastPreview != null) showRendered(lastPreview);
        registerUsbReceivers();

        if (engine.isConnected() && engine.session() != null) {
            onSession(engine.session());
            status("已连接（旋转/重建后自动恢复）");
        } else {
            status("请用 USB-OTG 线连接相机，并把相机设为 USB RAW CONV./BACKUP RESTORE");
            tryAutoConnect();
        }
    }

    @Override
    protected void onDestroy() {
        instance = null;
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_RAF) {
            Uri u = data.getData();
            if (u != null) importOne(u);
        } else if (req == REQ_RAF_BATCH) {
            final List<File> files = new ArrayList<>();
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            status("正在导入 " + uris.size() + " 个文件…");
            new Thread(() -> {
                for (Uri u : uris) {
                    try { files.add(copyToCache(u)); } catch (IOException ignored) { }
                }
                ui.post(() -> {
                    if (files.isEmpty()) { status("批量导入失败：没有可读文件"); return; }
                    status("开始批量处理 " + files.size() + " 张（机内渲染，约 3~6 秒/张）");
                    engine.batch(files, recipe);
                });
            }, "import-batch").start();
        }
    }

    // ---------------------------------------------------------------- UI 构建

    private View buildRoot() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 640
                || getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        root.addView(buildTopBar());
        root.addView(Ui.divider(this));

        LinearLayout body = wide ? Ui.row(this) : Ui.column(this);
        body.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 预览舞台
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF0C0C0D);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        stage.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewHint = Ui.text(this, "尚无预览\n\n① 连接相机 → ② 导入一张本机拍的 RAF → ③ 调整参数", Ui.TEXT_DIM, 13f, false);
        previewHint.setGravity(Gravity.CENTER);
        stage.addView(previewHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        summaryText = Ui.text(this, "", Ui.TEXT_DIM, 12f, false);
        summaryText.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM;
        stage.addView(summaryText, slp);

        // ---- 直方图叠层（右上） ----
        histogram = new HistogramView(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                Ui.dp(this, 148), Ui.dp(this, 86));
        hlp.gravity = Gravity.TOP | Gravity.END;
        hlp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 8), 0);
        stage.addView(histogram, hlp);

        // ---- 左上：直方图/对比 开关 + 当前图像标识 ----
        LinearLayout stageTools = Ui.row(this);
        stageTools.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), 0, 0);

        android.widget.Button histBtn = Ui.button(this, "直方图", false);
        histBtn.setOnClickListener(v -> {
            histVisible = !histVisible;
            histogram.setVisibility(histVisible ? View.VISIBLE : View.GONE);
        });
        stageTools.addView(histBtn);

        android.widget.Button cmpBtn = Ui.button(this, "对比原图", false);
        cmpBtn.setOnClickListener(v -> {
            if (originalBmp == null) { toast("还没有原图：请先导入一张 RAF"); return; }
            compareOriginal = !compareOriginal;
            applyDisplay();
        });
        stageTools.addView(cmpBtn);

        imageTag = Ui.text(this, "", Ui.TEXT_DIM, 11.5f, false);
        imageTag.setPadding(Ui.dp(this, 8), 0, 0, 0);
        stageTools.addView(imageTag);

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP | Gravity.START;
        stage.addView(stageTools, tlp);

        // ---- 长按预览 = 临时看原图（松手回到当前渲染） ----
        preview.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (originalBmp != null && !compareOriginal) {
                        preview.setImageBitmap(originalBmp);
                        if (imageTag != null) imageTag.setText("原图（RAF 内嵌 JPEG）");
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    applyDisplay();
                    return true;
                default:
                    return true;
            }
        });

        LinearLayout stageCol = Ui.column(this);
        stageCol.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!wide) stageCol.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f));
        else stageCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        body.addView(stageCol);

        // 参数面板
        LinearLayout panel = Ui.column(this);
        panel.setBackgroundColor(Ui.PANEL);
        buildParams(panel);
        ScrollView ps = Ui.scroller(this, panel, Ui.PANEL);
        if (wide) {
            ps.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 380), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ps.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f));
        }
        body.addView(ps);

        root.addView(body);
        root.addView(Ui.divider(this));
        root.addView(buildBottomBar());
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.PANEL2);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));

        LinearLayout left = Ui.column(this);
        left.addView(Ui.text(this, "Fotofilm X RAW  v" + APP_VERSION, Ui.TEXT, 15f, true));
        statusText = Ui.text(this, "未连接", Ui.TEXT_DIM, 11.5f, false);
        left.addView(statusText);
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button connect = Ui.button(this, "连接相机", true);
        connect.setOnClickListener(v -> tryAutoConnect());
        bar.addView(connect);

        android.widget.Button imp = Ui.button(this, "导入 RAF", false);
        imp.setOnClickListener(v -> pickFile(REQ_RAF));
        bar.addView(imp);

        android.widget.Button batch = Ui.button(this, "批量", false);
        batch.setOnClickListener(v -> pickFile(REQ_RAF_BATCH));
        bar.addView(batch);
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.PANEL2);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));

        LinearLayout col = Ui.column(this);
        queueText = Ui.text(this, "队列空闲", Ui.TEXT_DIM, 11.5f, false);
        col.addView(queueText);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        col.addView(progress);
        bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button prev = Ui.button(this, "预览", true);
        prev.setOnClickListener(v -> {
            if (!engine.isConnected()) { toast("请先连接相机"); return; }
            if (loadedRaf == null) { toast("请先导入并上传一张 RAF"); return; }
            engine.preview(recipe);
        });
        bar.addView(prev);

        android.widget.Button exp = Ui.button(this, "导出全图", true);
        exp.setOnClickListener(v -> {
            if (!engine.isConnected()) { toast("请先连接相机"); return; }
            if (loadedRaf == null) { toast("请先导入并上传一张 RAF"); return; }
            status("导出中：机身正在按 " + recipe.summaryText() + " 渲染");
            engine.export(recipe);
        });
        bar.addView(exp);

        android.widget.Button save = Ui.button(this, "存配方", false);
        save.setOnClickListener(v -> saveRecipeDialog());
        bar.addView(save);

        android.widget.Button load = Ui.button(this, "配方库", false);
        load.setOnClickListener(v -> loadRecipeDialog());
        bar.addView(load);

        android.widget.Button probe = Ui.button(this, "自检", false);
        probe.setOnClickListener(v -> probeDialog());
        bar.addView(probe);
        return bar;
    }

    private void buildParams(LinearLayout c) {
        c.addView(Ui.section(this, "输出（与机内完全一致的画质选项）"));
        sizeRow = new Ui.ChoiceRow(this, "图像尺寸", D185.IMAGE_SIZE_NAMES, recipe.imageSizeIdx, i -> recipe.imageSizeIdx = i);
        c.addView(sizeRow);
        qualityRow = new Ui.ChoiceRow(this, "图像质量", D185.QUALITY_NAMES, recipe.qualityIdx, i -> recipe.qualityIdx = i);
        c.addView(qualityRow);
        fileTypeRow = new Ui.ChoiceRow(this, "文件类型", D185.FILE_TYPE_NAMES, recipe.fileTypeIdx, i -> {
            recipe.fileTypeIdx = i;
            status(i == 0 ? "文件类型：JPEG" : i == 1 ? "文件类型：TIFF 8bit（大文件，传输更慢）" : "文件类型：TIFF 16bit（最大最慢）");
        });
        c.addView(fileTypeRow);
        csRow = new Ui.ChoiceRow(this, "色彩空间", D185.COLOR_SPACE_NAMES, recipe.colorSpaceIdx, i -> recipe.colorSpaceIdx = i);
        c.addView(csRow);

        c.addView(Ui.section(this, "胶片模拟"));
        simRow = new Ui.ChoiceRow(this, "胶片模拟", D185.SIM_NAMES, indexOf(D185.SIM_VALUES, recipe.filmSim), i -> {
            recipe.filmSim = D185.SIM_VALUES[i];
            if (i == D185.SIM_NAMES.length - 1) status("提示：带(推断)的项目请先用「自检 → 胶片模拟扫描」在真机确认");
        });
        c.addView(simRow);

        c.addView(Ui.section(this, "动态范围"));
        drRow = new Ui.ChoiceRow(this, "动态范围", new String[]{"DR100", "DR200", "DR400"},
                recipe.dr == 200 ? 1 : recipe.dr == 400 ? 2 : 0, i -> recipe.dr = i == 1 ? 200 : i == 2 ? 400 : 100);
        c.addView(drRow);
        drpRow = new Ui.ChoiceRow(this, "动态范围优先", new String[]{"关闭", "弱", "强"},
                Math.max(0, recipe.drPriority), i -> recipe.drPriority = i);
        c.addView(drpRow);

        c.addView(Ui.section(this, "色调曲线"));
        toneInfo = Ui.text(this, "量程/步进：待相机回报", Ui.TEXT_DIM, 11f, false);
        toneInfo.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        c.addView(toneInfo);

        hiRow = new Ui.SeekRow(this, "高光色调", -20, 40, recipe.highlight, "", true,
                v -> recipe.highlight = v, () -> engine.preview(recipe));
        hiRow.scale(10);
        hiRow.setRange(-20, 40, 5, true, recipe.highlight);   // 默认 0.5 档；连相机后以相机回报为准
        c.addView(hiRow);

        shRow = new Ui.SeekRow(this, "阴影色调", -20, 40, recipe.shadow, "", true,
                v -> recipe.shadow = v, () -> engine.preview(recipe));
        shRow.scale(10);
        shRow.setRange(-20, 40, 5, true, recipe.shadow);
        c.addView(shRow);

        c.addView(Ui.section(this, "色彩"));
        colorRow = new Ui.SeekRow(this, "色彩", -40, 40, recipe.color * 10, "", false,
                v -> recipe.color = v / 10, () -> engine.preview(recipe));
        colorRow.scale(10);
        colorRow.setRange(-40, 40, 10, false, recipe.color * 10);
        c.addView(colorRow);
        sharpRow = new Ui.SeekRow(this, "锐度", -40, 40, recipe.sharpness * 10, "", false,
                v -> recipe.sharpness = v / 10, () -> engine.preview(recipe));
        sharpRow.scale(10);
        sharpRow.setRange(-40, 40, 10, false, recipe.sharpness * 10);
        c.addView(sharpRow);

        c.addView(Ui.section(this, "白平衡"));
        wbRow = new Ui.ChoiceRow(this, "白平衡", D185.WB_NAMES, recipe.wbIndex, i -> recipe.wbIndex = i);
        c.addView(wbRow);
        tempRow = new Ui.SeekRow(this, "色温 (K)", 2500, 10000, recipe.wbTemp, "K", false,
                v -> recipe.wbTemp = v, () -> engine.preview(recipe));
        c.addView(tempRow);
        wbRRow = new Ui.SeekRow(this, "R 偏移", -9, 9, recipe.wbR, "", false,
                v -> recipe.wbR = v, () -> engine.preview(recipe));
        c.addView(wbRRow);
        wbBRow = new Ui.SeekRow(this, "B 偏移", -9, 9, recipe.wbB, "", false,
                v -> recipe.wbB = v, () -> engine.preview(recipe));
        c.addView(wbBRow);

        c.addView(Ui.section(this, "图像质感"));
        grainRow = new Ui.ChoiceRow(this, "颗粒效果", new String[]{"关闭", "弱", "强"},
                recipe.grainEffect - 1, i -> recipe.grainEffect = i + 1);
        c.addView(grainRow);
        grainSizeRow = new Ui.ChoiceRow(this, "颗粒大小", new String[]{"小", "大"},
                recipe.grainLarge ? 1 : 0, i -> recipe.grainLarge = i == 1);
        c.addView(grainSizeRow);
        chromeRow = new Ui.ChoiceRow(this, "彩色铬效应", new String[]{"关闭", "弱", "强"},
                recipe.chrome - 1, i -> recipe.chrome = i + 1);
        c.addView(chromeRow);
        fxRow = new Ui.ChoiceRow(this, "彩色 FX 蓝色", new String[]{"关闭", "弱", "强"},
                recipe.fxBlue, i -> recipe.fxBlue = i);
        c.addView(fxRow);
        nrRow = new Ui.SeekRow(this, "降噪", -4, 4, recipe.nr, "", false,
                v -> recipe.nr = v, () -> engine.preview(recipe));
        c.addView(nrRow);
        clarityRow = new Ui.SeekRow(this, "清晰度", -50, 50, recipe.clarity * 10, "", false,
                v -> recipe.clarity = v / 10, () -> engine.preview(recipe));
        clarityRow.scale(10);
        clarityRow.setRange(-50, 50, 10, false, recipe.clarity * 10);
        c.addView(clarityRow);
        lmoRow = new Ui.ChoiceRow(this, "镜头调制优化器 (LMO)", new String[]{"开", "关"},
                recipe.lmo == D185.LMO_ON ? 0 : 1, i -> recipe.lmo = i == 0 ? D185.LMO_ON : D185.LMO_OFF);
        c.addView(lmoRow);

        c.addView(Ui.section(this, "黑白调节（仅单色模拟生效）"));
        wcRow = new Ui.SeekRow(this, "WC 暖冷", -18, 18, recipe.monoWC, "", false,
                v -> recipe.monoWC = v, () -> engine.preview(recipe));
        c.addView(wcRow);
        mgRow = new Ui.SeekRow(this, "MG 洋红绿", -18, 18, recipe.monoMG, "", false,
                v -> recipe.monoMG = v, () -> engine.preview(recipe));
        c.addView(mgRow);

        c.addView(Ui.section(this, "曝光"));
        expRow = new Ui.SeekRow(this, "曝光补偿 (1/3 EV)", -9, 9, recipe.expBiasThirds, "", false,
                v -> recipe.expBiasThirds = v, () -> engine.preview(recipe));
        c.addView(expRow);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));
        c.addView(spacer);
    }

    // ---------------------------------------------------------------- 参数 <-> UI

    private void refreshUiFromRecipe() {
        suppress = true;
        if (simRow != null) simRow.setIndex(indexOf(D185.SIM_VALUES, recipe.filmSim));
        if (drRow != null) drRow.setIndex(recipe.dr == 200 ? 1 : recipe.dr == 400 ? 2 : 0);
        if (drpRow != null) drpRow.setIndex(Math.max(0, Math.min(2, recipe.drPriority)));
        if (grainRow != null) grainRow.setIndex(Math.max(0, recipe.grainEffect - 1));
        if (grainSizeRow != null) grainSizeRow.setIndex(recipe.grainLarge ? 1 : 0);
        if (chromeRow != null) chromeRow.setIndex(Math.max(0, recipe.chrome - 1));
        if (fxRow != null) fxRow.setIndex(Math.max(0, Math.min(2, recipe.fxBlue)));
        if (wbRow != null) wbRow.setIndex(Math.max(0, recipe.wbIndex));
        if (lmoRow != null) lmoRow.setIndex(recipe.lmo == D185.LMO_ON ? 0 : 1);
        if (sizeRow != null) sizeRow.setIndex(recipe.imageSizeIdx);
        if (qualityRow != null) qualityRow.setIndex(recipe.qualityIdx);
        if (fileTypeRow != null) fileTypeRow.setIndex(recipe.fileTypeIdx);
        if (csRow != null) csRow.setIndex(recipe.colorSpaceIdx);

        if (tempRow != null) tempRow.setValue(recipe.wbTemp, 2500);
        if (wbRRow != null) wbRRow.setValue(recipe.wbR, -9);
        if (wbBRow != null) wbBRow.setValue(recipe.wbB, -9);
        if (hiRow != null) hiRow.setValue(recipe.highlight, -20);
        if (shRow != null) shRow.setValue(recipe.shadow, -20);
        if (colorRow != null) colorRow.setValue(recipe.color * 10, -40);
        if (sharpRow != null) sharpRow.setValue(recipe.sharpness * 10, -40);
        if (nrRow != null) nrRow.setValue(recipe.nr, -4);
        if (clarityRow != null) clarityRow.setValue(recipe.clarity * 10, -50);
        if (wcRow != null) wcRow.setValue(recipe.monoWC, -18);
        if (mgRow != null) mgRow.setValue(recipe.monoMG, -18);
        if (expRow != null) expRow.setValue(recipe.expBiasThirds, -9);
        suppress = false;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    // ---------------------------------------------------------------- USB

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (granted && dev != null) {
                    status("已授权，正在连接 " + dev.getDeviceName() + " …");
                    engine.connect(dev);
                } else {
                    status("USB 权限被拒绝");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                status("检测到断线，正在清理会话…");
                engine.disconnect();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                status("检测到 USB 设备，尝试连接…");
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
            UsbDevice any = null;
            for (UsbDevice d : usb.getDeviceList().values())
                if (d.getVendorId() == Ptp.VID_FUJIFILM) any = d;
            if (any == null) {
                status("未发现富士相机。请检查：线是数据线 / 相机已开机 / CONNECTION MODE = USB RAW CONV.");
                return;
            }
            status("检测到富士设备，但未暴露 PTP 接口 —— 相机 CONNECTION MODE 可能不是 USB RAW CONV./BACKUP RESTORE");
            return;
        }
        if (usb.hasPermission(dev)) {
            status("已授权，连接中…");
            engine.connect(dev);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        status("请求 USB 权限…");
        usb.requestPermission(dev, pi);
    }

    // ---------------------------------------------------------------- 文件

    private void pickFile(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "image/x-fuji-raf", "*/*"});
        if (req == REQ_RAF_BATCH) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    private void importOne(Uri uri) {
        status("导入中…");
        new Thread(() -> {
            try {
                File f = copyToCache(uri);
                ui.post(() -> {
                    loadedRaf = f;
                    try {
                        Raf raf = Raf.inspect(f);
                        String model = engine.session() != null ? engine.session().model : null;
                        status("已导入 " + f.getName() + "（机身 " + raf.model + "，"
                                + f.length() / 1048576 + "MB）");
                        if (model != null && !model.isEmpty() && !raf.model.isEmpty()
                                && !model.startsWith(raf.model) && !raf.model.startsWith(model)) {
                            toast("注意：这张 RAF 由 " + raf.model + " 拍摄，而当前相机是 " + model
                                    + "。相机通常只接受本机拍摄的 RAW。");
                        }
                        byte[] thumb = raf.embeddedJpeg(f);
                        if (thumb != null) setOriginal(thumb);
                        engine.upload(f);
                    } catch (IOException e) {
                        status("导入失败：" + e.getMessage());
                    }
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
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
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

    // ---------------------------------------------------------------- 配方

    private void saveRecipeDialog() {
        EditText et = new EditText(this);
        et.setText(recipe.name);
        et.setTextColor(Ui.TEXT);
        new AlertDialog.Builder(this)
                .setTitle("保存配方")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    recipe.name = et.getText().toString().trim();
                    if (recipe.name.isEmpty()) recipe.name = "未命名";
                    try {
                        Recipe.save(this, recipe);
                        status("配方已保存：" + recipe.name + "（" + recipe.summaryText() + "）");
                    } catch (IOException e) {
                        status("保存失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadRecipeDialog() {
        final List<Recipe> list = Recipe.list(this);
        if (list.isEmpty()) { toast("配方库是空的，先调好参数点「存配方」"); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name + "  —  " + list.get(i).summaryText();
        new AlertDialog.Builder(this)
                .setTitle("配方库")
                .setItems(names, (d, which) -> {
                    Recipe r = list.get(which);
                    copyInto(recipe, r);
                    refreshUiFromRecipe();
                    status("已载入配方：" + r.name);
                    if (engine.isConnected() && loadedRaf != null) engine.preview(recipe);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void copyInto(Recipe dst, Recipe src) {
        dst.name = src.name;
        dst.filmSim = src.filmSim; dst.dr = src.dr; dst.drPriority = src.drPriority;
        dst.grainEffect = src.grainEffect; dst.grainLarge = src.grainLarge;
        dst.chrome = src.chrome; dst.fxBlue = src.fxBlue;
        dst.wbIndex = src.wbIndex; dst.wbTemp = src.wbTemp; dst.wbR = src.wbR; dst.wbB = src.wbB;
        dst.highlight = src.highlight; dst.shadow = src.shadow;
        dst.color = src.color; dst.sharpness = src.sharpness;
        dst.nr = src.nr; dst.clarity = src.clarity; dst.lmo = src.lmo;
        dst.imageSizeIdx = src.imageSizeIdx; dst.qualityIdx = src.qualityIdx;
        dst.fileTypeIdx = src.fileTypeIdx; dst.colorSpaceIdx = src.colorSpaceIdx;
        dst.expBiasThirds = src.expBiasThirds; dst.monoWC = src.monoWC; dst.monoMG = src.monoMG;
    }

    private void probeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("自检 / 机型探测")
                .setItems(new String[]{
                        "① 导出设备信息 + d185 全字段 + USB 事务日志（probe_dump.txt）",
                        "② 胶片模拟扫描：逐值渲染 20 张缩略图（simsweep/）",
                        "③ 从相机读回当前 RAF 的机内参数到面板"
                }, (d, which) -> {
                    if (!engine.isConnected()) { toast("请先连接相机"); return; }
                    if (which == 0) engine.probeDump();
                    else if (which == 1) {
                        if (loadedRaf == null) { toast("请先导入一张 RAF"); return; }
                        toast("开始扫描，约 1~2 分钟");
                        engine.probeSims(recipe);
                    } else {
                        if (loadedRaf == null) { toast("请先导入一张 RAF"); return; }
                        engine.upload(loadedRaf);     // 已装载则不重传，只重新读回 0xD185
                        toast("正在从相机回读机内参数…");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- Listener 回调

    @Override public void onStatus(String msg) {
        status(msg);
    }

    @Override public void onSession(Fuji.Session s) {
        status("已连接 " + s.title()
                + (s.freeSdram >= 0 ? " · 可缓存 " + s.freeSdram + " 张" : "")
                + (s.battery >= 0 ? " · 电量 " + s.battery + "%" : ""));
    }

    /**
     * 用相机回报的属性描述改写量程/步进——不再由我猜「0.5 还是 1」。
     * 优先取当前拍摄态属性（0xD320 高光 / 0xD321 阴影），退而取配方槽域（0xD19D / 0xD19E）。
     */
    @Override public void onCaps(java.util.Map<Integer, PropDesc> c) {
        caps = c;
        PropDesc hi = pick(c, 0xD320, 0xD19D);
        PropDesc sh = pick(c, 0xD321, 0xD19E);
        StringBuilder info = new StringBuilder();
        if (hi != null && hi.isRange() && hi.step > 0) {
            // 相机可能按「刻度值」(-2..4, step 1) 或「编码值」(-20..40, step 10) 回报，统一到编码值
            int nMin = hi.min, nMax = hi.max, nStep = hi.step;
            if (Math.abs(nMax) <= 10 && Math.abs(nMin) <= 10) { nMin *= 10; nMax *= 10; nStep *= 10; }
            boolean half = nStep % 10 != 0;
            int sMin = (sh != null && sh.isRange()) ? sh.min : nMin;
            int sMax = (sh != null && sh.isRange()) ? sh.max : nMax;
            int sStep = (sh != null && sh.step > 0) ? sh.step : nStep;
            if (Math.abs(sMax) <= 10 && Math.abs(sMin) <= 10) { sMin *= 10; sMax *= 10; sStep *= 10; }
            if (hiRow != null) {
                hiRow.setRange(nMin, nMax, nStep, half, recipe.highlight);
                if (shRow != null) shRow.setRange(sMin, sMax, sStep, sStep % 10 != 0, recipe.shadow);
            }
            info.append("高光/阴影：").append(nMin / 10).append(" ~ ").append(nMax / 10)
                    .append("，步进 ").append(half ? "0.5" : "1").append("（相机回报）");
        } else {
            info.append("高光/阴影：-2 ~ +4，步进 0.5（默认值；相机未回报该属性）");
        }
        if (toneInfo != null) toneInfo.setText(info.toString());
        status("已连接 · " + info);
    }

    private static PropDesc pick(java.util.Map<Integer, PropDesc> c, int a, int b) {
        PropDesc p = c.get(a);
        if (p == null || (p.isRange() && p.step <= 0)) p = c.get(b);
        return p;
    }

    @Override public void onBaseProfile(D185 base, Recipe fromCamera) {
        copyInto(recipe, fromCamera);
        refreshUiFromRecipe();
        status("已读取机内原始参数：" + fromCamera.summaryText() + "（可在此基础上调整）");
        summaryText.setText("机内原始：" + fromCamera.summaryText());
        engine.preview(recipe);
    }

    @Override public void onPreview(byte[] jpeg, String summary) {
        lastPreview = jpeg;
        showRendered(jpeg);
        if (summaryText != null) summaryText.setText(summary + " · " + jpeg.length / 1024 + "KB 预览");
    }

    @Override public void onExport(File file, String summary) {
        progress.setVisibility(View.GONE);
        status("已保存 " + file.getName() + "  →  " + file.getParent());
        toast("出图完成：" + file.getName());
        if (summaryText != null) summaryText.setText(summary + " · " + file.getName());
    }

    @Override public void onProgress(String what, long done, long total) {
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(total <= 0 ? 0 : (int) (done * 1000 / total));
        queueText.setText(what + "  " + (done / 1048576) + " / " + (total / 1048576) + " MB");
    }

    @Override public void onError(String msg) {
        progress.setVisibility(View.GONE);
        status("⚠ " + msg);
        toast(msg);
    }

    @Override public void onQueueChanged(List<String> items, int active) {
        if (queueText == null) return;
        if (items.isEmpty()) {
            queueText.setText("队列空闲" + (loadedRaf != null ? " · 已装载 " + loadedRaf.getName() : ""));
        } else {
            StringBuilder sb = new StringBuilder("待处理 " + items.size() + " 项：");
            for (int i = 0; i < Math.min(3, items.size()); i++) sb.append(items.get(i)).append(i < 2 ? " · " : "");
            queueText.setText(sb.toString());
        }
    }

    @Override public void onDisconnected() {
        status("已断开，可重新连接");
    }

    @Override public void onProbeDone(String path) {
        toast("已写入：" + path);
        status("探测结果：" + path);
    }

    // ---------------------------------------------------------------- 小工具

    /** 相机渲染结果（预览或出图） */
    private void showRendered(byte[] jpeg) {
        Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bm == null) return;
        renderedBmp = bm;
        applyDisplay();
    }

    /** RAF 内嵌 JPEG = 「原图」基准（无需相机即可显示） */
    private void setOriginal(byte[] jpeg) {
        Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bm == null) return;
        originalBmp = bm;
        if (renderedBmp == null) {
            applyDisplay();
        } else if (imageTag != null) {
            imageTag.setText("");
        }
    }

    /** 统一出口：决定画哪张、刷新直方图与角标 */
    private void applyDisplay() {
        Bitmap show = (compareOriginal && originalBmp != null) ? originalBmp : renderedBmp;
        if (show == null) show = originalBmp;
        if (show == null) return;
        preview.setImageBitmap(show);
        previewHint.setVisibility(View.GONE);
        if (histogram != null && histVisible) histogram.setBitmap(show);
        if (imageTag != null) {
            imageTag.setText(show == originalBmp ? "原图（RAF 内嵌 JPEG）" : "当前渲染（机身输出）");
        }
    }

    private void status(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** 供引擎回调转发（Activity 重建时不会丢任务） */
    private static final CameraEngine.Listener forwarder = new CameraEngine.Listener() {
        private MainActivity cur() { return instance; }

        @Override public void onStatus(String msg) { MainActivity a = cur(); if (a != null) a.onStatus(msg); }
        @Override public void onSession(Fuji.Session s) { MainActivity a = cur(); if (a != null) a.onSession(s); }
        @Override public void onCaps(java.util.Map<Integer, PropDesc> c) { MainActivity a = cur(); if (a != null) a.onCaps(c); }
        @Override public void onBaseProfile(D185 base, Recipe r) { MainActivity a = cur(); if (a != null) a.onBaseProfile(base, r); }
        @Override public void onPreview(byte[] jpeg, String summary) { MainActivity a = cur(); if (a != null) a.onPreview(jpeg, summary); }
        @Override public void onExport(File f, String summary) { MainActivity a = cur(); if (a != null) a.onExport(f, summary); }
        @Override public void onProgress(String what, long done, long total) { MainActivity a = cur(); if (a != null) a.onProgress(what, done, total); }
        @Override public void onError(String msg) { MainActivity a = cur(); if (a != null) a.onError(msg); }
        @Override public void onQueueChanged(List<String> items, int active) { MainActivity a = cur(); if (a != null) a.onQueueChanged(items, active); }
        @Override public void onDisconnected() { MainActivity a = cur(); if (a != null) a.onDisconnected(); }
        @Override public void onProbeDone(String path) { MainActivity a = cur(); if (a != null) a.onProbeDone(path); }
    };
}
