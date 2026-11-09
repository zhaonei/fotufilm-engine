package com.fotufilm.xraw;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 出图保存：始终写 App 外部私有目录（免权限），Android 10+ 再额外入库到相册/下载。 */
public final class MediaSaver {

    /** 导出结果：file 一定可写；uri 在 Android 10+ 指向「下载/FotofilmXRAW」，可用于分享 */
    public static final class Exported {
        public final File file;
        public final Uri uri;
        Exported(File f, Uri u) { file = f; uri = u; }
        public String displayPath() {
            return uri != null ? ("下载/FotofilmXRAW/" + file.getName()) : file.getAbsolutePath();
        }
    }

    /** 把纯文本（诊断日志/配方）导出到用户可见位置 */
    public static Exported exportText(Context ctx, String name, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        File dir = new File(ctx.getExternalFilesDir(null), "diagnostics");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建诊断目录");
        File out = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(data); } finally { fos.close(); }

        Uri uri = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FotofilmXRAW");
                Uri item = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (item != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(item);
                    if (os != null) {
                        try { os.write(data); } finally { os.close(); }
                        uri = item;
                    }
                }
            } catch (Exception ignored) {
                // 入库失败仍保留 App 外部目录里的文件
            }
        }
        return new Exported(out, uri);
    }


    public static File save(Context ctx, byte[] data, String baseName, String ext) throws IOException {
        boolean jpeg = ".jpg".equalsIgnoreCase(ext);
        String mime = jpeg ? "image/jpeg" : "image/tiff";

        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FotufilmXRAW");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建输出目录");
        File out = unique(dir, baseName, ext);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, out.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                Uri collection;
                if (jpeg) {
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotufilmXRAW");
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else {
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FotofilmXRAW");
                    collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                }
                Uri item = ctx.getContentResolver().insert(collection, v);
                if (item != null) {
                    java.io.OutputStream os = ctx.getContentResolver().openOutputStream(item);
                    if (os != null) {
                        try { os.write(data); } finally { os.close(); }
                    }
                }
            } catch (Exception ignored) {
                // 入库失败不影响已落盘的文件
            }
        }
        return out;
    }

    private static File unique(File dir, String base, String ext) {
        String safe = base.replaceAll("[^\\p{L}\\p{N}_\\-.]", "_");
        File f = new File(dir, safe + ext);
        int i = 1;
        while (f.exists()) f = new File(dir, safe + "_" + (i++) + ext);
        return f;
    }
}
