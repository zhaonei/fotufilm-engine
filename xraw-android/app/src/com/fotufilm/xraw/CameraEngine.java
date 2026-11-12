package com.fotufilm.xraw;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 相机引擎：单线程串行执行所有 PTP 作业（相机是有状态单会话设备，绝不能并发）。
 * 预览作业可被新预览替换（滑杆拖动时不会堆几十个任务）。
 */
public final class CameraEngine {
    private static final String TAG = "CameraEngine";

    public interface Listener {
        void onStatus(String msg);
        void onSession(Fuji.Session s);
        void onCaps(java.util.Map<Integer, PropDesc> caps);
        void onBaseProfile(D185 base, Recipe fromCamera);
        void onPreview(byte[] jpeg, String summary);
        void onExport(File file, String summary);
        void onProgress(String what, long done, long total);
        void onError(String msg);
        void onQueueChanged(List<String> items, int activeIndex);
        void onDisconnected();
        void onProbeDone(String path);
    }

    private static final int J_CONNECT = 1, J_UPLOAD = 2, J_PREVIEW = 3, J_EXPORT = 4,
            J_BATCH = 5, J_PROBE_DUMP = 6, J_PROBE_SIMS = 7, J_DISCONNECT = 8, J_CLOSE = 9,
            J_PROBE_CAPS = 10, J_DIAG_UPLOAD = 11, J_CALIB = 12;

    private static final class Job {
        final int kind;
        File file;
        List<File> files;
        Recipe recipe;
        String label;
        Job(int kind) { this.kind = kind; }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedList<Job> queue = new LinkedList<>();
    private Thread thread;
    private volatile boolean running;
    private volatile UsbDevice pendingDevice;

    private UsbPtp transport;
    private Fuji fuji;
    private Fuji.Session session;
    private java.util.Map<Integer, PropDesc> caps = java.util.Collections.emptyMap();
    private D185 baseProfile;      // 相机为「当前已装载 RAF」给出的原始档
    private File loadedRaf;        // 当前已装载的 RAF（不需要重传）
    private final List<String> queueNames = new ArrayList<>();
    private volatile UsbDevice lastDevice;
    private volatile String lastTransportLog = "";
    private volatile String lastLogPath = "";
    private volatile android.net.Uri lastLogUri;
    private volatile Fuji.DeviceInfo deviceInfo;
    private volatile String lastNote = "";
    private volatile boolean sessionDead = false;

    public CameraEngine(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    public synchronized Fuji.Session session() { return session; }
    public synchronized boolean isConnected() { return fuji != null && transport != null && transport.sessionOpen; }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "camera-io");
        thread.start();
    }

    public void stop() {
        running = false;
        post(new Job(J_CLOSE));
    }

    // ---------------------------------------------------------------- 提交作业

    public void connect(UsbDevice dev) {
        pendingDevice = dev;
        Job j = new Job(J_CONNECT);
        j.label = "连接相机";
        post(j);
    }

    public void upload(File raf) {
        Job j = new Job(J_UPLOAD);
        j.file = raf;
        j.label = "上传 " + raf.getName();
        post(j);
    }

