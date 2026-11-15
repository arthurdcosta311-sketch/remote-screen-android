#!/usr/bin/env python3
"""Draws the app icon and writes it into the iOS asset catalog.

Pure standard library on purpose: the icon is generated, so the repository
needs no binary asset and no image toolchain. Run from the repository root:

    python3 tools/make_icon.py

The same motif is the Android adaptive icon in
app/src/main/res/drawable/ic_launcher_foreground.xml.
"""

import os
import struct
import zlib

SIZE = 1024

BACKGROUND = (14, 17, 22)
GREEN = (61, 220, 151)

# Monitor, an arrow inside it pointing out, and its stand.
SCREEN = (170, 300, 854, 700, 56)
SCREEN_INNER = (214, 344, 810, 656, 28)
ARROW = [(330, 478), (556, 478), (556, 436), (700, 512), (556, 588), (556, 546), (330, 546)]
STAND = (468, 700, 556, 770)
BASE = (400, 770, 624, 790, 10)


def in_round_rect(x, y, box):
    x0, y0, x1, y1, r = box
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def in_polygon(x, y, points):
    inside = False
    j = len(points) - 1
    for i in range(len(points)):
        xi, yi = points[i]
        xj, yj = points[j]
        if (yi > y) != (yj > y):
            if x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
        j = i
    return inside


def colour_at(x, y):
    if 330 <= x <= 700 and 436 <= y <= 588 and in_polygon(x, y, ARROW):
        return GREEN
    if in_round_rect(x, y, SCREEN_INNER):
        return BACKGROUND
    if in_round_rect(x, y, SCREEN):
        return GREEN
    if STAND[0] <= x <= STAND[2] and STAND[1] <= y <= STAND[3]:
        return GREEN
    if in_round_rect(x, y, BASE):
        return GREEN
    return BACKGROUND


def chunk(tag, data):
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path, size):
    rows = []
    for y in range(size):
        row = bytearray([0])  # filter: none
        for x in range(size):
            r, g, b = colour_at(x, y)
            row += bytes((r, g, b, 255))
        rows.append(bytes(row))

    raw = b"".join(rows)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)
    return len(png)


def main():
    target = os.path.join(
        "ios", "RemoteScreen", "Assets.xcassets", "AppIcon.appiconset", "AppIcon-1024.png"
    )
    size = write_png(target, SIZE)
    print("wrote {} ({} bytes)".format(target, size))


if __name__ == "__main__":
    main()
