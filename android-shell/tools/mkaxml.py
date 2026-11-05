#!/usr/bin/env python3
"""Writes AndroidManifest.xml in Android's binary XML (AXML) form.

The SDK's aapt2 is an x86_64 binary and cannot run on this aarch64 host, so the manifest is
encoded directly, in the layout aapt2 emits: an UTF-8 string pool whose leading strings are the
android: attribute names in resource-id order, a resource map naming those ids, then the element
tree.

    python3 tools/mkaxml.py <manifest.xml> <out/AndroidManifest.xml>
"""
import struct
import sys
import xml.dom.minidom

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_PREFIX = "android"

# Framework attribute resource ids; Android resolves android: attributes through these.
ATTR_IDS = {
    "theme": 0x01010000,
    "label": 0x01010001,
    "icon": 0x01010002,
    "name": 0x01010003,
    "permission": 0x01010006,
    "hasCode": 0x0101000C,
    "exported": 0x01010010,
    "allowBackup": 0x01010280,
    "minSdkVersion": 0x0101020C,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "targetSdkVersion": 0x01010270,
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
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

NO_ENTRY = 0xFFFFFFFF
INT_ATTRIBUTES = {"versionCode", "minSdkVersion", "targetSdkVersion"}


def chunk(kind, body, header_size=16):
    return struct.pack("<HHI", kind, header_size, header_size + len(body)) + body


class Pool:
    """String pool in insertion order."""

    def __init__(self):
        self.strings = []
        self.index = {}

    def add(self, value):
        if value not in self.index:
            self.index[value] = len(self.strings)
            self.strings.append(value)
        return self.index[value]


def encode_pool_lengths(value):
    """Each string carries its UTF-16 length then its UTF-8 length (UTF-8 pool flag)."""
    out = bytearray()
    for length in (len(value.encode("utf-16-le")) // 2, len(value.encode("utf-8"))):
        if length > 0x7FFF:
            out += struct.pack("<HH", 0x8000 | (length >> 16), length & 0xFFFF)
        else:
            out.append(length)
    return bytes(out)


def string_pool_chunk(strings):
    data = bytearray()
    offsets = []
    for value in strings:
        offsets.append(len(data))
        data += encode_pool_lengths(value) + value.encode("utf-8") + b"\x00"
    while len(data) % 4:
        data += b"\x00"
    strings_start = 28 + 4 * len(strings)  # from the chunk's start to the first string
    header = struct.pack("<HHIIIIII", RES_STRING_POOL, 28, strings_start + len(data),
                         len(strings), 0, 0x100, strings_start, 0)
    return header + b"".join(struct.pack("<I", offset) for offset in offsets) + bytes(data)


def elements(root):
    """Every element in document order."""
    def walk(element):
        yield element
        for child in element.childNodes:
            if child.nodeType == child.ELEMENT_NODE:
                yield from walk(child)
    return list(walk(root))


def element_attributes(element):
    """(namespace, name, value) of an element's attributes.

    minidom leaves namespaces unresolved, so "android:label" arrives as a plain name; the android
    prefix is unrolled here. xmlns declarations are carried by the namespace chunks, not as
    attributes.
    """
    items = []
    for i in range(element.attributes.length):
        item = element.attributes.item(i)
        if item.name == "xmlns" or item.name.startswith("xmlns:"):
            continue
        name = item.name
        namespace = None
        if ":" in name:
            prefix, local = name.split(":", 1)
            if prefix != ANDROID_PREFIX:
                raise SystemExit(f"unsupported attribute namespace: {name}")
            namespace, name = ANDROID_NS, local
        items.append((namespace, name, item.value))
    return items


def android_attributes(root):
    """(name, value) of the android: attributes used, with their framework ids."""
    found = []
    for element in elements(root):
        for namespace, name, value in element_attributes(element):
            if namespace == ANDROID_NS:
                found.append((name, value))
    return found


def build_pool(root):
    pool = Pool()
    attributes = android_attributes(root)
    # Attribute names lead the pool, in resource-id order: the resource map is then that prefix.
    named = sorted({name for name, _ in attributes if name in ATTR_IDS}, key=lambda n: ATTR_IDS[n])
    resource_ids = []
    for name in named:
        pool.add(name)
        resource_ids.append(ATTR_IDS[name])
    pool.add(ANDROID_NS)
    pool.add(ANDROID_PREFIX)
    for name, value in attributes:
        if name not in ATTR_IDS:
            continue
        if name in INT_ATTRIBUTES or value in ("true", "false"):
            continue
        pool.add(value)
    for element in elements(root):
        pool.add(element.tagName)
        for namespace, name, value in element_attributes(element):
            pool.add(name)
            if namespace == ANDROID_NS and (name in INT_ATTRIBUTES
                                            or value in ("true", "false")):
                continue
            pool.add(value)
    return pool, resource_ids, named


def attribute_chunk(pool, namespace, name, value):
    ns_index = pool.index[ANDROID_NS] if namespace == ANDROID_NS else NO_ENTRY
    name_index = pool.index[name]
    if name in INT_ATTRIBUTES:
        raw, value_type, data = NO_ENTRY, TYPE_INT_DEC, int(value)
    elif value in ("true", "false"):
        raw, value_type = NO_ENTRY, TYPE_INT_BOOLEAN
        data = 0xFFFFFFFF if value == "true" else 0
    else:
        raw = pool.index[value]
        value_type, data = TYPE_STRING, raw
    return (struct.pack("<III", ns_index, name_index, raw)
            + struct.pack("<HBBI", 8, 0, value_type, data & 0xFFFFFFFF))


def encode_element(pool, element):
    name_index = pool.index[element.tagName]
    body = struct.pack("<IIII", 1, NO_ENTRY, NO_ENTRY, name_index)
    attributes = element_attributes(element)
    body += struct.pack("<HHHHHH", 20, 20, len(attributes), 0, 0, 0)
    for namespace, name, value in attributes:
        body += attribute_chunk(pool, namespace, name, value)
    start = chunk(RES_XML_START_ELEMENT, body)
    children = b"".join(encode_element(pool, child) for child in element.childNodes
                        if child.nodeType == child.ELEMENT_NODE)
    end = chunk(RES_XML_END_ELEMENT, struct.pack("<IIII", 1, NO_ENTRY, NO_ENTRY, name_index))
    return start + children + end


def encode(manifest_path):
    root = xml.dom.minidom.parse(manifest_path).documentElement
    pool, resource_ids, named = build_pool(root)

    resource_map = b""
    if resource_ids:
        resource_map = chunk(RES_XML_RESOURCE_MAP,
                             b"".join(struct.pack("<I", value) for value in resource_ids),
                             header_size=8)

    namespace = struct.pack("<IIII", 1, NO_ENTRY, pool.index[ANDROID_PREFIX],
                            pool.index[ANDROID_NS])
    body = chunk(RES_XML_START_NAMESPACE, namespace)
    body += encode_element(pool, root)
    body += chunk(RES_XML_END_NAMESPACE, namespace)

    pool_bytes = string_pool_chunk(pool.strings)
    total = 8 + len(pool_bytes) + len(resource_map) + len(body)
    header = struct.pack("<HHI", RES_XML, 8, total)
    print(f"strings={len(pool.strings)} attributes={len(resource_ids)} "
          f"resource-map={resource_ids}")
    return header + pool_bytes + resource_map + body


def main():
    if len(sys.argv) != 3:
        print("usage: mkaxml.py <manifest.xml> <out/AndroidManifest.xml>", file=sys.stderr)
        return 2
    data = encode(sys.argv[1])
    with open(sys.argv[2], "wb") as handle:
        handle.write(data)
    print(f"wrote {sys.argv[2]}: {len(data)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
