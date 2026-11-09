#!/usr/bin/env python3
"""AndroidManifest.xml → 二进制 AXML（严格按 AOSP ResXMLTree 布局）。

本机是 aarch64，Google 的 aapt2 只有 x86_64 构建，无法运行，因此直接手写容器。

布局（每个 size 字段 == 该 chunk 实际字节数）：
  file        : RES_XML(0x0003) hs=8  size=total
  string pool : RES_STRING_POOL(0x0001) hs=28 flags=UTF8(0x100)
  res map     : RES_XML_RESOURCE_MAP(0x0180) hs=8
  ns start    : RES_XML_START_NAMESPACE(0x0100) hs=16 size=16  (body: prefix,uri 已含在 hs 内)
  elements    : RES_XML_START_ELEMENT(0x0102) hs=16 size=16+20+20*attrCount
                RES_XML_END_ELEMENT  (0x0103) hs=16 size=24
  ns end      : RES_XML_END_NAMESPACE(0x0101) hs=16 size=16

用法: python3 tools/mkaxml.py app/AndroidManifest.xml out/AndroidManifest.xml
"""
import struct
import sys
import xml.dom.minidom

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_PREFIX = "android"

# android: 框架属性 id（按 id 升序写入 resource map 前缀，与 aapt 一致）
ATTR_IDS = {
    "theme": 0x01010000,
    "label": 0x01010001,
    "icon": 0x01010002,
    "name": 0x01010003,
    "permission": 0x01010006,
    "hasCode": 0x0101000C,
    "exported": 0x01010010,
    "launchMode": 0x0101001D,
    "configChanges": 0x0101001F,
    "allowBackup": 0x01010280,
    "minSdkVersion": 0x0101020C,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "targetSdkVersion": 0x01010270,
    "required": 0x0101028E,
    "extractNativeLibs": 0x010104EA,
}

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_RESOURCE_MAP = 0x0180

TYPE_STRING = 0x03
TYPE_REFERENCE = 0x01
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

NO_ENTRY = 0xFFFFFFFF
INT_ATTRS = {"versionCode", "minSdkVersion", "targetSdkVersion"}
BOOL_ATTRS = {"hasCode", "allowBackup", "exported", "required", "extractNativeLibs"}


class Pool:
    def __init__(self):
        self.items = []
        self.index = {}

    def add(self, s):
        if s not in self.index:
            self.index[s] = len(self.items)
            self.items.append(s)
        return self.index[s]


def string_pool_chunk(strings):
    """UTF-16 池（flags=0）——与 aapt2 为 AndroidManifest 生成的池完全一致，
    避免 UTF-8 池「u16 长度 / u8 长度」先后顺序的歧义。
    每条：u16 字符数（0x8000 扩展），UTF-16LE 内容，u16 NUL。"""
    offsets, data = [], bytearray()
    for s in strings:
        offsets.append(len(data))
        units = s.encode("utf-16-le")
        n = len(units) // 2
        if n > 0x7FFF:
            data += struct.pack("<H", 0x8000 | (n >> 16)) + struct.pack("<H", n & 0xFFFF)
        else:
            data += struct.pack("<H", n)
        data += units + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    strings_start = 28 + 4 * len(strings)
    size = strings_start + len(data)
    head = struct.pack("<HHIIIIII", RES_STRING_POOL, 28, size,
                       len(strings), 0, 0, strings_start, 0)
    return head + b"".join(struct.pack("<I", o) for o in offsets) + bytes(data)


def chunk(kind, header_size, body):
    """size 恒等于实际字节数"""
    return struct.pack("<HHI", kind, header_size, 8 + len(body)) + body


def walk(el):
    yield el
    for c in el.childNodes:
        if c.nodeType == c.ELEMENT_NODE:
            yield from walk(c)


