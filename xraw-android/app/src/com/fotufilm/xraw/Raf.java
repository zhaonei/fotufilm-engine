package com.fotufilm.xraw;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * RAF 轻量解析：只为两件事服务
 *  1) 取机身型号字符串，与相机 GetDeviceInfo 的 model 比对（跨机身 RAF 一定会被相机 0x2002 拒绝）；
 *  2) 抽内嵌 JPEG 做「打开即出图」的本地占位预览（L0 级，不发相机）。
 *
 * RAF 头：0..15 "FUJIFILMCCD-RAW "，16..19 版本，20..23 camera id，24.. 型号字符串；
 * 偏移 84 = 内嵌 JPEG 偏移，88 = 长度（dcraw 的取法）。
 */
public final class Raf {
    public final String model;
    public final int jpegOffset;
    public final int jpegLength;

    private Raf(String model, int off, int len) {
        this.model = model; this.jpegOffset = off; this.jpegLength = len;
    }

    public static Raf inspect(java.io.File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] head = new byte[128];
            if (raf.read(head) < 64) throw new IOException("文件太小，不像是 RAF");
            String magic = new String(head, 0, 15, "US-ASCII");
            if (!magic.startsWith("FUJIFILM")) {
                throw new IOException("不是富士 RAF 文件（magic=" + magic.trim() + "）");
            }
            int end = 24;
            while (end < 64 && head[end] != 0) end++;
            String model = new String(head, 24, Math.max(0, end - 24), "US-ASCII").trim();
            int off = UsbPtp.le32(head, 84);
            int len = UsbPtp.le32(head, 88);
            if (off <= 0 || len <= 0 || off + (long) len > f.length()) { off = 0; len = 0; }
            return new Raf(model, off, len);
        } finally {
            try { raf.close(); } catch (IOException ignored) { }
        }
    }

    /** 抽内嵌 JPEG；失败返回 null（UI 会退化为灰底占位） */
    public byte[] embeddedJpeg(java.io.File f) {
        if (jpegOffset <= 0 || jpegLength <= 0) return null;
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                byte[] buf = new byte[jpegLength];
                raf.seek(jpegOffset);
                raf.readFully(buf);
                return (buf.length > 4 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8) ? buf : null;
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
