# -*- coding: utf-8 -*-
"""把 classes.dex 塞进 aapt2 产出的 base.apk，并保证 resources.arsc 为 STORED + 4 字节对齐。

Windows 上 zipalign 在本项目环境里不可靠，改为手工写 zip（与原构建链一致）。

用法：
    python pack.py <base.apk> <classes.dex> <out.apk>
"""
import os
import struct
import sys
import zipfile
import zlib

ALIGN = 4


def build(src, dex, out):
    zin = zipfile.ZipFile(src, 'r')
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    if os.path.exists(dex):
        zi = zipfile.ZipInfo('classes.dex')
        zi.compress_type = zipfile.ZIP_STORED
        entries.append((zi, open(dex, 'rb').read()))
    zin.close()

    if os.path.exists(out):
        os.remove(out)

    zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
    offset = 0
    for zi, data in entries:
        name = zi.filename
        zi.compress_type = zipfile.ZIP_STORED
        base = offset + 30 + len(name.encode('utf-8'))
        need = (ALIGN - base % ALIGN) % ALIGN
        if need and need < 4:
            need += 4  # 额外字段最小 4 字节（id 2 + size 2）
        if need:
            # 0xFFFF 未知字段：id(2) + size(2) + 填充
            payload = b'\x00' * (need - 4)
            zi.extra = struct.pack('<HH', 0xFFFF, len(payload)) + payload
        else:
            zi.extra = b''
        zi.header_offset = offset
        zi.file_size = len(data)
        zi.CRC = zlib.crc32(data) & 0xffffffff
        zout.writestr(zi, data)
        if zi.compress_type == zipfile.ZIP_DEFLATED:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            csize = len(co.compress(data) + co.flush())
        else:
            csize = len(data)
        offset = offset + 30 + len(name.encode('utf-8')) + len(zi.extra) + csize
    zout.close()

    # 校验：所有 STORED 条目的 data 偏移必须 4 字节对齐
    z = zipfile.ZipFile(out)
    ok = True
    for i in z.infolist():
        off = i.header_offset + 30 + len(i.filename.encode('utf-8')) + len(i.extra)
        good = (i.compress_type != zipfile.ZIP_STORED or off % ALIGN == 0)
        if not good:
            ok = False
        print('  %-22s method=%d data_off=%d mod4=%d %s'
              % (i.filename, i.compress_type, off, off % ALIGN, 'OK' if good else 'BAD'))
    print('apk size', os.path.getsize(out), 'align_ok', ok)
    if not ok:
        sys.exit(1)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    build(sys.argv[1], sys.argv[2], sys.argv[3])
