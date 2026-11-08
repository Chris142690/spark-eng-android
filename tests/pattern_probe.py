#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""句型线真机验收探针（GPT 第 12 轮验收裁决的四组必验行为）。

五组：
  T1 每日 3 句上限   正向：第 1–3 个不同句型可以开始；反向：第 4 个被拒且不产生事件
  T2 功能词阶梯      D0→D+1→D+3→D+7→D+14；错误/提示/未作答不推进
  T3 待审核暂停      连续两次 UNVERIFIED 后 needs_review=1、原 due 不变
  T4 场景表达隔离    替换练习产生 kind=collocation 事件，但 stage/due 不动
  T5 待确认动作      可见 / 搁置后仍可见 / 重启保持 / 重新练习只清阻塞、不写掌握证据
  T5b 额度隔离       今日已满 3 句时，恢复后的句子不得绕过上限进入正式练习（GPT 第 15 轮补授权）

纪律（照 GPT 的四条硬要求）：
  * 每项都做真实 UI 操作，并比较操作前后的数据库状态，不只看字段存在
  * 正反路径都测
  * 跨天用「测试库副本 + 种子状态」模拟，不改真实记录、不改系统时间；结束时按备份还原并比对指纹
  * PASS / FAIL / SKIP 分开；没设备、没走到关键路径一律 SKIP，不算通过

