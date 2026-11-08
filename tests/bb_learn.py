import sys, time, json, re, os
sys.path.insert(0, r"D:\手机\re\ccg\tests")
import smoke_adb as S

MEM_PATH = r"D:\手机\re\ccg\tests\shots\bb-mean.json"
mem = {}
if os.path.exists(MEM_PATH):
    mem = json.load(open(MEM_PATH, encoding="utf-8"))

POS = ("n.", "adj.", "adv.", "v.", "vt.", "vi.", "prep.", "conj.", "pron.", "num.", "art.")

def cn(s):
    return set(re.findall(r"[\u4e00-\u9fff]", s))

def parse(nodes):
    txt = [(t, x, y) for t, x, y in nodes]
    word = None
    for t, x, y in txt:
        if y < 500 and re.fullmatch(r"[a-zA-Z][a-zA-Z\- ]{1,20}", t or ""):
            word = t.strip()
            break
    prog = next((t for t, x, y in txt if t.endswith("/10")), "")
    opts = []
    for t, x, y in txt:
        if y > 900 and y < 2000 and t and t.strip() and not t.startswith("看答案"):
            if t.startswith(POS) or len(cn(t)) >= 2:
                if cn(t):
                    opts.append((t, x, y))
    return word, prog, opts, [t for t, x, y in txt]

def tap(x, y):
    S.adb("shell", "input", "tap", str(x), str(y))

for i in range(45):
    nodes = S.dump_ui("LR%d" % i)
    word, prog, opts, txt = parse(nodes)
    has_ans = "看答案" in txt
    reveal = "下一词" in txt
    tag = "quiz" if has_ans else ("reveal" if reveal else "other")
    print(i, prog, tag, word, " opts=%d" % len(opts), txt[6:10])
    if reveal:
        # 记录正确释义
        if word:
            mean = " ".join(t for t, x, y in nodes if y > 600 and y < 760)
            if cn(mean):
                mem[word] = mean
        for t, x, y in nodes:
            if t == "下一词":
                tap(x, y)
                break
    elif has_ans:
        best, bs = None, 0
        if word in mem:
            m = cn(mem[word])
            for t, x, y in opts:
                s = len(cn(t) & m)
                if s > bs:
                    best, bs = (t, x, y), s
        if best and bs >= 2:
            print("   -> pick", best[0][:20], "score", bs)
            tap(best[1], best[2])
        else:
            print("   -> 看答案")
            for t, x, y in nodes:
                if t == "看答案":
                    tap(x, y)
                    break
    elif "继续" in txt:
        for t, x, y in nodes:
            if t == "继续":
                tap(x, y)
                break
    elif "完成" in txt or "单词辨识完成" in txt:
        for t, x, y in nodes:
            if t in ("完成", "单词辨识完成"):
                tap(x, y)
                break
        break
    else:
        print("STOP:", txt[:16])
        break
    time.sleep(2.2)

json.dump(mem, open(MEM_PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("mem:", {k: v[:16] for k, v in mem.items()})
S.adb("shell", "screencap", "-p", "/sdcard/lr-end.png")
S.adb("pull", "/sdcard/lr-end.png", r"D:\手机\re\ccg\tests\shots\n5s-learn-end.png")