def attrs_of(el):
    out = []
    for i in range(el.attributes.length):
        it = el.attributes.item(i)
        if it.name == "xmlns" or it.name.startswith("xmlns:"):
            continue
        name = it.name
        if ":" in name:
            prefix, local = name.split(":", 1)
            if prefix != ANDROID_PREFIX:
                raise SystemExit("unsupported attribute namespace: " + name)
            out.append((ANDROID_NS, local, it.value))
        else:
            out.append((None, name, it.value))   # 例如 usb-device 的 vendor-id（无命名空间）
    return out


def typed_value(ns, name, value, pool):
    if ns == ANDROID_NS and name in INT_ATTRS:
        return NO_ENTRY, TYPE_INT_DEC, int(value)
    if ns == ANDROID_NS and (name in BOOL_ATTRS or value in ("true", "false")):
        return NO_ENTRY, TYPE_INT_BOOLEAN, (0xFFFFFFFF if value == "true" else 0)
    return pool.index[value], TYPE_STRING, pool.index[value]


def build(root):
    pool = Pool()
    # 1) 先把用到的 android: 属性名按 id 升序塞进池首，形成 resource map 前缀
    used = set()
    for el in walk(root):
        for ns, name, _ in attrs_of(el):
            if ns == ANDROID_NS:
                if name not in ATTR_IDS:
                    raise SystemExit("android:%s 未在 ATTR_IDS 中登记" % name)
                used.add(name)
    ordered = sorted(used, key=lambda n: ATTR_IDS[n])
    resmap = []
    for n in ordered:
        pool.add(n)
        resmap.append(ATTR_IDS[n])
    # 2) 命名空间
    pool.add(ANDROID_PREFIX)
    pool.add(ANDROID_NS)
    # 3) 其余字符串
    for el in walk(root):
        pool.add(el.tagName)
        for ns, name, value in attrs_of(el):
            if ns != ANDROID_NS:
                pool.add(name)
            pool.add(value)
    return pool, resmap


def encode_element(el, pool):
    ns_i = NO_ENTRY
    name_i = pool.index[el.tagName]
    alist = attrs_of(el)
    ext = struct.pack("<II", ns_i, name_i)                      # attrExt: ns, name
    ext += struct.pack("<HHHHHH", 20, 20, len(alist), 0, 0, 0)  # attributeStart/Size/Count/id/class/style
    for ns, name, value in alist:
        raw, dtype, data = typed_value(ns, name, value, pool)
        ext += struct.pack("<III", pool.index[ANDROID_NS] if ns == ANDROID_NS else NO_ENTRY,
                           pool.index[name], raw)
        ext += struct.pack("<HBBI", 8, 0, dtype, data & 0xFFFFFFFF)
    # node 的第三个 int 其实是 attrExt 的起点，这里整体拼接：
    body = struct.pack("<II", 1, NO_ENTRY) + ext
    start = chunk(RES_XML_START_ELEMENT, 16, body)
    children = b"".join(encode_element(c, pool) for c in el.childNodes
                        if c.nodeType == c.ELEMENT_NODE)
    end = chunk(RES_XML_END_ELEMENT, 16, struct.pack("<II", ns_i, name_i))
    return start + children + end


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    root = xml.dom.minidom.parse(sys.argv[1]).documentElement
    pool, resmap = build(root)

    ns_body = struct.pack("<II", pool.index[ANDROID_PREFIX], pool.index[ANDROID_NS])
    body = chunk(RES_XML_START_NAMESPACE, 16, ns_body)
    body += encode_element(root, pool)
    body += chunk(RES_XML_END_NAMESPACE, 16, ns_body)

    resmap_bytes = b""
    if resmap:
        resmap_bytes = chunk(RES_XML_RESOURCE_MAP, 8,
                             b"".join(struct.pack("<I", v) for v in resmap))

    pool_bytes = string_pool_chunk(pool.items)
    total = 8 + len(pool_bytes) + len(resmap_bytes) + len(body)
    out = struct.pack("<HHI", RES_XML, 8, total) + pool_bytes + resmap_bytes + body

    with open(sys.argv[2], "wb") as f:
        f.write(out)
    print("strings=%d attrIds=%d bytes=%d" % (len(pool.items), len(resmap), len(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
