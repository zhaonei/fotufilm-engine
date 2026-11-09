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
            J_BATCH = 5, J_PROBE_DUMP = 6, J_PROBE_SIMS = 7, J_DISCONNECT = 8, J_CLOSE = 9;

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
    private D185 baseProfile;      // 相机为「当前已装载 RAF」给出的原始档
    private File loadedRaf;        // 当前已装载的 RAF（不需要重传）
    private final List<String> queueNames = new ArrayList<>();

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
                final String msg = (job.label == null ? "" : job.label + "：") + e.getMessage();
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
                status("打开 USB 设备…");
                transport = new UsbPtp(ctx, dev);
                transport.open();
                fuji = new Fuji(transport);
                status("握手 / 校验 USB RAW CONV. 模式…");
                final Fuji.Session s = fuji.handshake();
                session = s;
                baseProfile = null;
                loadedRaf = null;
                main.post(() -> {
                    listener.onSession(s);
                    listener.onStatus("已连接 " + s.title()
                            + (s.freeSdram >= 0 ? " · 可缓存 " + s.freeSdram + " 张" : "")
                            + (s.battery >= 0 ? " · 电量 " + s.battery + "%" : ""));
                });
                break;
            }
            case J_UPLOAD: {
                requireSession();
                // 已装载同一文件则跳过上传（这正是「不重传也能改参数」的收益点）
                if (loadedRaf != null && loadedRaf.getAbsolutePath().equals(job.file.getAbsolutePath())
                        && baseProfile != null) {
                    status("该 RAF 已装载，跳过上传");
                } else {
                    status("上传 RAF：" + job.file.getName() + " …");
                    fuji.uploadRaf(job.file, (sent, total) ->
                            main.post(() -> listener.onProgress("上传", sent, total)));
                    loadedRaf = job.file;
                }
                status("读取机内原始配置档 (0xD185)…");
                baseProfile = fuji.readProfile();
                final D185 b = baseProfile;
                final Recipe fromCam = Recipe.fromD185(b);
                main.post(() -> listener.onBaseProfile(b, fromCam));
                break;
            }
            case J_PREVIEW: {
                requireLoaded();
                D185 p = D185.parse(baseProfile.raw());
                job.recipe.applyTo(p);
                p.sanitize(baseProfile.dr());
                fuji.writeProfile(p);
                status("渲染预览…");
                byte[] jpeg = fuji.render(false, 20000);
                final String sum = p.summary();
                main.post(() -> listener.onPreview(jpeg, sum));
                status("预览就绪");
                break;
            }
            case J_EXPORT: {
                requireLoaded();
                D185 p = D185.parse(baseProfile.raw());
                job.recipe.applyTo(p);
                p.sanitize(baseProfile.dr());
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
                        p.sanitize(p.dr());
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
                requireSession();
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
                UsbPtp.DataIn di = transport.commandDataIn(Ptp.OC_GET_DEVICE_INFO);
                Fuji.DeviceInfo info = Fuji.DeviceInfo.parse(di.data);
                sb.append("manufacturer=").append(info.manufacturer).append('\n')
                        .append("model=").append(info.model).append('\n');
                sb.append("ops(").append(info.operations.length).append("): ");
                for (int op : info.operations) sb.append(String.format("%04X ", op));
                sb.append("\nprops(").append(info.properties.length).append("): ");
                for (int pr : info.properties) sb.append(String.format("%04X ", pr));
                sb.append('\n');
                if (baseProfile != null) sb.append("\n== d185 ==\n").append(baseProfile.hexDump());
                sb.append("\n== iop code ==\n");
                try {
                    sb.append("0xD184 = ").append(fuji.getString(Ptp.DPC_IOP_CODE)).append('\n');
                } catch (IOException ignored) { }
                sb.append("\n== USB 信息 ==\n").append(transport.describe()).append('\n');
                sb.append("\n== PTP 事务日志 ==\n").append(transport.dumpLog());
                File f = new File(Recipe.dir(ctx), "probe_dump.txt");
                FileOutputStream fos = new FileOutputStream(f);
                try { fos.write(sb.toString().getBytes(StandardCharsets.UTF_8)); } finally { fos.close(); }
                final String path = f.getAbsolutePath();
                main.post(() -> listener.onProbeDone(path));
                status("自检信息已写入 probe_dump.txt");
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
                        D185 p = D185.parse(baseProfile.raw());
                        job.recipe.applyTo(p);
                        p.set(D185.IDX_FILM_SIM, sims[i]);
                        p.sanitize(baseProfile.dr());
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
