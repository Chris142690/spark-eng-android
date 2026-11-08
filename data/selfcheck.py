# -*- coding: utf-8 -*-
"""离线自检：对 words.json 全量跑中文判定与挖空生成，复刻 MainActivity 的 matchCn / blank 规则。
用法: python selfcheck.py [words.json 路径]"""
import json, os, re, sys
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

PATH = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "words.json")

def norm_cn(s):
    return "".join(c for c in (s or "") if "\u4e00" <= c <= "\u9fff")

def match_cn(raw, cn):
    a = norm_cn(raw)
    if not a:
        return False
    for p in re.split(r"[；;，,\n、/（）()]", cn or ""):
        b = norm_cn(p)
        if b and a == b:
            return True
    return False

def norm_en(s):
    return re.sub(r"[^a-z'-]", "", (s or "").strip().lower())

def match_en(raw, w):
    a, b = norm_en(raw), norm_en(w)
    if not b:
        return False
    if a == b:
        return True
    return a in {b + "s", b + "es", b + "ed", b + "d", b + "ing", b + "'s"}

def blank(ex, w):
    if not ex or not w:
        return ""
    q = re.escape(w) + r"(?:s|es|ed|d|ing|'s)?"
    return re.sub(r"(?i)(?<![A-Za-z])" + q + r"(?![A-Za-z])", "______", ex)

def main():
    W = json.load(open(PATH, encoding="utf-8"))
    print("words:", len(W), "| path:", PATH)
    bad_cn, bad_blank, bad_ex, dup = [], [], [], set()
    seen = set()
    for x in W:
        parts = [p for p in re.split(r"[；;，,\n、/（）()]", x["cn"]) if norm_cn(p)]
        if not parts:
            bad_cn.append((x["w"], "无有效义项"))
        elif not match_cn(parts[0], x["cn"]):
            bad_cn.append((x["w"], parts[0]))
        if "______" not in blank(x["ex"], x["w"]):
            bad_blank.append((x["w"], x["ex"]))
        if x["w"].lower() not in (x["ex"] or "").lower():
            bad_ex.append(x["w"])
        if x["w"] in seen:
            dup.add(x["w"])
        seen.add(x["w"])
    print("中文首义项自匹配失败:", len(bad_cn), bad_cn[:5])
    print("挖空失败:", len(bad_blank), bad_blank[:5])
    print("例句不含目标词:", len(bad_ex), bad_ex[:5])
    print("重复词:", len(dup), list(dup)[:5])
    ok = not (bad_cn or bad_ex or dup)
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1

sys.exit(main())
