#!/usr/bin/env python3
"""
X-M5 d185 参数快照工具（PC 版，备用）

用途：连上相机 → 上传一张 RAF → 读 0xD185 → 打印 629 字节配置包的逐字段解码。

⚠️ Windows 注意事项（这通常是抓不到/连不上的原因）
  Windows 自带的 WPD/PTP 驱动不允许用户态程序直接占用 USB 接口。
  要用本脚本必须先装 libusb 驱动（会**替换**系统驱动，之后富士官方软件就用不了）：
    1. 下载 Zadig (https://zadig.akeo.ie)
    2. 相机设为 USB RAW CONV./BACKUP RESTORE 并插上
    3. Zadig → Options → List All Devices → 选 FUJIFILM X-M5
    4. 目标驱动选 WinUSB → Replace Driver
    5. 改回：设备管理器 → 卸载设备 → 勾"删除驱动程序" → 重新插拔
  如果你不想折腾驱动，**直接用 App 的「自检 → ⑧ d185 参数快照」**，
  那条路在你的手机上已验证可用，效果完全一样。

依赖：
  pip install pyusb

用法：
  python xm5_d185_dump.py --raf _DSF4142.RAF            # 完整流程：上传 + 读快照
  python xm5_d185_dump.py --read-only                    # 相机里已有 RAF 时，直接读
"""
import argparse, struct, sys, time

try:
    import usb.core, usb.util
except ImportError:
    sys.exit("缺少 pyusb：pip install pyusb")

VID = 0x04CB
UID = 0x04CB
OP_GET_DEVICE_INFO = 0x1001
OP_OPEN_SESSION = 0x1002
OP_CLOSE_SESSION = 0x1003
OP_GET_PROP = 0x1015
OP_SET_PROP = 0x1016
OP_FUJI_SEND_OBJECT_INFO = 0x900C
OP_FUJI_SEND_OBJECT2 = 0x900D
DPC_USB_MODE = 0xD16E
DPC_RAW_CONV_PROFILE = 0xD185
DPC_START_RAW_CONV = 0xD183

NAMES = [
 "ShootingCondition","FileType","ImageSize","ImageQuality","ExposureBias",
 "DynamicRange","WideDRange","FilmSimulation","GrainEffect","ColorChrome",
 "WBShootCond","WhiteBalance","WBShiftR","WBShiftB","WBColorTemp",
 "HighlightTone","ShadowTone","Color","Sharpness","NoiseReduction",
 "LensModulationOpt","ColorSpace","BlackImageTone","unknown_23","ColorChromeFXBlue",
 "MonoColorRG","Clarity","unknown_27","unknown_28",
]


class Ptp:
    def __init__(self):
        self.dev = usb.core.find(idVendor=VID)
        if not self.dev:
            sys.exit("没找到富士相机：请确认已接 USB、相机在 USB RAW CONV./BACKUP RESTORE 模式")
        self.dev.set_configuration()
        cfg = self.dev.get_active_configuration()
        intf = None
        for i in cfg:
            if i.bInterfaceClass == 6:
                intf = i
                break
        if intf is None:
            sys.exit("找不到 class 6 接口：相机可能不在 RAW CONV 模式，或驱动不是 WinUSB")
        usb.util.claim_interface(self.dev, intf.bInterfaceNumber)
        self.ep_in = self.ep_out = None
        for ep in intf:
            if usb.util.endpoint_type(ep.bmAttributes) != usb.util.ENDPOINT_TYPE_BULK:
                continue
            if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                self.ep_in = ep
            else:
                self.ep_out = ep
        if not (self.ep_in and self.ep_out):
            sys.exit("缺少 bulk 端点")
        self.tid = 0

    # ---- 收发：header 与 payload 必须在同一次传输里 ----
    def _send(self, data):
        off = 0
        while off < len(data):
            chunk = data[off:off + 128 * 1024]
            n = self.dev.write(self.ep_out, chunk, timeout=180000)
            if n <= 0:
                raise IOError("bulk OUT 失败")
            off += n

    def _recv(self, timeout=25000):
        first = self.dev.read(self.ep_in, 512, timeout=timeout)
        total = struct.unpack("<I", bytes(first[:4]))[0]
        buf = bytearray(first)
        while len(buf) < total:
            more = self.dev.read(self.ep_in, min(256 * 1024, total - len(buf)), timeout=60000)
            buf += bytes(more)
        ctype, code, tid = struct.unpack("<HHI", bytes(buf[4:12]))
        payload = bytes(buf[12:]) if ctype == 2 else b""
        params = []
        if ctype == 3:
            rest = bytes(buf[12:])
            for i in range(0, len(rest) - 3, 4):
                params.append(struct.unpack("<I", rest[i:i + 4])[0])
        return ctype, code, tid, params, payload

    def cmd(self, opcode, params=(), data=None, timeout=25000):
        self.tid += 1
        head = struct.pack("<IHHI", 12 + 4 * len(params), 1, opcode, self.tid)
        head += b"".join(struct.pack("<I", p) for p in params)
        self._send(head)
        if data is not None:
            dh = struct.pack("<IHHI", 12 + len(data), 2, opcode, self.tid)
            self._send(dh + data)
        while True:
            ctype, code, tid, rparams, payload = self._recv(timeout)
            if ctype == 3:
                return code, rparams, payload

    def stream_object(self, opcode, path):
        size = os.path.getsize(path)
        self.tid += 1
        head = struct.pack("<IHHI", 12, 1, opcode, self.tid)
        self._send(head)
        dh = struct.pack("<IHHI", 12 + size, 2, opcode, self.tid)
        self._send(dh)
        with open(path, "rb") as f:
            sent = 0
            while True:
                chunk = f.read(128 * 1024)
                if not chunk:
                    break
                n = self.dev.write(self.ep_out, chunk, timeout=180000)
                sent += n
        while True:
            ctype, code, tid, p, _ = self._recv(120000)
            if ctype == 3:
                return code


