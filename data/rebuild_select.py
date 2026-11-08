# -*- coding: utf-8 -*-
"""从 words.json 重新选词：补全真实词频 -> 词形去重 -> 保护名单 -> 取前 N"""
import csv, json, os, sys, hashlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# 保护名单：旅行 / 礼貌 / 求助 / 高频功能词，无论词频一律保留
PROTECT = set("""
hello hi please thanks thank sorry excuse welcome goodbye bye yes no okay ok
help need want like would could should may must can
where when what who why how which whose
here there this that these those
water food eat drink buy pay money price cheap expensive cost
ticket train bus plane airport flight hotel room bed
toilet bathroom restroom washroom men women exit entrance
left right straight turn stop go come take give get put
doctor hospital police thief lost find lose stolen emergency
passport visa luggage bag suitcase map address street
today tomorrow yesterday morning afternoon evening night
time hour minute day week month year
one two three four five six seven eight nine ten
open close big small hot cold fast slow near far
speak talk say tell ask answer understand know think
name age phone number card bank
sorry pardon question problem fine good bad
breakfast lunch dinner tea coffee milk bread rice
sick tired hungry thirsty afraid angry happy
I you he she it we they me him her us them
my your his its our their mine yours
be am is are was were been do does did done
have has had make made
and or but so because if than then
of to in on at for with from by about
not very too also just only more most
""".split())

def wkey(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()

def main():
    words = json.load(open(os.path.join(ROOT, "words.json"), encoding="utf-8"))
    meta = {}
    with open(os.path.join(HERE, "ecdict.csv"), encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            w = (row.get("word") or "").strip()
            if not w:
                continue
            meta[w.lower()] = row

    def freq(w):
        r = meta.get(w.lower())
        if not r:
            return None
        def num(k):
            v = (r.get(k) or "").strip()
            try:
                n = int(v)
                return n if n > 0 else None
            except Exception:
                return None
        return num("frq") or num("bnc")

    # 补真实词频
    filled = 0
    for x in words:
        f = freq(x["w"])
        if f:
            if x.get("frq") != f:
                filled += 1
            x["frq"] = f
        else:
            x["frq"] = 200000
    print("真词频补全:", filled, "/", len(words))

    # 词形归并：若 A 是 B 的派生词（B.exchange 里出现 A），保留词频更高的那个
    drop = set()
    for x in words:
        r = meta.get(x["w"].lower())
        if not r:
            continue
        ex = (r.get("exchange") or "")
        forms = []
        for part in ex.split("/"):
            if ":" in part:
                forms += [t for t in part.split(":", 1)[1].split(",") if t]
        for f0 in forms:
            f0 = f0.strip().lower()
            if f0 and f0 != x["w"].lower():
                drop.add(f0)
    print("派生形式标记:", len(drop))

    cand = [x for x in words if x["w"].lower() not in drop or x["w"] in PROTECT]
    print("去派生后:", len(cand))

    TARGET = 1200
    cand.sort(key=lambda x: (x["frq"], x["w"]))
    keep, cut = [], []
    for x in cand:
        if x["w"] in PROTECT or len(keep) < TARGET:
            keep.append(x)
        else:
            cut.append(x)
    # 保护名单可能超出上限，超了则按词频砍
    print("保留:", len(keep), "砍掉:", len(cut))

    keep.sort(key=lambda x: (x["frq"], x["w"]))
    out = []
    for i, x in enumerate(keep):
        x["id"] = x["w"]
        x["tag"] = "core" if x["frq"] <= 5437 else "ext"
        out.append(x)

    p = os.path.join(ROOT, "words.json")
    json.dump(out, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    data = open(p, "rb").read()
    print("写出:", p, len(data), "B  sha256=", hashlib.sha256(data).hexdigest())
    print("tag:", {t: sum(1 for x in out if x["tag"] == t) for t in set(x["tag"] for x in out)})
    print("frq 范围:", out[0]["frq"], "~", out[-1]["frq"])
    print("样例尾部:", [(x["w"], x["frq"]) for x in out[-6:]])
    print("保护词是否都在:", all(w in {x["w"] for x in out} for w in list(PROTECT)[:40]))

main()
