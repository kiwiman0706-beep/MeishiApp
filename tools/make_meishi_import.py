#!/usr/bin/env python3
"""Build a Meishi-Claude import ZIP (cards.csv + images/) from a CSV and an image folder.

Format: docs/IMPORT_FORMAT.md. Standard library only (Python 3.8+).

Examples
--------
# Eight CSV + images named after a column value (e.g. 名刺ID_1.jpg / 名刺ID_2.jpg)
python make_meishi_import.py eight.csv --images ./eight_images \
    --front "{名刺ID}_1.jpg" --back "{名刺ID}_2.jpg" -o meishi-import.zip

# CSV that already has 表面画像 / 裏面画像 columns with file names
python make_meishi_import.py cards.csv --images ./images -o meishi-import.zip
"""
import argparse
import csv
import io
import os
import re
import sys
import zipfile

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".pdf"}


def read_csv(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp932"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        sys.exit(f"文字コードを判別できません: {path}（UTF-8かShift_JISで保存してください）")
    rows = list(csv.reader(io.StringIO(text)))
    if len(rows) < 2:
        sys.exit("CSVにデータ行がありません")
    return rows[0], rows[1:]


def fill(pattern, record):
    """'{名刺ID}_1.jpg' → '12345_1.jpg' using the row's own columns."""
    def sub(m):
        key = m.group(1)
        if key not in record:
            sys.exit(f"--front/--back の {{{key}}} がCSVの列にありません。列名: {', '.join(record)}")
        return record[key].strip()
    return re.sub(r"\{([^{}]+)\}", sub, pattern)


def find(images, name, index):
    """Exact path first, then a case-insensitive match on the bare file name."""
    if not name:
        return None
    direct = os.path.join(images, name)
    if os.path.isfile(direct):
        return direct
    return index.get(os.path.basename(name).lower())


def main():
    ap = argparse.ArgumentParser(description="Meishi-Claude用のインポートZIPを作ります")
    ap.add_argument("csv", help="名刺データのCSV（Eightの書き出しなど）")
    ap.add_argument("--images", help="名刺画像のフォルダ（サブフォルダも検索）")
    ap.add_argument("--front", help="表面画像のファイル名パターン 例: \"{名刺ID}_1.jpg\"")
    ap.add_argument("--back", help="裏面画像のファイル名パターン 例: \"{名刺ID}_2.jpg\"")
    ap.add_argument("-o", "--out", default="meishi-import.zip", help="出力ZIP（既定: meishi-import.zip）")
    a = ap.parse_args()

    header, rows = read_csv(a.csv)
    index = {}
    if a.images:
        for root, _, files in os.walk(a.images):
            for f in files:
                if os.path.splitext(f)[1].lower() in IMAGE_EXT:
                    index.setdefault(f.lower(), os.path.join(root, f))

    # Image columns: use existing ones, or add them when patterns are given.
    out_header = list(header)
    for col in ("表面画像", "裏面画像"):
        if col not in out_header:
            out_header.append(col)
    fi, bi = out_header.index("表面画像"), out_header.index("裏面画像")

    missing, used, out_rows = [], {}, []
    for n, row in enumerate(rows, start=2):
        if not any(c.strip() for c in row):
            continue
        record = dict(zip(header, row + [""] * (len(header) - len(row))))
        out = [record.get(h, "") for h in header] + [""] * (len(out_header) - len(header))
        for col, pattern, i in (("表面画像", a.front, fi), ("裏面画像", a.back, bi)):
            name = fill(pattern, record) if pattern else record.get(col, "").strip()
            if not name:
                continue
            src = find(a.images, name, index) if a.images else None
            if not src:
                missing.append(f"{n}行目 {col}: {name}")
                out[i] = ""
                continue
            # Unique, ASCII-safe names inside the ZIP.
            arc = used.get(src) or f"images/{len(used) + 1:05d}{os.path.splitext(src)[1].lower()}"
            used[src] = arc
            out[i] = arc
        out_rows.append(out)

    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    w.writerow(out_header)
    w.writerows(out_rows)
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("cards.csv", "﻿" + buf.getvalue())
        for src, arc in used.items():
            z.write(src, arc, compress_type=zipfile.ZIP_STORED)

    print(f"作成: {a.out}  名刺 {len(out_rows)}件 / 画像 {len(used)}枚")
    if missing:
        print(f"\n見つからなかった画像 {len(missing)}件（その人は画像なしで取り込まれます）:")
        for m in missing[:50]:
            print("  " + m)
        if len(missing) > 50:
            print(f"  …ほか{len(missing) - 50}件")


if __name__ == "__main__":
    main()