def object_info(size, name=b"FUP_FILE.dat"):
    def s(b):
        return bytes([len(b) + 1]) + b.decode("ascii").encode("utf-16-le") + b"\x00\x00"
    return (struct.pack("<IHH I", 0, 0xF802, 0, size)
            + struct.pack("<H I I I I I I H I I",
                          0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            + s(name) + b"\x00" + b"\x00" + b"\x00")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raf", help="要上传的 RAF（可选；相机里已有 RAF 时不用给）")
    ap.add_argument("--read-only", action="store_true", help="只读，不上传")
    ap.add_argument("--out", default="d185_snapshot.txt")
    a = ap.parse_args()

    p = Ptp()
    code, _, _ = p.cmd(OP_OPEN_SESSION, [1])
    print("OpenSession ->", hex(code))
    code, _, info = p.cmd(OP_GET_DEVICE_INFO)
    print("GetDeviceInfo ->", hex(code), len(info), "bytes")

    code, _, v = p.cmd(OP_GET_PROP, [DPC_USB_MODE])
    mode = struct.unpack("<H", v[:2])[0] if len(v) >= 2 else -1
    print("USBMode =", mode, "(6 = RAW CONV.)")

    if a.raf and not a.read_only:
        import os
        size = os.path.getsize(a.raf)
        print(f"上传 {a.raf} ({size/1048576:.1f} MB) …")
        code, _, _ = p.cmd(OP_FUJI_SEND_OBJECT_INFO, [0, 0, 0], object_info(size))
        print("  SendObjectInfo ->", hex(code))
        code = p.stream_object(OP_FUJI_SEND_OBJECT2, a.raf)
        print("  SendObject2 ->", hex(code))

    time.sleep(0.5)
    code, _, pack = p.cmd(OP_GET_PROP, [DPC_RAW_CONV_PROFILE])
    print("读 0xD185 ->", hex(code), len(pack), "bytes")
    if len(pack) < 64:
        sys.exit("配置包为空：确认 RAF 已上传成功")

    n = struct.unpack("<H", pack[0:2])[0]
    base = 0x201
    if base + 29 * 4 > len(pack):
        base = len(pack) - n * 4
    vals = [struct.unpack("<i", pack[base + i * 4: base + i * 4 + 4])[0] for i in range(29)]

    lines = []
    lines.append("===== d185 参数快照（PC 工具）=====")
    lines.append(f"包长={len(pack)}  nProps(u16@0)={n}  布局=0x201/29")
    lines.append("")
    lines.append("idx | 字段                | 原始值      | 十六进制")
    lines.append("----+---------------------+-------------+-----------")
    for i, v in enumerate(vals):
        lines.append(f"{i:3d} | {NAMES[i]:<19} | {v:11d} | 0x{v & 0xFFFFFFFF:08X}")
    text = "\n".join(lines)
    print(text)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print("\n已保存:", a.out)
    p.cmd(OP_CLOSE_SESSION)


if __name__ == "__main__":
    import os
    main()