    public void preview(Recipe r) {
        Job j = new Job(J_PREVIEW);
        j.recipe = r;
        synchronized (queue) {
            for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i).kind == J_PREVIEW) queue.remove(i);
            queue.add(j);
            refreshQueue();
        }
        synchronized (queue) { queue.notifyAll(); }
    }

    public void export(Recipe r) {
        Job j = new Job(J_EXPORT);
        j.recipe = r;
        j.label = "导出全分辨率";
        post(j);
    }

    public void batch(List<File> rafs, Recipe r) {
        Job j = new Job(J_BATCH);
        j.files = rafs;
        j.recipe = r;
        j.label = "批量 " + rafs.size() + " 张";
        post(j);
    }

    public void probeCaps() {
        Job j = new Job(J_PROBE_CAPS);
        j.label = "读取相机能力";
        post(j);
    }

    public void probeDump() {
        Job j = new Job(J_PROBE_DUMP);
        j.label = "自检信息";
        post(j);
    }

    public void probeSims(Recipe r) {
        Job j = new Job(J_PROBE_SIMS);
        j.recipe = r;
        j.label = "胶片模拟扫描";
        post(j);
    }

    public void disconnect() {
        Job j = new Job(J_DISCONNECT);
        j.label = "断开";
        post(j);
    }

    private void post(Job j) {
        synchronized (queue) {
            queue.add(j);
            refreshQueue();
            queue.notifyAll();
        }
    }

    private void refreshQueue() {
        queueNames.clear();
        for (Job j : queue) queueNames.add(j.label == null ? "任务" : j.label);
        final List<String> copy = new ArrayList<>(queueNames);
        final int active = running ? Math.max(0, queue.size() - 1) : 0;
        main.post(() -> listener.onQueueChanged(copy, 0));
    }

    // ---------------------------------------------------------------- 工作线程

    private void loop() {
        while (running) {
            Job job;
            synchronized (queue) {
                while (running && queue.isEmpty()) {
                    try { queue.wait(500); } catch (InterruptedException e) { return; }
                }
                if (!running) break;
                job = queue.removeFirst();
                refreshQueue();
            }
            try {
                handle(job);
            } catch (IOException e) {
                Log.e(TAG, "作业失败: " + job.label, e);
                captureLog();
                // 已经 resync/recover 过仍失败 → 用心跳判定会话是否真的死了，避免无限重试造成"卡死"
                if (fuji != null && !fuji.heartbeat()) {
                    sessionDead = true;
                    if (transport != null) transport.note("会话判定死亡（心跳失败）");
                }
                final String msg = (job.label == null ? "" : job.label + "：") + e.getMessage()
                        + (sessionDead ? "\n（会话已失效，请拔插数据线后重新连接）" : "");
                main.post(() -> listener.onError(msg));
                if (job.kind != J_PREVIEW) {   // 预览失败不打断会话
                    // 会话可能已死：保留 fuji，但清掉易失状态
                    baseProfile = null;
                }
            } catch (Throwable e) {
                Log.e(TAG, "作业异常", e);
                main.post(() -> listener.onError("内部错误：" + e));
            }
        }
    }

    private void handle(Job job) throws IOException {
        switch (job.kind) {
            case J_CLOSE:
                shutdown();
                break;
            case J_CONNECT: {
                shutdown();
                UsbDevice dev = pendingDevice;
                if (dev == null) throw new IOException("未指定设备");
                lastDevice = dev;
                status("打开 USB 设备…");
                transport = new UsbPtp(ctx, dev);
                transport.open();
                fuji = new Fuji(transport);
                status("握手 / 校验 USB RAW CONV. 模式…");
                final Fuji.Session s = fuji.handshake();
                session = s;
                sessionDead = false;
                deviceInfo = fuji.info();
                // 注意：这里绝不能再读任何"可选属性"。
                // 实测日志：读 0xD20E/0xD242 得到两次 0x200A(DevicePropNotSupported) 之后，
                // 紧接着的第一个 bulk OUT 就返回 -1（v1.1.0 与 v1.2.3 均复现）。
                // libfuji/rawji 之所以能跑通，正是因为它们从不读这些属性。
                final java.util.Map<Integer, PropDesc> c = caps;
                baseProfile = null;
                loadedRaf = null;
                main.post(() -> {
                    listener.onSession(s);
                    listener.onCaps(c);
                    if (deviceInfo != null && deviceInfo.operations.length > 0
                            && !deviceInfo.supportsOperation(Ptp.OC_FUJI_SEND_OBJECT_INFO)) {
                        listener.onStatus("已连接 " + s.title()
                                + " · ⚠ 相机未声明支持 0x900C（上传通道可能不同，见自检⑥）");
                        return;
                    }
                    listener.onStatus("已连接 " + s.title()
                            + (s.freeSdram >= 0 ? " · 可缓存 " + s.freeSdram + " 张" : "")
                            + (s.battery >= 0 ? " · 电量 " + s.battery + "%" : ""));
                });
                break;
            }
            case J_UPLOAD: {
                requireSession();
                try {
                    doUpload(job.file);
                } catch (IOException first) {
                    // 关键：相机 STALL 之后整条管道作废，只重建 PTP 会话没用，
                    // 必须把 USB 连接整个关掉重开（复位端点状态）。
                    status("上传失败，重开 USB 连接后重试…（" + first.getMessage() + "）");
                    captureLog();
                    try {
                        reopenUsb();
                        doUpload(job.file);
                    } catch (IOException second) {
                        String path = lastLogPath;
                        throw new IOException(second.getMessage()
                                + "\n\n当前上传通道形态 = " + Fuji.UPLOAD_CHANNEL
                                + "（可在「自检 → ⑥ 选择上传通道」切换）"
                                + "\n**请拔插数据线后再试**（相机此刻已卡死，不拔插必然继续失败）"
                                + (path.isEmpty() ? "" : "\n诊断日志：" + path));
                    }
                }
                break;
            }
            case J_DIAG_UPLOAD: {
                long size = loadedRaf != null ? loadedRaf.length() : 32L * 1024 * 1024;
                final String path = runVariantSweep(size);
                main.post(() -> listener.onProbeDone(path));
                status("变体自检完成：" + path);
                break;
            }
            case J_PREVIEW: {
                requireLoaded();
                if (sessionDead) {
                    main.post(() -> listener.onError("相机会话已失效：请拔插数据线后重新「连接相机」（当前不会再自动重试以免卡死）"));
                    break;
                }
                D185 p = baseProfile.copy();
                applyDrIfChanged(job.recipe);
                job.recipe.applyTo(p);
                lastNote = job.recipe.note;
                writeProfileSafely(p);
                status("渲染预览…");
                byte[] jpeg = fuji.render(false, 20000);
                final String sum = p.summary();
                main.post(() -> listener.onPreview(jpeg, sum));
                status("预览就绪");
                break;
            }
            case J_EXPORT: {
                requireLoaded();
                D185 p = baseProfile.copy();
                job.recipe.applyTo(p);
                fuji.writeProfile(p);
                status("渲染全分辨率（机身处理中）…");
                byte[] data = fuji.render(true, 60000);
                File out = MediaSaver.save(ctx, data, baseName(loadedRaf) + "_" + tag(p), job.recipe.exportExtension());
                final String sum = p.summary();
                final File f = out;
                main.post(() -> listener.onExport(f, sum));
                status("已保存 " + out.getName());
                break;
            }
            case J_BATCH: {
                requireSession();
                int i = 0;
                for (File raf : job.files) {
                    i++;
                    final int idx = i;
                    final String nm = raf.getName();
                    main.post(() -> listener.onStatus("批量 " + idx + "/" + job.files.size() + "：" + nm));
                    try {
                        if (!fuji.heartbeat()) throw new IOException("相机无响应，停止批量");
                        fuji.uploadRaf(raf, (sent, total) ->
                                main.post(() -> listener.onProgress("上传 " + nm, sent, total)));
                        D185 p = fuji.readProfile();
                        job.recipe.applyTo(p);
                                fuji.writeProfile(p);
                        byte[] data = fuji.render(true, 60000);
                        File out = MediaSaver.save(ctx, data, baseName(raf) + "_" + tag(p),
                                job.recipe.exportExtension());
                        final String sum = p.summary();
                        final File f = out;
                        main.post(() -> listener.onExport(f, sum));
                    } catch (IOException e) {
                        Log.w(TAG, "批量单张失败 " + nm, e);
                        final String msg = nm + " 失败：" + e.getMessage();
                        main.post(() -> listener.onError(msg));
                        fuji.drainObjects();
                    }
                }
                loadedRaf = null;
                baseProfile = null;
                status("批量完成");
                break;
            }
            case J_PROBE_DUMP: {
                // 不强制要求已连接：连接失败时更需要这份日志
                if (fuji == null && lastDevice != null) {
                    status("尝试重新连接以采集日志…");
                    try {
                        transport = new UsbPtp(ctx, lastDevice);
                        transport.open();
                        fuji = new Fuji(transport);
                        try { fuji.handshake(); } catch (IOException ignored) { }
                    } catch (IOException e) {
                        lastTransportLog = transport != null ? transport.dumpLog() : lastTransportLog;
                    }
                }
                StringBuilder sb = new StringBuilder();
                sb.append("== FujiSession ==\n");
                if (session != null) {
                    sb.append("model=").append(session.model).append('\n')
                            .append("version=").append(session.version).append('\n')
                            .append("serial=").append(session.serial).append('\n')
                            .append("usbmode=").append(session.usbMode).append('\n')
                            .append("freeSdram=").append(session.freeSdram).append('\n')
                            .append("battery=").append(session.battery).append('\n');
                }
                sb.append("\n== DeviceInfo ==\n");
                Fuji.DeviceInfo info = null;
                if (transport != null) {
                    try {
                        UsbPtp.DataIn di = transport.commandDataIn(Ptp.OC_GET_DEVICE_INFO);
                        info = Fuji.DeviceInfo.parse(di.data);
                    } catch (IOException e) {
                        sb.append("读取失败: ").append(e.getMessage()).append('\n');
                    }
                }
                if (info != null) {
                    sb.append("manufacturer=").append(info.manufacturer).append('\n')
                            .append("model=").append(info.model).append('\n')
                            .append("version=").append(info.deviceVersion).append('\n')
                            .append("serial=").append(info.serial).append('\n');
                    sb.append("ops(").append(info.operations.length).append("): ");
                    for (int op : info.operations) sb.append(String.format("%04X ", op));
                    sb.append("\nprops(").append(info.properties.length).append("): ");
                    for (int pr : info.properties) sb.append(String.format("%04X ", pr));
                    sb.append('\n');
                } else {
                    sb.append("(无)\n");
                }
                sb.append("\n").append(deviceInfoText());
            if (baseProfile != null) sb.append("\n== d185 ==\n").append(baseProfile.hexDump());
                sb.append("\n== iop code ==\n");
                try {
                    sb.append("0xD184 = ").append(fuji.getString(Ptp.DPC_IOP_CODE)).append('\n');
                } catch (IOException ignored) { }
                sb.append("\n== USB 信息 ==\n")
                        .append(transport != null ? transport.describe() : "transport=null").append('\n');
                sb.append("\n== 机型能力（GetDevicePropDesc）==\n");
                if (caps.isEmpty()) sb.append("(空)\n");
                for (java.util.Map.Entry<Integer, PropDesc> e : caps.entrySet()) {
                    sb.append(e.getValue().describe()).append('\n');
                }
                sb.append("\n== PTP 事务日志 ==\n")
                        .append(transport != null ? transport.dumpLog() : lastTransportLog);
                MediaSaver.Exported ex = MediaSaver.exportText(ctx, "probe_dump.txt", sb.toString());
                lastLogPath = ex.displayPath();
                lastLogUri = ex.uri;
                final String path = lastLogPath;
                main.post(() -> listener.onProbeDone(path));
                status("诊断日志已导出：" + path);
                break;
            }
            case J_PROBE_CAPS: {
                requireSession();
                status("读取相机能力（GetDevicePropDesc）…");
                final java.util.Map<Integer, PropDesc> c = fuji.probeDescriptors();
                caps = c;
                main.post(() -> listener.onCaps(c));
                status("已读取 " + c.size() + " 条属性约束");
                break;
            }
            case J_CALIB: {
                requireLoaded();
                final String path = runCalibration();
                main.post(() -> listener.onProbeDone(path));
                status("自校准完成：" + path);
                break;
            }
            case J_PROBE_SIMS: {
                requireLoaded();
                File outDir = new File(Recipe.dir(ctx), "simsweep");
                if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建 simsweep 目录");
                int[] sims = D185.SIM_VALUES;
                for (int i = 0; i < sims.length; i++) {
                    final int idx = i + 1;
                    main.post(() -> listener.onStatus("模拟扫描 " + idx + "/" + sims.length));
                    try {
                        D185 p = baseProfile.copy();
                        job.recipe.applyTo(p);
                        p.set(D185.Idx.FILM_SIM, sims[i]);
                                fuji.writeProfile(p);
                        byte[] jpg = fuji.render(false, 20000);
                        File out = new File(outDir, String.format("sim_%02d_%d.jpg", sims[i], sims[i]));
                        FileOutputStream fos = new FileOutputStream(out);
                        try { fos.write(jpg); } finally { fos.close(); }
                    } catch (IOException e) {
                        final String msg = "sim=" + sims[i] + " 失败：" + e.getMessage();
                        main.post(() -> listener.onError(msg));
                        fuji.drainObjects();
                    }
                }
                final String path = outDir.getAbsolutePath();
                main.post(() -> listener.onProbeDone(path));
                status("模拟扫描完成，结果在 simsweep/");
                break;
            }
            case J_DISCONNECT:
                shutdown();
                main.post(() -> {
                    listener.onDisconnected();
                    listener.onStatus("已断开");
                });
                break;
        }
    }

    /** 把当前 USB 事务日志留存 + 落盘（连接失败时最有用） */
    public String lastLogPath() { return lastLogPath; }
    public android.net.Uri lastLogUri() { return lastLogUri; }

    private void captureLog() {
        try {
            if (transport != null) lastTransportLog = transport.dumpLog();
            StringBuilder sb = new StringBuilder();
            sb.append("== 结果 ==\n");
            sb.append(session != null ? ("已连接 " + session.title()) : "未连接 / 连接失败");
            sb.append('\n');
            if (!caps.isEmpty()) {
                sb.append("\n== 机型能力 ==\n");
                for (java.util.Map.Entry<Integer, PropDesc> e : caps.entrySet())
                    sb.append(e.getValue().describe()).append('\n');
            }
            sb.append("\n").append(deviceInfoText());
            if (baseProfile != null) sb.append("\n== d185 ==\n").append(baseProfile.hexDump());
            sb.append("\n== USB 信息 ==\n")
                    .append(transport != null ? transport.describe() : "transport=null").append('\n');
            sb.append("\n== PTP 事务日志 ==\n").append(lastTransportLog);
            MediaSaver.Exported ex = MediaSaver.exportText(ctx, "probe_dump.txt", sb.toString());
            lastLogPath = ex.displayPath();
            lastLogUri = ex.uri;
            Log.i(TAG, "日志已导出 " + lastLogPath);
        } catch (Exception e) {
            Log.w(TAG, "写日志失败: " + e);
        }
    }

    /**
     * 用 7 种 ObjectInfo 形态逐个问相机「你接受哪种」。
     * 每个变体都开一条**全新 USB 连接**，避免上一次 STALL 影响下一次。
     */
    private String runVariantSweep(long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("== SendObjectInfo 变体自检 ==\n");
        sb.append("相机: ").append(session != null ? session.title() : "?")
                .append(" 文件大小=").append(fileSize).append('\n');
        sb.append("当前通道形态 = ").append(Fuji.UPLOAD_CHANNEL).append('\n');
        sb.append("注意：相机在上一次失败后可能已卡死，本项只测当前通道；\n");
        sb.append("要测别的形态，请先「自检 → ⑥」切换，再拔插数据线后重跑本项。\n\n");

        if (lastDevice == null) {
            android.hardware.usb.UsbManager um =
                    (android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (d.getVendorId() == Ptp.VID_FUJIFILM) { lastDevice = d; break; }
                }
            }
        }
        if (lastDevice == null) {
            sb.append("找不到相机设备（请先点一次「连接相机」）\n");
        } else {
            for (int v = Fuji.UPLOAD_CHANNEL; v <= Fuji.UPLOAD_CHANNEL; v++) {
                final int vv = v + 1;
                main.post(() -> listener.onStatus("测试通道 " + Fuji.UPLOAD_CHANNEL + " …"));
                try {
                    shutdown();
                    transport = new UsbPtp(ctx, lastDevice);
                    transport.open();
                    fuji = new Fuji(transport);
                    fuji.handshake();
                    String r = fuji.trySendObjectInfo(v, fileSize);
                    sb.append("  ").append(r).append('\n');
                    lastTransportLog = transport.dumpLog();
                } catch (Exception e) {
                    sb.append("  变体").append(v).append(" -> 连接/握手失败: ")
                            .append(e.getMessage()).append('\n');
                }
            }
            shutdown();
        }
        sb.append("\n== PTP 事务日志（最后一个变体）==\n").append(lastTransportLog);
        try {
            MediaSaver.Exported ex = MediaSaver.exportText(ctx, "probe_upload_variants.txt", sb.toString());
            lastLogPath = ex.displayPath();
            lastLogUri = ex.uri;
        } catch (IOException e) {
            lastLogPath = "写入失败: " + e.getMessage();
        }
        return lastLogPath;
    }

    /**
     * 字段绑定自校准：逐个参数写入一个独特值 → 回读 profile → 对比。
     * 报告里会指出「期望索引 vs 实际变化索引」，从而在真机上确定字段映射。
     */
    private String runCalibration() {
        StringBuilder sb = new StringBuilder();
        sb.append("== 字段绑定自校准 ==\n");
        sb.append("相机: ").append(session != null ? session.title() : "?")
                .append("  基础档长度=").append(baseProfile.rawLength())
                .append(" nProps=").append(baseProfile.rawPropCount())
                .append(" 布局=0x201/29 native=").append(FpNative.available()).append("\n\n");
        sb.append("做法：每次只改一个字段 → 写回 → 回读 → 列出实际发生变化的索引。\n");
        sb.append("若「实际变化索引」≠「写入索引」，说明该机型字段映射需要平移。\n\n");

        int[][] cases = {
                {D185.Idx.FILM_SIM, 11, 0},
                {D185.Idx.DYNAMIC_RANGE, 200, 1},
                {D185.Idx.WIDE_DRANGE, 2, 2},
                {D185.Idx.GRAIN, 5, 3},
                {D185.Idx.CHROME, 3, 4},
                {D185.Idx.FX_BLUE, 3, 5},
                {D185.Idx.WB_MODE, 0x8007, 7},
                {D185.Idx.WB_SHIFT_R, 5, 8},
                {D185.Idx.WB_SHIFT_B, -5, 9},
                {D185.Idx.WB_COLOR_TEMP, 6000, 10},
                {D185.Idx.HIGHLIGHT, 20, 11},
                {D185.Idx.SHADOW, -20, 12},
                {D185.Idx.COLOR, 30, 13},
                {D185.Idx.SHARPNESS, 30, 14},
                {D185.Idx.NOISE_RED, 0x5000, 15},
                {D185.Idx.CLARITY, 30, 16},
                {D185.Idx.EXPOSURE_BIAS, 1000, 17},
        };
        String[] names = {
                "FilmSim=11(ClassicChrome)", "DR=200", "DRPriority=2(Strong)", "Grain=5(强+大)",
                "Chrome=3(强)", "FXBlue=3(强)", "SmoothSkin=3(强)", "WB=0x8007(色温)",
                "WBShiftR=+5", "WBShiftB=-5", "ColorTemp=6000", "Highlight=+2(20)",
                "Shadow=-2(-20)", "Color=+3(30)", "Sharpness=+3(30)", "NR=0x5000(+4)",
                "Clarity=+3(30)", "ExposureBias=1000(+1EV)",
        };

        for (int c = 0; c < cases.length; c++) {
            final int idx = cases[c][0], value = cases[c][1];
            main.post(() -> listener.onStatus("校准 " + (idx) + " …"));
            try {
                D185 p = baseProfile.copy();
                p.set(idx, value);
                fuji.writeProfile(p);
                D185 back = fuji.readProfile();
                StringBuilder changed = new StringBuilder();
                for (int i = 0; i < D185.PROPS; i++) {
                    int a = baseProfile.get(i), b = back.get(i);
                    if (a != b) changed.append(String.format("idx%d:%d→%d ", i, a, b));
                }
                boolean ok = back.get(idx) == value;
                sb.append(String.format("%-26s 写入 idx%-2d = %-6d %s  变化: %s%n",
                        names[c], idx, value, ok ? "回读一致" : "回读不一致(被规范化/被忽略)",
                        changed.length() == 0 ? "（无变化！）" : changed.toString().trim()));
            } catch (IOException e) {
                sb.append(String.format("%-26s 写入 idx%-2d 失败: %s%n", names[c], idx, e.getMessage()));
                fuji.drainObjects();
            }
        }
        // 恢复原始档
        try { fuji.writeProfile(baseProfile); } catch (IOException ignored) { }
        sb.append("\n（已把原始档写回相机）\n");

        try {
            MediaSaver.Exported ex = MediaSaver.exportText(ctx, "calib.txt", sb.toString());
            lastLogPath = ex.displayPath();
            lastLogUri = ex.uri;
        } catch (IOException e) {
            lastLogPath = "写入失败: " + e.getMessage();
        }
        return lastLogPath;
    }

    /**
     * 写档统一入口：相机拒绝时**恢复原始档**并给出提示，但**保留会话**，
     * 让用户可以继续调别的参数、并把日志一次导出来。
     */
    private void writeProfileSafely(D185 p) throws IOException {
        try {
            if (transport != null) transport.note("写入差异 " + diffOf(p));
            fuji.writeProfile(p);
            verifyWriteBack(p);
            String n = lastNote;
            if (n != null && !n.isEmpty()) status("⚠ " + n);
        } catch (Fuji.ParamRejectedException e) {
            Log.w(TAG, "参数被相机拒绝: " + e.getMessage());
            try { fuji.writeProfile(baseProfile); } catch (IOException ignored) { }
            baseProfile = fuji.readProfile();          // 以相机真实状态为基准重建
            final String msg = e.getMessage() + "（已自动恢复原参数，会话仍可用）";
            main.post(() -> listener.onError(msg));
        }
    }

    /**
     * DR / DR优先：优先用相机自己的属性(0xD191/0xD190)下发，再回读 profile，
     * 让相机把联动字段填好——直接把两个字段塞进 d185 会导致整幅偏绿。
     */
    private void applyDrIfChanged(Recipe r) {
        if (baseProfile == null) return;
        r.skipDrInProfile = false;
        boolean changed = r.dr != baseProfile.dr() || r.drPriority != baseProfile.drPriority();
        if (!changed) return;
        int prio = r.drPriority;
        int baseDr = baseProfile.dr();
        int dr = (baseDr == 100 || baseDr == 200 || baseDr == 400) ? Math.min(r.dr, baseDr) : r.dr;
        try {
            if (fuji.applyDrViaProps(dr, prio)) {
                baseProfile = fuji.readProfile();          // 相机填好的新基线
                r.skipDrInProfile = true;
                r.dr = dr;
                r.drPriority = prio;
                final String msg = "DR 已由相机属性下发：优先=" + prio + " DR=" + dr
                        + "（回读 profile: DR=" + baseProfile.dr() + " 优先=" + baseProfile.drPriority() + "）";
                status(msg);
                if (transport != null) transport.note(msg);
            }
        } catch (IOException e) {
            Log.w(TAG, "DR 属性下发失败，退回 profile 写入: " + e.getMessage());
        }
    }

    private String diffOf(D185 p) {
        if (baseProfile == null) return "-";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < D185.PROPS; i++) {
            if (p.get(i) != baseProfile.get(i)) {
                if (n++ < 12) sb.append("idx").append(i).append(':')
                        .append(baseProfile.get(i)).append("→").append(p.get(i)).append(' ');
            }
        }
        return n == 0 ? "无" : (n + " 项: " + sb);
    }

    /** 写档后回读校验：把"写入的字段"与"相机回读的字段"比对，不一致立刻告警 */
    private void verifyWriteBack(D185 written) {
        try {
            D185 back = fuji.readProfile();
            StringBuilder diff = new StringBuilder();
            int n = 0;
            for (int i = 0; i < D185.PROPS; i++) {
                if (back.get(i) != written.get(i)) {
                    n++;
                    if (n <= 6) diff.append(String.format("idx%d:%d→%d ", i, written.get(i), back.get(i)));
                }
            }
            if (n > 0) {
                final String msg = "⚠ 相机回读与写入有 " + n + " 处不一致：" + diff;
                status(msg);
                Log.w(TAG, msg);
            }
        } catch (IOException e) {
            Log.w(TAG, "回读校验失败: " + e.getMessage());
        }
    }

    private void doUpload(File file) throws IOException {
        requireSession();
        if (loadedRaf != null && loadedRaf.getAbsolutePath().equals(file.getAbsolutePath())
                && baseProfile != null) {
            status("该 RAF 已装载，跳过上传");
        } else {
            status("上传 RAF：" + file.getName() + " …");
            fuji.uploadRaf(file, (sent, total) ->
                    main.post(() -> listener.onProgress("上传", sent, total)));
            loadedRaf = file;
        }
        status("读取机内原始配置档 (0xD185)…");
        baseProfile = fuji.readProfile();
        final D185 b = baseProfile;
        final Recipe fromCam = Recipe.fromD185(b);
        main.post(() -> listener.onBaseProfile(b, fromCam));
    }

    /** 关掉 USB 连接再重开（复位端点 toggle/halt，比只重建 PTP 会话更彻底） */
    private void reopenUsb() throws IOException {
        transport_close();
        if (lastDevice == null) throw new IOException("没有可用设备，请拔插数据线重连");
        transport = new UsbPtp(ctx, lastDevice);
        transport.open();
        fuji = new Fuji(transport);
        final Fuji.Session s2 = fuji.handshake();
        session = s2;
        main.post(() -> listener.onSession(s2));
    }

    public void calibrate() {
        Job j = new Job(J_CALIB);
        j.label = "字段绑定自校准";
        post(j);
    }

    public void diagUploadVariants() {
        Job j = new Job(J_DIAG_UPLOAD);
        j.label = "上传变体自检";
        post(j);
    }

    /** 把 opcode / 属性列表格式化成可读文本（含关键 opcode 是否支持） */
    private String deviceInfoText() {
        Fuji.DeviceInfo di = deviceInfo;
        if (di == null) return "== DeviceInfo == (未获取)\n";
        StringBuilder sb = new StringBuilder();
        sb.append("== DeviceInfo ==\n");
        sb.append("manufacturer=").append(di.manufacturer).append('\n');
        sb.append("model=").append(di.model).append('\n');
        sb.append("version=").append(di.deviceVersion).append('\n');
        sb.append("serial=").append(di.serial).append('\n');
        sb.append("ops(").append(di.operations.length).append("): ");
        for (int op : di.operations) sb.append(String.format("%04X ", op));
        sb.append('\n');
        sb.append("props(").append(di.properties.length).append("): ");
        for (int pr : di.properties) sb.append(String.format("%04X ", pr));
        sb.append('\n');
        sb.append("关键 opcode：0x900C=")
                .append(di.supportsOperation(Ptp.OC_FUJI_SEND_OBJECT_INFO) ? "支持" : "未声明")
                .append("  0x900D=")
                .append(di.supportsOperation(Ptp.OC_FUJI_SEND_OBJECT2) ? "支持" : "未声明")
                .append("  0x901D=")
                .append(di.supportsOperation(Ptp.OC_FUJI_SEND_OBJECT) ? "支持" : "未声明")
                .append("  0x100C=")
                .append(di.supportsOperation(Ptp.OC_SEND_OBJECT_INFO) ? "支持" : "未声明")
                .append('\n');
        return sb.toString();
    }

    private void requireSession() throws IOException {
        if (fuji == null || transport == null) throw new IOException("相机未连接");
        if (!transport.sessionOpen) throw new IOException("会话已失效，请重新连接");
    }

    private void requireLoaded() throws IOException {
        requireSession();
        if (baseProfile == null) throw new IOException("请先导入并上传一张 RAF");
    }

    private void shutdown() {
        transport_close();
        session = null;
        baseProfile = null;
        loadedRaf = null;
    }

    private void transport_close() {
        try {
            if (transport != null) transport.close();
        } catch (Exception ignored) { }
        transport = null;
        fuji = null;
    }

    private void status(String msg) { main.post(() -> listener.onStatus(msg)); }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String tag(D185 p) {
        String s = D185.simName(p.filmSim());
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