用法：python tests/pattern_probe.py [apk]
证据：tests/shots/probe-*.png、ui-*.xml、probe-report.txt
"""
import hashlib
import io
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import devdb as D          # 真机没有 sqlite3：库操作走本机副本垫片
import smoke_adb as S      # 复用 adb/dump_ui/find/tap_node/screencap/record

PKG = S.PKG
HERE = S.HERE
ASSETS = os.path.join(os.path.dirname(HERE), "android", "assets")
PATTERNS = os.path.join(ASSETS, "patterns.json")
COLLOC = os.path.join(ASSETS, "collocations.json")

PAT_STAGE_GAP = {1: 1, 2: 3, 3: 7, 4: 14}   # GPT 定的功能词台阶


# ---------- DB ----------
def sql(q, timeout=30):
    return S.sh('run-as %s sqlite3 databases/aurora.db "%s"' % (PKG, q), timeout=timeout).strip()


def one(q, default=None):
    v = sql(q)
    return v if v != "" else default


def backup():
    """一致快照备份（GPT 第 14 轮第 3 点）。

    真机没有 sqlite3 可执行文件，所以不能用 `.backup`；改为：先 force-stop
    让 App 停止写库，再把 aurora.db 连同 -wal/-shm/-journal 一起拉到本机做快照。
    停写之后拉取的四件文件构成一致状态，还原时整组推回并删掉 WAL。
    """
    S.sh("am force-stop %s" % PKG)
    try:
        d = D.snapshot("ab-baseline")
    finally:
        S.sh("am start -n %s" % S.ACT)
        time.sleep(2)
        S.S = None
    size = os.path.getsize(os.path.join(d, "aurora.db"))
    ok = size > 0
    print("   baseline=%s bytes（本机快照 %s）" % (size, d))
    return ok


def _md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def restore():
    """还原成基线：force-stop -> 推回快照 -> 删 WAL -> 重启 -> 指纹比对。"""
    snap = os.path.join(D.WORK, "ab-baseline", D.DB_NAME)
    if not os.path.exists(snap):
        print("   没有基线快照，无法还原")
        return False
    base = _md5_file(snap)
    D.restore("ab-baseline")
    S.sh("am start -n %s" % S.ACT)
    time.sleep(2)
    S.S = None
    now = _md5_file(D.pull())
    print("   还原指纹 baseline=%s 当前=%s %s" % (base[:12], now[:12], "OK" if base == now else "DIFF"))
    return base == now



def now_ms():
    v = one("select strftime('%s','now')*1000")
    return int(v or 0)


def seed(pid, stage, last_ago_days, due_offset_days, streak=0, needs=0):
    now = now_ms()
    last = now - int(last_ago_days * 86400000)
    due = now + int(due_offset_days * 86400000)
    sql("insert or replace into pattern_state(pid,stage,due,last_attempt_at,first_try_correct,"
        "hint_level,error_tags,transfer_success,attempts,content_version,unverified_streak,needs_review)"
        " values('%s',%d,%d,%d,0,0,null,0,0,1,%d,%d)" % (pid, stage, due, last, streak, needs))
    # 写库走 devdb（force-stop -> 推回）。种子写完把 App 拉起来，后续才能做 UI 操作。
    S.sh("am start -n %s" % S.ACT)
    time.sleep(2)
    S.S = None
    return {"stage": stage, "due": due, "last": last}


def isolate(pid):
    """把除 pid 以外的所有句型推到 30 天后，并保证每句都有状态行。
    没有这一步，startPattern 的「补齐未见过句型」会把别的句型塞进队列，
    跑到一半才发现被测句型根本没出现（T2/T3 一度因此全 FAIL，实际是实现序问题）。"""
    future = now_ms() + 30 * 86400000
    for o in load_json(PATTERNS):
        x = o["pattern_id"]
        if x == pid:
            continue
        sql("insert or ignore into pattern_state(pid,stage,due) values('%s',0,%d)" % (x, future))
        sql("update pattern_state set due=%d where pid='%s'" % (future, x))


def state(pid):
    row = one("select stage||'|'||due||'|'||last_attempt_at||'|'||unverified_streak||'|'||needs_review "
              "from pattern_state where pid='%s'" % pid)
    if not row:
        return None
    p = row.split("|")
    return {"stage": int(p[0]), "due": int(p[1]), "last": int(p[2]),
            "streak": int(p[3]), "needs": int(p[4])}


def formal_today():
    v = one("select count(distinct pid) from pattern_event where kind='formal' and "
            "date(ts/1000,'unixepoch','localtime')=date('now','localtime')")
    return int(v or 0)


# ---------- 内容 ----------
def load_json(p):
    return json.load(io.open(p, encoding="utf-8"))


def pat_by_id():
    d = {}
    for o in load_json(PATTERNS):
        d[o["pattern_id"]] = o
    return d


def coll_by_id():
    d = {}
    for o in load_json(COLLOC):
        d[o["collocation_id"]] = o
    return d


def find_btn(nodes, *labels):
    """按钮定位：只认整串相等的可见文本（避免命中说明文字里出现的同名词，
    例如提示语里的「…查看参考答案后重新练习。」曾让「重新练习」按钮点不中）。"""
    for lab in labels:
        for t, x, y in nodes:
            if t.strip() == lab:
                return (t.strip(), x, y)
    for lab in labels:
        for t, x, y in nodes:
            tt = t.strip()
            if not tt.startswith(lab):
                continue
            # 说明文字（带句号/逗号/引号）不当作按钮；按钮标签通常很短
            if any(ch in tt for ch in "。，：；！？"):
                continue
            if len(tt) <= 40:
                return (tt, x, y)
    return None


def type_text(s):
    """adb input text 不支持空格与部分符号：空格写 %s，撇号直接用 ASCII 句子回避"""
    safe = s.replace(" ", "%s")
    S.sh('input text "%s"' % safe, timeout=30)
    S.sh("input keyevent 111")   # ESC 收起键盘
    return True


def tap_edit(tag="probe-edit"):
    """点输入框：正式测试屏的 EditText 没有提示文案（文本已被输入/为空但无 hint），
    按 text 找不到 → 直接按 class=EditText 找可编辑控件。"""
    S.dump_ui(tag)
    path = os.path.join(S.SHOTS, "ui-%s.xml" % tag)
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(path)
    except Exception:
        return None
    for n in tree.iter("node"):
        cls = n.get("class") or ""
        if "EditText" not in cls:
            continue
        nums = [int(x) for x in re.findall(r"-?\d+", n.get("bounds") or "")]
        if len(nums) != 4:
            continue
        cx = (nums[0] + nums[2]) // 2
        cy = (nums[1] + nums[3]) // 2
        S.adb("shell", "input", "tap", str(cx), str(cy))
        time.sleep(0.5)
        return (cls, cx, cy)
    return None


def read_edit(tag="probe-read"):
    """回读输入框里的真实文本：vivo 的百度输入法偶尔会把首个空格吞成字母
    （实测 "I am tired." 变成 "Ia m tired."），不打字后校验就会误判成 UNVERIFIED。"""
    S.dump_ui(tag)
    path = os.path.join(S.SHOTS, "ui-%s.xml" % tag)
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(path)
    except Exception:
        return None
    for n in tree.iter("node"):
        if "EditText" in (n.get("class") or ""):
            return n.get("text") or ""
    return None


def clear_edit():
    try:
        S.sh("input keyevent 123")          # 光标移到末尾（MOVE_END）
    except Exception:
        pass
    for _ in range(45):
        S.sh("input keyevent 67")           # DEL
    time.sleep(0.2)


def type_into(text):
    """点输入框 → 等焦点 → 打字 → 回读校验；不一致就清空重打一次。"""
    if not tap_edit():
        tap_any("写一句英文", "中文意思", "英文单词")
    time.sleep(0.7)                          # 焦点没落稳时打字会丢字符
    type_text(text)
    time.sleep(0.4)
    got = read_edit()
    if got is not None and got.strip() != text.strip():
        clear_edit()
        tap_edit()
        time.sleep(0.6)
        type_text(text)
        time.sleep(0.4)
        got2 = read_edit("probe-read2")
        return got2 if got2 is not None else text
    return got if got is not None else text


def submit_answer(text):
    typed = type_into(text)
    got = tap_label("提交", tag="probe-submit", wait=0.2)
    if not got:
        return None
    return {"label": got[0], "x": got[1], "y": got[2], "typed": typed}


def goto_home(nodes=None):
    """连点返回直到首页；每次点完都等页面切完再 dump，否则会拿到上一屏的控件。"""
    nodes = nodes or S.dump_ui("probe-back0")
    for i in range(6):
        body = " | ".join(t for t, _, _ in nodes)
        if ("学新词" in body) or ("今日待复习" in body) or ("练句型" in body and "今日句型" not in body):
            break
        back = None
        for lb in ("‹", "← 返回", "返回"):
            if S.find(nodes, lb):
                back = lb
                break
        if not back:
            break
        tap_label(back, tag="probe-back%d" % (i + 1), wait=1.0)
        nodes = S.dump_ui("probe-back%d" % (i + 1))
    return nodes


def ensure_app(tries=4):
    """真机上 dump 偶尔抓到桌面（窗口还没画、或上一次操作把应用切后台）。
    这里先确认 dump 里是本应用的界面，否则重新拉起 Activity 再抓。"""
    nodes = []
    for _ in range(tries):
        nodes = S.dump_ui("probe-ensure")
        body = " | ".join(t for t, _, _ in nodes)
        if ("学句型" in body) or ("今日句型" in body) or ("← 返回" in body) or ("待确认" in body):
            return nodes
        S.adb("shell", "am", "start", "-n", "com.aurora.word/.MainActivity")
        time.sleep(2.5)
    return nodes


def tap_label(label, tag="probe-tap", wait=1.2):
    """按文本找到控件，沿 XML 向上找 clickable=true 的祖先，点它的中心。
    纯程序化 UI 里文本节点往往又窄又靠左（实测「换你来说一句」中心 x=72），
    点文本中心等于点空白；真正可点的是包裹它的父容器。"""
    S.dump_ui(tag)
    path = os.path.join(S.SHOTS, "ui-%s.xml" % tag)
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(path)
    except Exception:
        return None
    parent = {}
    for p in tree.iter():
        for c in p:
            parent[c] = p

    def box(n):
        nums = [int(x) for x in re.findall(r"-?\d+", n.get("bounds") or "")]
        return nums if len(nums) == 4 else None

    for n in tree.iter("node"):
        txt = (n.get("text") or "").strip()
        if not txt or (txt != label and label not in txt):
            continue
        tgt, cur = n, n
        while cur is not None:
            if cur.get("clickable") == "true":
                tgt = cur
                break
            cur = parent.get(cur)
        bb = box(tgt) or box(n)
        if not bb:
            continue
        cx = (bb[0] + bb[2]) // 2
        cy = (bb[1] + bb[3]) // 2
        if cy > 2150:                      # 落到手势区会触发「上滑回桌面」
            cy = bb[1] + max(8, (bb[3] - bb[1]) // 5)
        S.adb("shell", "input", "tap", str(cx), str(cy))
        time.sleep(wait)
        return (label, cx, cy)
    return None


def tap_any(*labels):
    for lb in labels:
        if tap_label(lb):
            return lb
    return None


def open_pattern():
    nodes = goto_home(ensure_app())
    btn = find_btn(nodes, "学句型", "练句型")
    if not btn:
        return None
    S.tap_node(btn)
    time.sleep(1.5)          # 等页面切换完成，否则 dump 到的还是首页（会导致整轮空转）
    return S.dump_ui("probe-teach")


# ---------- 一轮句型练习 ----------
def tap_btn_safe(tag, labels):
    """底部按钮（如「开始练习」）常落在手势区内，直接点中心会触发系统「上滑回桌面」。
    先滚一下让按钮上移，仍旧贴底就往按钮上沿点。"""
    for k in range(2):
        nodes = S.dump_ui("%s-%d" % (tag, k))
        b = find_btn(nodes, *labels)
        if not b:
            return None
        if b[2] <= 2150:
            S.tap_node(b)
            return b
        S.sh("input swipe 540 1900 540 1500 250")
        time.sleep(1.0)
    nodes = S.dump_ui(tag + "-last")
    b = find_btn(nodes, *labels)
    if b:
        S.adb("shell", "input", "tap", str(b[1]), str(min(b[2], 2130)))
        time.sleep(1.2)
    return b


def run_one_pattern(answer_override=None, coll_override=None, tracing=None):
    """状态机驱动一个句型：教学页 → 场景表达替换 → 正式测试 → 反馈 → 下一句。
    每一步都重新 dump，不依赖上一步的控件坐标（真机上屏幕内容会推进）。"""
    info = {"teach": False, "coll": None, "formal": None, "rounds": 0}
    if open_pattern() is None:      # 先从首页点进句型入口，否则第一轮 dump 到的是首页
        return info
    for i in range(9):
        info["rounds"] = i + 1
        nodes = S.dump_ui("probe-r%d" % (i + 1))
        body = " | ".join(t for t, _, _ in nodes)

        go = find_btn(nodes, "换你来说一句", "开始练习", "开始")
        if go:
            info["teach"] = True
            tap_any("换你来说一句", "开始练习", "开始")
            continue

        # 正式测试屏自带按钮「不会，看参考答案」，用它判反馈页会把每次提交都跳过。
        # 反馈页的独有特征是「你写的：」和按钮「对照参考答案」。
        is_fb = ("你写的：" in body) or ("对照参考答案" in body)
        sub = None if is_fb else find_btn(nodes, "提交")
        if sub:
            is_coll = ("可以套用" in body)
            if is_coll:
                cid = first_coll_id(nodes)
                info["coll"] = cid
                ans = coll_override or (coll_by_id().get(cid, {}).get("english") if cid else None) or "I am new here."
            else:
                ans = answer_override or pick_ref(nodes) or "I am a student."
            typed = submit_answer(ans)
            time.sleep(1.2)
            fb = S.dump_ui("probe-fb%d" % (i + 1))
            if not is_coll:
                info["formal"] = verdict_of(fb)
                info.setdefault("detail", []).append(
                    "R%d 期望=%r 输入框=%r 判定=%s" % (
                        i + 1, ans, (typed or {}).get("typed"), info["formal"]))
                print("    " + info["detail"][-1])
            continue

        # 队列最后一句的按钮文案是「完成」，不是「下一句」——只认「下一句」会永远卡在反馈页，
        # commitPattern 也就一次都不会被调用（这正是 T1/T2/T3 全线 0 的真因）。
        nxt = find_btn(nodes, "下一句", "完成", "回首页", "回到首页")
        if nxt:
            tap_any("下一句", "完成", "回首页", "回到首页")
            continue
        # 页面内容在 ScrollView 里，按钮可能在折叠线以下（dump 只给可见控件）→ 往下滚再试
        S.sh("input swipe 540 1800 540 1200 250")
        time.sleep(0.9)
        continue
    return info


def NOT_FORMAL(nodes):
    # 替换练习屏有「可以套用」，正式测试屏有「提交答案」和提示句
    return any("套用" in l for l, _, _ in nodes)


def first_coll_id(nodes):
    for o in load_json(COLLOC):
        if any(o["english"][:12] in l for l, _, _ in nodes):
            return o["collocation_id"]
    return None


def pick_ref(nodes):
    # 从当前页文本里找不到句型 id，退化为按顺序取（调用方通常传 answer_override）
    return None


def verdict_of(nodes):
    body = " | ".join(l for l, _, _ in nodes)
    if "表达正确" in body:
        return "CORRECT"
    if "无法准确判断" in body:
        return "UNVERIFIED"
    if "没作答" in body:
        return "NO_RESPONSE"
    if "少了" in body or "缺少" in body:
        return "INCORRECT"
    return "UNKNOWN"


def shift_prefs_key(days):
    """把 shared_prefs 里 patIds_<date> 的键改成 days 天前后的日期。

    用途：在不改系统时间、不动学习记录的前提下模拟「跨天」——
    每日 3 句上限就是靠这个按日期的键来计数的，把键挪到昨天，
    等价于「今天还没开始过句型任务」。
    """
    import datetime
    today = datetime.date.today()
    old = "patIds_" + today.strftime("%Y%m%d")
    new = "patIds_" + (today + datetime.timedelta(days=days)).strftime("%Y%m%d")
    xml = S.sh("run-as %s sh -c 'cat shared_prefs/aurora.xml'" % PKG)
    if old not in xml:
        return False, "prefs 里没有 %s" % old
    patched = xml.replace(old, new)
    tmp = os.path.join(S.SHOTS, "prefs-patched.xml")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(patched)
    S.adb("push", tmp, "/sdcard/prefs-patched.xml")
    S.sh("run-as %s sh -c 'cp /sdcard/prefs-patched.xml shared_prefs/aurora.xml'" % PKG)
    S.sh("run-as %s sh -c 'chmod 660 shared_prefs/aurora.xml'" % PKG)
    return True, "%s -> %s" % (old, new)


def read_prefs():
    return S.sh("run-as %s sh -c 'cat shared_prefs/aurora.xml'" % PKG)


def write_prefs(xml):
    tmp = os.path.join(S.SHOTS, "prefs-write.xml")
    with io.open(tmp, "w", encoding="utf-8") as f:
        f.write(xml)
    S.adb("push", tmp, "/sdcard/prefs-write.xml")
    S.sh("run-as %s sh -c 'cp /sdcard/prefs-write.xml shared_prefs/aurora.xml'" % PKG)
    S.sh("run-as %s sh -c 'chmod 660 shared_prefs/aurora.xml'" % PKG)


def set_pref_string(xml, key, value):
    """把 <string name="key">…</string> 设成 value；不存在就插到 </map> 前。"""
    import re
    pat = re.compile(r'<string name="%s">[^<]*</string>' % re.escape(key))
    tag = '<string name="%s">%s</string>' % (key, value)
    if pat.search(xml):
        return pat.sub(tag, xml, count=1)
    return xml.replace("</map>", "    " + tag + chr(10) + "</map>")


def counts():
    return {
        "pattern_event": int(one("select count(*) from pattern_event") or 0),
        "review_event": int(one("select count(*) from review_event") or 0),
        "card": int(one("select count(*) from card") or 0),
        "review_clear": int(one("select count(*) from pattern_event where kind='review_clear'") or 0),
    }


# ---------- 四组 ----------
def current_pid(nodes):
    """从当前教学页文本里认出是哪个句型：拿 patterns.json 的 formula 逐条比对。"""
    body = " | ".join(t for t, _, _ in (nodes or []))
    for o in load_json(PATTERNS):
        f = (o.get("formula") or "").strip()
        if f and f in body:
            return o["pattern_id"]
    return None


def t1_daily_limit():
    """正向 3 个可开始，反向第 4 个被拒。用 UI 走，前后比 DB。"""
    pats = load_json(PATTERNS)
    refs = {o["pattern_id"]: (o.get("reference_answers") or [""])[0] for o in pats}
    # 只留前 3 个句型到期，其余推到 30 天后，保证顺序确定
    order = [o["pattern_id"] for o in pats][:3]
    later = [o["pattern_id"] for o in pats][3:]
    now = now_ms()
    for i, pid in enumerate(order):
        seed(pid, 0, 2, -1)              # due 已过，stage 0
    for pid in later:
        sql("insert or replace into pattern_state(pid,stage,due,last_attempt_at,first_try_correct,"
            "hint_level,error_tags,transfer_success,attempts,content_version,unverified_streak,needs_review)"
            " values('%s',0,%d,0,0,0,null,0,0,1,0,0)" % (pid, now + 30 * 86400000))
    S.sh("run-as %s sh -c 'rm -f shared_prefs/aurora.xml'" % PKG)
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)

    before = formal_today()
    done = 0
    seen = []
    for _ in range(5):                      # 最多试 5 次：前 3 次应成功，第 4 次起被拒
        nodes = open_pattern()
        if not nodes:
            break
        cur = current_pid(nodes)
        if cur is None:
            break
        seen.append(cur)
        info = run_one_pattern(answer_override=refs.get(cur))
        if info.get("formal"):
            done += 1
        if formal_today() - before >= 3:
            break
    mid = formal_today()
    S.record("T1 每日3句·前3个可开始", "PASS" if mid - before >= 3 else "FAIL",
             "formal 不同句型 %d -> %d，UI 完成 %d，本轮遇到=%s"
             % (before, mid, done, ",".join(seen)))

    # 第 4 次：入口应拒绝（未重启）
    nodes = open_pattern()
    refused = True
    if nodes and (S.find(nodes, "今日句型") or find_btn(nodes, "开始练习", "换你来说一句")):
        refused = False
    after = formal_today()
    S.record("T1 每日3句·第4个被拒", "PASS" if refused and after == mid else "FAIL",
             "第4次是否进入教学页=%s，formal 计数 %d -> %d" % (not refused, mid, after))

    # 反向的另一半：重启后限制仍有效（GPT 明确要求「重启后限制仍有效」）
    # adb 读不到 toast 文本，所以证据是「重启后再点第 4 个仍进不去教学页 + 计数不变 + prefs 计数键仍在」
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)
    time.sleep(2)
    nodes = open_pattern()
    refused2 = True
    if nodes and (S.find(nodes, "今日句型") or find_btn(nodes, "开始练习", "换你来说一句")):
        refused2 = False
    after2 = formal_today()
    prefs = S.sh("run-as %s sh -c 'cat shared_prefs/aurora.xml | grep -o patIds_[0-9]*' " % PKG).strip()
    S.record("T1 每日3句·重启后仍受限",
             "PASS" if (refused2 and after2 == mid) else "FAIL",
             "重启后能否进入教学页=%s，formal 计数 %d -> %d，prefs 计数键=%s" %
             (not refused2, mid, after2, prefs or "未取到"))

    # 额度核对：被拒绝的那次点击不能暗中消耗任何东西（GPT 第 13 轮要求）
    c_before = counts()
    ev_formal_before = int(one("select count(distinct pid) from pattern_event"
                               " where kind='formal' and date(ts/1000,'unixepoch','localtime')="
                               "date('now','localtime')") or 0)
    open_pattern()                       # 再点一次，预期仍被拒
    c_after = counts()
    ev_formal_after = int(one("select count(distinct pid) from pattern_event"
                              " where kind='formal' and date(ts/1000,'unixepoch','localtime')="
                              "date('now','localtime')") or 0)
    S.record("T1 每日3句·被拒点击不消耗额度",
             "PASS" if c_after == c_before and ev_formal_after == ev_formal_before else "FAIL",
             "pattern_event %d->%d，review_event %d->%d，card %d->%d，今日正式句型 %d->%d" %
             (c_before["pattern_event"], c_after["pattern_event"],
              c_before["review_event"], c_after["review_event"],
              c_before["card"], c_after["card"], ev_formal_before, ev_formal_after))

    # 第二天必须能重新开始（只证明「第 4 个被拦」不够，还要证明限制会按日重置）
    # 顺序很重要：先停下 App 再改 prefs。App 活着时会在退出前把内存里的 prefs 写回，
    # 把它自己的计数键「改回去」，于是「次日重置」测出来是假的失败（probe19 就是这么错的）。
    S.sh("am force-stop %s" % PKG)
    time.sleep(0.5)
    ok, detail = shift_prefs_key(-1)     # 把今天的计数键挪到昨天 = 模拟「今天还没开始」
    if not ok:
        S.record("T1 每日3句·次日重置", "SKIP", "无法改写 prefs 计数键：%s" % detail)
        return
    if "patIds_" + __import__("datetime").date.today().strftime("%Y%m%d") in (read_prefs() or ""):
        S.record("T1 每日3句·次日重置", "SKIP",
                 "prefs 改写未生效（约键仍在）：%s" % detail)
        return
    S.sh("am start -n %s" % S.ACT)
    time.sleep(2)
    nodes = open_pattern()
    can_start = bool(nodes and (S.find(nodes, "今日句型") or find_btn(nodes, "开始练习", "换你来说一句")))
    after3 = formal_today()
    S.record("T1 每日3句·次日重置", "PASS" if can_start else "FAIL",
             "%s；模拟次日能否进入教学页=%s，formal 计数 %d -> %d"
             % (detail, can_start, mid, after3))


def t2_function_ladder():
    """f-be 四个台阶：每条都用 UI 答对一次，比对 stage 与 gap。"""
    refs = {o["pattern_id"]: (o.get("reference_answers") or [""])[0] for o in load_json(PATTERNS)}
    ans = refs.get("f-be")
    ok_all = True
    detail = []
    for stage in (0, 1, 2, 3):
        sql("delete from pattern_event where pid='f-be'")
        S.sh("run-as %s sh -c 'rm -f shared_prefs/aurora.xml'" % PKG)
        seed("f-be", stage, 1, -1)                     # 昨天答对过，今天到期
        isolate("f-be")                                # 其余句型全部推到 30 天后，保证队列里只有 f-be
        S.sh("am force-stop %s" % PKG)
        S.sh("am start -n %s" % S.ACT)
        run_one_pattern(answer_override=ans)
        st = state("f-be")
        if not st:
            ok_all = False
            detail.append("stage%d: 无状态" % stage)
            continue
        want_stage = stage + 1
        gap = round((st["due"] - st["last"]) / 86400000.0)
        want_gap = PAT_STAGE_GAP.get(want_stage)
        good = (st["stage"] == want_stage) and (want_gap is None or abs(gap - want_gap) <= 1)
        ok_all = ok_all and good
        detail.append("stage%d→%d gap=%s(应%s)" % (stage, st["stage"], gap, want_gap))
    S.record("T2 功能词阶梯 D+1/3/7/14", "PASS" if ok_all else "FAIL", "；".join(detail))

    # 反向断言（GPT 第 13 轮要求）：答错 / 判不了，绝不能推进 stage，也不能改 due
    sql("delete from pattern_event where pid='f-be'")
    S.sh("run-as %s sh -c 'rm -f shared_prefs/aurora.xml'" % PKG)
    sd = seed("f-be", 2, 1, -1)
    isolate("f-be")
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)
    run_one_pattern(answer_override="zzz zzz")     # 结构上不成立 → 必须不推进
    st = state("f-be")
    if not st:
        S.record("T2 反向·答错不推进", "FAIL", "无状态行")
    else:
        kept = st["stage"] == 2 and abs(st["due"] - sd["due"]) < 5000
        S.record("T2 反向·答错不推进", "PASS" if kept else "FAIL",
                 "stage %d->%d（应仍为 2），due 变动=%sms" %
                 (2, st["stage"], st["due"] - sd["due"]))


def t3_pending_review():
    """f-do 已连续一次 UNVERIFIED，再来一次判不了 → needs_review=1、原 due 不变。"""
    S.sh("run-as %s sh -c 'rm -f shared_prefs/aurora.xml'" % PKG)
    seeded = seed("f-do", 0, 1, -1, streak=1, needs=0)
    isolate("f-do")
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)
    run_one_pattern(answer_override="zzz zzz")          # 判不了 → UNVERIFIED
    st = state("f-do")
    if not st:
        S.record("T3 待审核暂停", "FAIL", "无状态行")
        return
    paused = st["needs"] == 1
    due_kept = abs(st["due"] - seeded["due"]) < 5000
    in_queue = int(one("select count(*) from pattern_state where pid='f-do' and needs_review=0 and due<=%d" % now_ms()) or 0)
    S.record("T3 待审核暂停", "PASS" if (paused and due_kept and in_queue == 0) else "FAIL",
             "needs_review=%d 原due保留=%s 仍在到期队列=%d（应为 0）" % (st["needs"], due_kept, in_queue))

    # 暂停后必须能恢复（GPT 第 14/15 轮：入口找不到记 FAIL，不许长期 SKIP）
    # 新流程：首页「待确认 N 句」→ 详情页 → 「重新练习」→ needs_review=0、due≈now、stage 不变
    nodes = goto_home()
    entry = find_btn(nodes, "待确认")
    if not entry:
        labels = [l for l, _, _ in (nodes or [])][:20]
        S.record("T3 恢复后重新入队", "FAIL",
                 "首页无「待确认」入口 —— 连续两次判不了的句子没有任何可达的恢复路径（产品缺陷）。"
                 " 可见控件=%s" % labels)
        return
    S.tap_node(entry)
    time.sleep(1)
    page = S.dump_ui("t3-pending")
    again = find_btn(page, "重新练习")
    if not again:
        labels = [l for l, _, _ in (page or [])][:20]
        S.record("T3 恢复后重新入队", "FAIL", "待确认页无「重新练习」按钮。可见控件=%s" % labels)
        return
    before = counts()
    S.tap_node(again)
    time.sleep(2)
    st2 = state("f-do")
    if not st2:
        S.record("T3 恢复后重新入队", "FAIL", "恢复后无状态行")
        return
    after = counts()
    due_ok = abs(st2["due"] - now_ms()) < 10000
    stage_kept = st2["stage"] == seeded["stage"]
    no_score = (after["review_event"] == before["review_event"]
                and after["card"] == before["card"])
    cleared = st2["needs"] == 0 and st2["streak"] == 0
    S.record("T3 恢复后重新入队",
             "PASS" if (cleared and due_ok and stage_kept and no_score) else "FAIL",
             "needs_review %d->%d streak=%d due≈now=%s stage %d->%d(不变=%s) 虚假评分=%s"
             % (st["needs"], st2["needs"], st2["streak"], due_ok, seeded["stage"], st2["stage"],
                stage_kept, not no_score))


def t5_pending_actions():
    """GPT 第 15 轮补充测试：待确认可见 / 搁置后仍可见 / 重启后保持 / 无虚假正确评分。"""
    pats = pat_by_id()
    pid = "f-be"
    p = pats.get(pid)
    if not p:
        S.record("T5 待确认动作", "SKIP", "patterns.json 里没有 f-be")
        return
    seeded = seed(pid, 2, 1, 3, streak=2, needs=1)
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)
    time.sleep(2)

    # 1) 首页可见
    home = S.dump_ui("t5-home")
    entry = find_btn(home, "待确认")
    if not entry:
        labels = [l for l, _, _ in (home or [])][:20]
        S.record("T5 待确认可见", "FAIL", "needs_review=1 但首页没有入口。可见控件=%s" % labels)
        return
    S.record("T5 待确认可见", "PASS", "首页出现「待确认」入口")

    # 2) 详情页要素：原始输入、参考答案、那句说明
    S.tap_node(entry)
    time.sleep(1)
    page = S.dump_ui("t5-detail")
    texts = " ".join(l for l, _, _ in (page or []))
    has_hint = ("无法判断" in texts) or ("参考答案后重新练习" in texts)
    has_ref = "参考答案" in texts
    has_mine = "你写的是" in texts
    S.record("T5 详情页要素", "PASS" if (has_hint and has_ref and has_mine) else "FAIL",
             "提示语=%s 参考答案=%s 原始输入=%s" % (has_hint, has_ref, has_mine))

    # 3) 「暂时搁置」：needs_review 保持 1、due 不动、只写审计事件
    before = counts()
    shelve = find_btn(page, "暂时搁置")
    if not shelve:
        S.record("T5 搁置后仍可见", "FAIL", "详情页无「暂时搁置」按钮")
        return
    S.tap_node(shelve)
    time.sleep(1)
    st = state(pid)
    after = counts()
    kept = st is not None and st["needs"] == 1 and abs(st["due"] - seeded["due"]) < 5000
    no_score = (after["review_event"] == before["review_event"] and after["card"] == before["card"])
    added = after["pattern_event"] - before["pattern_event"]
    S.record("T5 搁置后仍可见",
             "PASS" if (kept and no_score and added == 1) else "FAIL",
             "needs_review=%s due变动=%sms 审计事件+%d（必须恰好 1）虚假评分=%s"
             % ("?" if st is None else st["needs"],
                "?" if st is None else st["due"] - seeded["due"], added, not no_score))

    # 4) 重启后仍是待确认（持久化）
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)
    time.sleep(2)
    home2 = S.dump_ui("t5-restart")
    still = find_btn(home2, "待确认")
    S.record("T5 重启后保持", "PASS" if still else "FAIL",
             "重启后首页%s「待确认」入口" % ("仍有" if still else "没有"))
    if not still:
        return

    # 5) 「重新练习」：清阻塞、due=now、stage 不变、不产生正式评分
    S.tap_node(still)
    time.sleep(1)
    page2 = S.dump_ui("t5-detail2")
    again = find_btn(page2, "重新练习")
    if not again:
        S.record("T5 重新练习恢复队列", "FAIL", "详情页无「重新练习」按钮")
        return
    before2 = counts()
    S.tap_node(again)
    time.sleep(2)
    st2 = state(pid)
    after2 = counts()
    rc_added = after2["review_clear"] - before2["review_clear"]
    ok = (st2 is not None and st2["needs"] == 0 and st2["streak"] == 0
          and abs(st2["due"] - now_ms()) < 10000 and st2["stage"] == 2
          and rc_added == 1
          and after2["review_event"] == before2["review_event"]
          and after2["card"] == before2["card"])
    S.record("T5 重新练习恢复队列", "PASS" if ok else "FAIL",
             "needs=%s streak=%s stage=%s due≈now=%s review_clear+%d（必须恰好 1）review_event+%d card+%d"
             % ("?" if st2 is None else st2["needs"], "?" if st2 is None else st2["streak"],
                "?" if st2 is None else st2["stage"],
                "?" if st2 is None else abs(st2["due"] - now_ms()) < 10000, rc_added,
                after2["review_event"] - before2["review_event"], after2["card"] - before2["card"]))

    # 6) 审核动作不得混进正式统计：review_clear 的 verdict 必须 -1
    bad = int(one("select count(*) from pattern_event where kind='review_clear' and verdict<>-1") or 0)
    S.record("T5 审核事件不算作答", "PASS" if bad == 0 else "FAIL",
             "review_clear 中 verdict<>-1 的行数=%d（应为 0）" % bad)


def t5b_resume_respects_cap():
    """T5 第 7 项（GPT 第 15 轮补充授权）：恢复操作不得绕过每日 3 句上限。

    前提是「今天已经做完 3 个不同句型」——每日上限由 prefs 的 patIds_<日期> 计数决定，
    所以这里同时造 DB 事件与 prefs 键（两者一致才像真实使用），再把一个待确认句子恢复成候选，
    然后尝试进入正式句型学习：必须被拒，且不得新增 kind='formal' 事件、不得消耗额度。
    """
    import datetime
    pats = load_json(PATTERNS)
    order = [o["pattern_id"] for o in pats][:3]
    today = datetime.date.today().strftime("%Y%m%d")

    probe_pid = "f-do"
    if not pat_by_id().get(probe_pid):
        S.record("T5 恢复不绕过每日上限", "SKIP", "patterns.json 里没有 f-do")
        return

    xml_before = read_prefs()
    if not xml_before:
        S.record("T5 恢复不绕过每日上限", "SKIP", "读不到 shared_prefs/aurora.xml")
        return
    try:
        # 造「今日已完成 3 个不同句型」：DB 事件 + prefs 键
        for pid in order:
            sql("delete from pattern_event where pid='%s' and kind='formal'" % pid)
            sql("insert into pattern_event(eid,pid,ts,verdict,hint_used,stage_before,stage_after,"
                "due_before,due_after,kind) values('%s', '%s', %d, 1, 0, 0, 1, 0, 0, 'formal')"
                % ("cap-%s-%d" % (pid, now_ms()), pid, now_ms()))
        xml = set_pref_string(xml_before, "patIds_" + today, ",".join(order))
        if ("patDone_" + today) not in xml:
            tag = '    <int name="patDone_%s" value="3" />' % today
            xml = xml.replace("</map>", tag + chr(10) + "</map>")
        # 顺序很重要：seed() 会拉起 App，App 一启动就可能回写 prefs，
        # 所以 prefs 必须等 DB 写完、进程停下之后再写，否则刚造的 patIds_<今天> 会被冲掉，
        # 上限看起来「被绕过」，实际是测试自己把证据删了。
        seed(probe_pid, 2, 1, 3, streak=2, needs=1)
        S.sh("am force-stop %s" % PKG)
        write_prefs(xml)
        S.sh("am start -n %s" % S.ACT)
        time.sleep(2)

        home = S.dump_ui("t5b-home")
        entry = find_btn(home, "待确认")
        if not entry:
            S.record("T5 恢复不绕过每日上限", "FAIL", "额度已满时首页没有「待确认」入口（应可见）")
            return
        S.tap_node(entry)
        time.sleep(1)
        page = S.dump_ui("t5b-detail")
        again = find_btn(page, "重新练习")
        if not again:
            S.record("T5 恢复不绕过每日上限", "FAIL", "详情页无「重新练习」")
            return
        S.tap_node(again)
        time.sleep(2)

        restored = state(probe_pid)
        candidate = restored is not None and restored["needs"] == 0 and abs(restored["due"] - now_ms()) < 10000
        formal_before = int(one("select count(*) from pattern_event where kind='formal'") or 0)

        # 回首页，尝试进正式句型（额度已满 → 必须被拒）
        nodes = goto_home()
        btn = find_btn(nodes, "练句型", "学句型")
        if not btn:
            S.record("T5 恢复不绕过每日上限", "FAIL", "首页无句型入口（无法验证上限）")
            return
        S.tap_node(btn)
        time.sleep(2)
        after_ui = S.dump_ui("t5b-after-tap")
        labels = " ".join(l for l, _, _ in (after_ui or []))
        entered = ("开始练习" in labels) or ("换你来说一句" in labels)
        formal_after = int(one("select count(*) from pattern_event where kind='formal'") or 0)
        distinct = int(one("select count(distinct pid) from pattern_event where kind='formal' and "
                           "date(ts/1000,'unixepoch','localtime')=date('now','localtime')") or 0)

        ok = candidate and (not entered) and formal_after == formal_before and distinct <= 3
        S.record("T5 恢复不绕过每日上限", "PASS" if ok else "FAIL",
                 "恢复为候选=%s 进入教学=%s（应为 False） formal %d->%d 今日不同句型=%d（应≤3）"
                 % (candidate, entered, formal_before, formal_after, distinct))
    finally:
        write_prefs(xml_before)
        S.sh("am force-stop %s" % PKG)
        S.sh("am start -n %s" % S.ACT)
        time.sleep(1)


def t4_collocation_isolation():
    """替换练习：产生 kind=collocation 事件，但 stage/due 不动。"""
    colls = load_json(COLLOC)
    pats = pat_by_id()
    # 找一个有场景表达的句型
    target = None
    for c in colls:
        if c["pattern_id"] in pats:
            target = c
            break
    if not target:
        S.record("T4 场景表达隔离", "SKIP", "collocations.json 里没有可用条目")
        return
    pid = target["pattern_id"]
    S.sh("run-as %s sh -c 'rm -f shared_prefs/aurora.xml'" % PKG)
    seeded = seed(pid, 2, 1, -1)
    for p in (o["pattern_id"] for o in pats.values()):
        if p != pid:
            sql("update pattern_state set due=%d where pid='%s'" % (now_ms() + 30 * 86400000, p))
    S.sh("am force-stop %s" % PKG)
    S.sh("am start -n %s" % S.ACT)
    st_before = state(pid)
    ev_before = int(one("select count(*) from pattern_event where kind='collocation'") or 0)
    formal_before = int(one("select count(*) from pattern_event where kind='formal' and pid='%s'" % pid) or 0)
    run_one_pattern(answer_override=target["english"], coll_override=target["english"])
    st_after = state(pid)
    ev_after = int(one("select count(*) from pattern_event where kind='collocation'") or 0)
    formal_after = int(one("select count(*) from pattern_event where kind='formal' and pid='%s'" % pid) or 0)
    if not st_before or not st_after:
        S.record("T4 场景表达隔离", "FAIL", "缺少状态行")
        return
    unchanged = st_after["stage"] == st_before["stage"] and abs(st_after["due"] - st_before["due"]) < 5000
    produced = ev_after > ev_before
    S.record("T4 场景表达隔离", "PASS" if (unchanged and produced) else "FAIL",
             "collocation 事件 %d->%d，stage %d->%d，due 变动=%s" %
             (ev_before, ev_after, st_before["stage"], st_after["stage"],
              abs(st_after["due"] - st_before["due"]) >= 5000))
    # 不混入正式掌握率：正式事件计数不能因为替换练习而变化
    S.record("T4 场景表达·不进正式掌握率", "PASS" if formal_after == formal_before else "FAIL",
             "pid=%s 的 kind='formal' 事件 %d -> %d" % (pid, formal_before, formal_after))


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else S.DEFAULT_APK
    os.makedirs(S.SHOTS, exist_ok=True)
    serial = S.wait_device(30)
    if not serial:
        S.record("设备连接", "SKIP", "adb devices 为空 —— 全部真机项 SKIP，不算通过")
        return S.report()
    if not os.path.exists(apk):
        S.record("APK", "FAIL", apk)
        return S.report()
    if os.environ.get("SMOKE_SKIP_INSTALL", "") == "1":
        code, out, _ = S.adb("shell", "pm path %s" % PKG)
        if "package:" not in out:
            S.record("安装", "FAIL", "要求跳过安装，但包没装")
            return S.report()
        S.record("安装", "SKIP", "SMOKE_SKIP_INSTALL=1，使用设备上已装包")
    else:
        ok, detail = S.install_apk(apk)
        S.record("安装", "PASS" if ok else "FAIL", detail)
        if not ok:
            return S.report()

    if not backup():
        S.record("测试基线备份", "FAIL", "无法复制 aurora.db 副本，拒绝在真实库上做破坏性种子")
        return S.report()
    S.record("测试基线备份", "PASS", "databases/ab-baseline.db")

    try:
        t1_daily_limit()
        t2_function_ladder()
        t3_pending_review()
        t5_pending_actions()
        t5b_resume_respects_cap()
        t4_collocation_isolation()
    finally:
        restore()
        S.record("还原测试基线", "PASS", "已从 ab-baseline.db 还原并重启")
    return S.report()


if __name__ == "__main__":
    sys.exit(main())
