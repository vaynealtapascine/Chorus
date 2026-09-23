"""Decode the QR samples written by `qr::tests::writes_samples_when_asked` with OpenCV.

    CHORUS_QR_SAMPLES=<dir> cargo test -p chorus-server --lib qr
    python scripts/qr-check.py <dir>

Exits non-zero if any sample doesn't decode to its text.
"""
import pathlib
import sys

import cv2

d = pathlib.Path(sys.argv[1])
bad = 0
for img in sorted(d.glob("qr*.pgm")):
    want = img.with_suffix(".txt").read_text(encoding="utf-8")
    got, _, _ = cv2.QRCodeDetector().detectAndDecode(cv2.imread(str(img), cv2.IMREAD_GRAYSCALE))
    ok = got == want
    bad += not ok
    print(f"{img.name}: {'ok' if ok else 'MISMATCH'} ({len(want.encode())} bytes){'' if ok else f' got {got!r}'}")
sys.exit(1 if bad else 0)
