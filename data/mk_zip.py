# -*- coding: utf-8 -*-
"""重建 EnglishApp-SourceReview.zip（含测试）并写 SHA256SUMS.txt。"""
import zipfile, hashlib, os, sys
sys.stdout.reconfigure(encoding='utf-8')
ROOT = r"D:\手机\re\ccg"
PRE  = "EnglishApp-SourceReview/"
FILES = [
    ("IMPLEMENTATION-STATUS.md", "IMPLEMENTATION-STATUS.md"),
    ("TEST-EVIDENCE.md",         "TEST-EVIDENCE.md"),
    ("README.md",                "README.md"),
    ("验收-20261105.md",         "docs/验收-20261105.md"),
    ("android/src/com/aurora/word/Fsrs.java",        "src/com/aurora/word/Fsrs.java"),
    ("android/src/com/aurora/word/Db.java",          "src/com/aurora/word/Db.java"),
    ("android/src/com/aurora/word/MainActivity.java","src/com/aurora/word/MainActivity.java"),
    ("android/AndroidManifest.xml", "AndroidManifest.xml"),
    ("android/res/values/styles.xml","res/values/styles.xml"),
    ("android/build.sh",         "build.sh"),
    ("android/pack.py",          "pack.py"),
    ("android/assets/words.json","assets/words.json"),
    ("android/assets/forms.json","assets/forms.json"),
    ("data/build_lexicon.py",    "data/build_lexicon.py"),
    ("data/colloquial.py",       "data/colloquial.py"),
    ("data/rebuild_select.py",   "data/rebuild_select.py"),
    ("data/selfcheck.py",        "data/selfcheck.py"),
    ("tests/FsrsTest.java",      "tests/FsrsTest.java"),
    ("tests/run_tests.sh",       "tests/run_tests.sh"),
    ("tests/RESULT.txt",         "tests/RESULT.txt"),
]
out = os.path.join(ROOT, "EnglishApp-SourceReview.zip")
lines = []
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for src, dst in FILES:
        p = os.path.join(ROOT, src)
        if not os.path.exists(p):
            print("MISS", src); continue
        data = open(p, "rb").read()
        z.writestr(PRE + dst, data)
        lines.append("%s  %s" % (hashlib.sha256(data).hexdigest(), dst))
    z.writestr(PRE + "SHA256SUMS.txt", "\n".join(lines) + "\n")
n = len(zipfile.ZipFile(out).namelist())
h = hashlib.sha256(open(out, "rb").read()).hexdigest()
sz = os.path.getsize(out)
open(out + ".sha256", "w").write(h + "  EnglishApp-SourceReview.zip\n")
print("zip %d B  sha256 %s  members %d" % (sz, h, n))
