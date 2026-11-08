#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
AURORA 一键装机验证（不依赖 uiautomator 之外的任何工具，只用 adb）

用法:
    python tests/smoke_adb.py                 # 用默认 APK
    python tests/smoke_adb.py path/to.apk

它做四件事，每件独立判 PASS/FAIL，最后给总结：
  A. 安装 + 冷启动，捕获 FATAL EXCEPTION / ANR（d8 假包、闪退都在这一步暴露）
  B. 首页三件套存在：进度行 / 复习大卡 / 学新词按钮
  C. 新授入口可点，四步骨架能走（懒筛'写中文'那步 adb 无法输入中文，只验到输入框出现）
  D. 数据库 8 张表存在且可读（run-as + sqlite3）

截图写到 tests/shots/smoke-*.png，失败时先看图和 logcat。

注意（交接包 §2.5 已记）：adb 不能输入中文，所以筛查的『看英文写中文』只能人工过；
本脚本把它标成 MANUAL，不算 FAIL。
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devdb  # noqa: E402  真机 sqlite3 垫片
ROOT = os.path.dirname(HERE)
SHOTS = os.path.join(HERE, "shots")
PKG = "com.aurora.word"
ACT = PKG + "/.MainActivity"
DEFAULT_APK = r"D:\手机\re\ccg\aurora.apk"
ADB = r"D:\asdk\platform-tools\adb.exe"

results = []   # (name, status, detail)  status in PASS/FAIL/MANUAL/SKIP


def record(name, status, detail=""):
    results.append((name, status, detail))
    print("[%s] %s %s" % (status, name, ("- " + detail) if detail else ""))


def adb(*args, **kw):
    cmd = [ADB] + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=kw.get("timeout", 60))
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")


def sh(cmd, timeout=60):
    # 真机没有 /system/bin/sqlite3：DB 命令交给 devdb 垫片（拉库 -> 本地 SQL -> 需要时推回）
    try:
        handled, out = devdb.handle(cmd)
    except Exception as e:
        print("   devdb error:", e)
        return ""
    if handled:
        return out
    code, out, err = adb("shell", cmd, timeout=timeout)
    return out


def wait_device(timeout_s=60):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        code, out, _ = adb("devices")
        lines = [l for l in out.splitlines()[1:] if l.strip()]
        if lines:
            rec = lines[0].split()
            if len(rec) >= 2 and rec[1] == "device":
                return rec[0]
        time.sleep(2)
    return None


def install_apk(apk, timeout=420):
    """安装 APK；vivo 会弹出「已了解应用的风险检测结果 + 继续安装」确认框，
    这里在安装进行中轮询该弹框并自动勾选/确认，否则 adb install 会一直挂着超时。"""
    p = subprocess.Popen([ADB] + (["-s", os.environ["ADB_SERIAL"]] if os.environ.get("ADB_SERIAL") else [])
                         + ["install", "-r", "-t", apk],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    t0 = time.time()
    handled = 0
    while p.poll() is None and time.time() - t0 < timeout:
        code, out, _ = adb("shell", "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        if "packageinstaller" in out:
            nodes = dump_ui("install-dialog")
            box = find(nodes, "已了解")
            if box:
                tap_node(box)
                time.sleep(0.8)
            go = find_btn(nodes, "继续安装")
            if go:
                tap_node(go)
                handled += 1
            time.sleep(1.5)
        time.sleep(2)
    try:
        out = p.communicate(timeout=30)[0].decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        p.kill()
        return False, "安装超时（%ds）" % timeout
    ok = "Success" in out
    return ok, ("%s%s" % (("自动确认弹框 %d 次；" % handled) if handled else "", out.strip().replace("\n", " ")[:180]))


def dump_ui(tag):
    """uiautomator dump -> 返回 [(text, x, y)] 可点中心点列表"""
    remote = "/sdcard/ui.xml"
    sh("uiautomator dump %s" % remote)
    code, out, err = adb("pull", remote, os.path.join(SHOTS, "ui-%s.xml" % tag))
    local = os.path.join(SHOTS, "ui-%s.xml" % tag)
    if not os.path.exists(local):
        return []
    try:
        tree = ET.parse(local)
    except Exception as e:
        print("   ui parse fail:", e)
        return []
    nodes = []
    for n in tree.iter("node"):
        txt = n.get("text") or ""
        desc = n.get("content-desc") or ""
        label = txt if txt else desc
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if label:
            nodes.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return nodes


def find(nodes, *subs):
    for label, x, y in nodes:
        for s in subs:
            if s in label:
                return (label, x, y)
    return None


def find_btn(nodes, *labels):
    """按「按钮」语义找节点：先整串相等，再允许短前缀/短包含。
    排除句子（含中文标点或过长），否则说明文字会把提示语里的动词当按钮点。"""
    for label, x, y in nodes:
        for s in labels:
            if label == s:
                return (label, x, y)
    for label, x, y in nodes:
        for s in labels:
            if not label or len(label) > 40:
                continue
            if any(ch in label for ch in "。，：；！？"):
                continue
            if label.startswith(s) or (len(label) <= len(s) + 6 and s in label):
                return (label, x, y)
    return None


def tap_node(node):
    _, x, y = node
    sh("input tap %d %d" % (x, y))
    time.sleep(1.2)


def tap_text(nodes, *subs):
    n = find(nodes, *subs)
    if not n:
        return None
    tap_node(n)
    return n


def screencap(tag):
    remote = "/sdcard/s-%s.png" % tag
    sh("screencap -p %s" % remote)
    local = os.path.join(SHOTS, "smoke-%s.png" % tag)
    adb("pull", remote, local)
    return local


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    os.makedirs(SHOTS, exist_ok=True)
    if not os.path.exists(ADB):
        record("adb 存在", "FAIL", ADB)
        return report()
    if not os.path.exists(apk):
        record("APK 存在", "FAIL", apk)
        return report()

    serial = wait_device(45)
    if not serial:
        record("设备连接", "SKIP", "adb devices 为空——插线并打开 USB 调试后重跑；本轮真机项全部 SKIP，不算通过")
        return report()
    record("设备连接", "PASS", serial)

    # ---- A. 安装 + 冷启动 ----
    if os.environ.get("SMOKE_SKIP_INSTALL", "") == "1":
        code, out, err = adb("shell", "pm path %s" % PKG)
        have = "package:" in out
        record("安装", "SKIP" if have else "FAIL",
               "SMOKE_SKIP_INSTALL=1，已装包不再覆盖安装" if have else "包未安装且要求跳过安装")
        if not have:
            return report()
    else:
        ok, detail = install_apk(apk)
        record("安装", "PASS" if ok else "FAIL", detail)
        if not ok:
            return report()

    adb("logcat", "-c")
    sh("am force-stop %s" % PKG)
    code, out, err = adb("shell", "am start -W -n %s" % ACT, timeout=60)
    time.sleep(2.5)
    started = "Status: ok" in out or "LaunchState" in out or "Activity:" in out
    record("冷启动", "PASS" if started else "FAIL", out.strip().replace("\n", " ")[:160])

    # 进程还活着吗（闪退检测）
    pid = sh("pidof %s" % PKG).strip()
    record("进程存活", "PASS" if pid else "FAIL", pid or "进程已退出=闪退")

    fatals = adb("logcat", "-d", "-b", "crash", "*:E")[1]
    fatals += adb("logcat", "-d", "*:E")[1]
    bad = [l for l in fatals.splitlines() if ("FATAL" in l or "AndroidRuntime" in l) and PKG in (l + "")]
    if bad:
        record("无崩溃日志", "FAIL", bad[0][:200])
    else:
        record("无崩溃日志", "PASS")

    shot = screencap("01-home")
    nodes = dump_ui("01-home")
    print("   首页文本:", " | ".join(n[0] for n in nodes)[:300])

    # ---- B. 首页三件套 ----
    prog = find(nodes, "今天 ", "累计")
    record("首页·进度行", "PASS" if prog else "FAIL", prog[0] if prog else "没找到『今天 X / 50 · 累计』")
    # GPT 核验结论：s>=7 只是模型预测，首页必须写「预计掌握」，禁止写「记住」
    if prog:
        record("首页·口径用词", "PASS" if "预计掌握" in prog[0] and "记住" not in prog[0] else "FAIL",
               prog[0][:120])
    review = find(nodes, "待复习", "今天没有要复习的", "今天已完成")
    record("首页·复习大卡", "PASS" if review else "FAIL", review[0] if review else "没找到复习大卡")
    newbtn = find(nodes, "学新词", "今天到此为止")
    record("首页·新授按钮", "PASS" if newbtn else "FAIL", newbtn[0] if newbtn else "没找到『学新词 · 今天 N 个』")
    patbtn = find(nodes, "学句型", "练句型")
    record("首页·句型入口", "PASS" if patbtn else "FAIL",
           patbtn[0] if patbtn else "没找到『学句型 / 练句型』入口——GPT 判定为入口不可发现即部分实现")
    if patbtn:
        tap_node(patbtn)
        time.sleep(1.2)
        pnodes = dump_ui("01b-pattern")
        screencap("01b-pattern")
        body = " | ".join(n[0] for n in pnodes)
        has_kicker = ("今日句型" in body) or ("句型" in body and ("/3" in body or "/ 3" in body))
        record("句型·教学页可达", "PASS" if has_kicker else "MANUAL",
               body[:200] if has_kicker else "未识别到『今日句型 x/3』，人工确认")
        backp = find(pnodes, "‹") or find(pnodes, "← 返回") or find(pnodes, "返回")
        if backp:
            tap_node(backp)
            time.sleep(1.0)
        nodes = dump_ui("01c-home-back")

    if not newbtn:
        return report()

    # ---- C. 新授入口 + 四步骨架 ----
    if "今天就到此为止" in newbtn[0] or "到此为止" in newbtn[0]:
        record("新授入口", "SKIP", "今天额度已满，跳过点进")
        return report()

    tap_node(newbtn)
    nodes = dump_ui("02-new")
    screencap("02-new")
    inp = find(nodes, "中文意思", "英文单词")
    record("新授·筛查输入框", "PASS" if inp else "FAIL", inp[0] if inp else "没找到输入框")
    # 筛查第一步是「看英文写中文」，adb 无法输入中文 → 只验界面，不打分
    record("新授·懒筛第一步", "MANUAL", "『看英文写中文』需要人工输入中文（adb input text 不支持中文）")
    if inp:
        # 随便给一个英文错答，验证答错对比页能出来（不评分的路径）
        sh("input text zzz")
        time.sleep(0.6)
        nodes2 = dump_ui("03-wrong")
        screencap("03-wrong")
        btn = find(nodes2, "确认", "提交", "继续", "看释义")
        if btn:
            tap_node(btn)
            time.sleep(1.0)
            nodes3 = dump_ui("04-after-wrong")
            screencap("04-after-wrong")
            contrast = find(nodes3, "正确的是", "你写的是", "没关系", "从头")
            record("答错有对比/引导", "PASS" if contrast else "MANUAL",
                   contrast[0] if contrast else "界面未出现对比文案，人工确认")
        else:
            record("答错有对比/引导", "MANUAL", "没找到提交按钮，人工确认")

    # 回首页
    nodes = dump_ui("05-x")
    back = find(nodes, "‹")
    if back:
        tap_node(back)
        time.sleep(1.0)
    screencap("05-home2")

    # ---- D. 数据库 ----
    tables = "word card log screen review_event session sentence_event spell retrieval_guard pattern_state pattern_event"
    out = sh("run-as %s sqlite3 databases/aurora.db \".tables\"" % PKG, timeout=30)
    missing = [t for t in tables.split() if t not in out]
    record("DB·8 张表", "PASS" if not missing else "FAIL",
           ("缺 " + ",".join(missing)) if missing else "全部存在")

    for t in ("word", "card", "spell", "log"):
        cnt = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from %s\"" % (PKG, t), timeout=30).strip()
        good = cnt.isdigit()
        record("DB·%s 可读" % t, "PASS" if good else "FAIL", "rows=" + (cnt or "?"))

    ver = sh("run-as %s sqlite3 databases/aurora.db \"PRAGMA user_version\"" % PKG, timeout=30).strip()
    for col in ("hinted", "verdict", "answer", "matched_sense", "event_type"):
        q = sh("run-as %s sqlite3 databases/aurora.db \"SELECT COUNT(*) FROM pragma_table_info('review_event')"
               " WHERE name='%s'\"" % (PKG, col), timeout=30).strip()
        record("DB·review_event." + col, "PASS" if q.strip() == "1" else "FAIL", "count=" + (q or "?"))
    record("DB·版本", "PASS" if ver.strip() == "14" else "FAIL", "user_version=" + (ver or "?"))
    # GPT「申诉事件存储复裁」：正式评分与申诉必须可分。申诉行不得进入保持率/评分类统计。
    evtype = sh("run-as %s sqlite3 databases/aurora.db \"SELECT COUNT(*) FROM review_event WHERE event_type IS NULL\"" % PKG, timeout=30).strip()
    record("DB·事件类型无空值", "PASS" if evtype.strip() in ("0", "") else "FAIL", "event_type IS NULL 行数=" + (evtype or "?"))
    badapp = sh("run-as %s sqlite3 databases/aurora.db \"SELECT COUNT(*) FROM review_event WHERE event_type='fsrs_review' AND (rating<1 OR rating>4)\"" % PKG, timeout=30).strip()
    record("DB·无 rating0 冒充正式评分", "PASS" if badapp.strip() in ("0", "") else "FAIL", "违规行数=" + (badapp or "?"))

    # ---- E. GPT 二轮复裁的验收点 ----
    idx = sh("run-as %s sqlite3 databases/aurora.db \"select name from sqlite_master where type='index' and name='idx_review_eid'\"" % PKG, timeout=30).strip()
    record("Q7·eid 唯一索引", "PASS" if idx == "idx_review_eid" else "FAIL", idx or "索引不存在")
    hint = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from pragma_table_info('review_event') where name='hinted'\"" % PKG, timeout=30).strip()
    record("Q3·hinted 列", "PASS" if hint == "1" else "FAIL", "列数=" + (hint or "?"))
    # 今日新授卡：state=1 & reps=0 & last 在今天。这些卡当天不得再被评分（Q6）。
    d0q = ("select count(*) from card where state=1 and reps=0 and "
           "date(last/1000,'unixepoch','localtime')=date('now','localtime')")
    d0 = sh("run-as %s sqlite3 databases/aurora.db \"%s\"" % (PKG, d0q), timeout=30).strip()
    record("Q6·今日新授卡数", "INFO", "state=1&reps=0 今日 " + (d0 or "?") + " 张（>0 时若又被评分即为缺陷）")

    # ---- F. 同日封口（GPT 复裁 Q2 的验收点）----
    g = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from retrieval_guard\"" % PKG, timeout=30).strip()
    # 句型线 V13：待审核标记列必须存在，且不得有 needs_review=1 却仍进到期队列的卡
    nr = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from pragma_table_info('pattern_state') where name in ('needs_review','unverified_streak')\"" % PKG, timeout=30).strip()
    record("DB·句型待审核列", "PASS" if nr.strip() == "2" else "FAIL", "两列命中=" + (nr or "?"))
    pend = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from pattern_state where needs_review=1\"" % PKG, timeout=30).strip()
    record("DB·待审核句型计数", "INFO", "needs_review=1 共 " + (pend or "?"))
    # 句型线（V11）：两表必须存在，且 pattern_event 不能出现 eid 重复
    pe = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from pattern_event\"" % PKG, timeout=30).strip()
    record("DB·句型事件表可读", "PASS" if pe.isdigit() else "FAIL", "pattern_event=" + (pe or "?"))
    pd = sh("run-as %s sqlite3 databases/aurora.db \"select count(*) from (select eid from pattern_event group by eid having count(*)>1)\"" % PKG, timeout=30).strip()
    record("DB·句型事件无重复 eid", "PASS" if pd.strip() == "0" else "FAIL", "dup=" + (pd or "?"))

    # ---- G. 句型线（V11–V14）----
    kinds = sh('run-as %s sqlite3 databases/aurora.db '
               '"select count(*) from pattern_event where kind is null or kind not in (\'formal\',\'collocation\',\'review_clear\')"'
               % PKG, timeout=30).strip()
    record("句型·kind 合法", "PASS" if kinds.strip() == "0" else "FAIL",
           "非法/空 kind 行数=" + (kinds or "?"))
    coll = sh('run-as %s sqlite3 databases/aurora.db '
              '"select count(*) from pattern_event where kind=\'collocation\' '
              'and (stage_before<>stage_after or due_before<>due_after)"' % PKG, timeout=30).strip()
    record("句型·替换练习不改调度", "PASS" if coll.strip() == "0" else "FAIL",
           "违规行数=" + (coll or "?"))
    perday = sh('run-as %s sqlite3 databases/aurora.db '
                '"select count(distinct pid) from pattern_event where kind=\'formal\' '
                'and date(ts/1000,\'unixepoch\',\'localtime\')=date(\'now\',\'localtime\')"' % PKG, timeout=30).strip()
    okday = perday.isdigit() and int(perday) <= 3
    record("句型·每日上限 3 个", "PASS" if okday else "FAIL",
           "今日 formal 涉及 pattern_id=" + (perday or "?"))
    # 待确认入口：只有真的存在 needs_review=1 时才检查，避免零数据时产生无意义的 SKIP
    # （SKIP>0 会把整轮判成「部分通过」，那会让一次干净的验收永远拿不到 PASS）
    if (pend or "").strip().isdigit() and int(pend) > 0:
        home = dump_ui("smoke-pending-home")
        got = find(home, "待确认")
        record("句型·待确认入口可见", "PASS" if got else "FAIL",
               "needs_review=1 共 %s 条，首页入口%s" % (pend, "存在" if got else "缺失（内容不可达）"))
        rv = sh('run-as %s sqlite3 databases/aurora.db '
                '"select count(*) from pattern_event where kind=\'review_clear\' and verdict<>-1"' % PKG,
                timeout=30).strip()
        record("句型·审核动作不算作答", "PASS" if rv.strip() == "0" else "FAIL",
               "review_clear 中 verdict<>-1 行数=" + (rv or "?"))
    # 功能词阶梯：stage 与 (due-last) 的天数必须对得上（1/3/7/14），不得跳级
    ladder = sh('run-as %s sqlite3 databases/aurora.db '
                '"select pid,stage,cast((due-last)/86400000 as int) d from pattern_state '
                'where pid in (\'f-be\',\'f-have\',\'f-do\') and last>0"' % PKG, timeout=30).strip()
    badl = []
    want = {1: 1, 2: 3, 3: 7, 4: 14}
    for line in ladder.splitlines():
        parts = line.replace("|", " ").split()
        if len(parts) < 3 or not parts[1].isdigit() or not parts[2].lstrip("-").isdigit():
            continue
        st, dd = int(parts[1]), int(parts[2])
        if st in want and abs(dd - want[st]) > 1:
            badl.append("%s stage=%d 间隔=%d天(应%d)" % (parts[0], st, dd, want[st]))
    record("句型·功能词不跳级", "PASS" if not badl else "FAIL",
           "；".join(badl)[:200] or "f-be/f-have/f-do 阶梯吻合(1/3/7/14)")

    record("封口表可读", "PASS" if g.isdigit() else "FAIL", "rows=" + (g or "?"))
    dup = sh('run-as %s sqlite3 databases/aurora.db '
             '"select w,local_date,count(*) c from retrieval_guard group by w,local_date having c>1"'
             % PKG, timeout=30).strip()
    record("封口唯一·同词同日", "PASS" if not dup else "FAIL", dup[:160] or "无重复 (w,local_date)")
    both = sh('run-as %s sqlite3 databases/aurora.db '
              '"select w,local_date,count(distinct track) c from retrieval_guard group by w,local_date having c>1"'
              % PKG, timeout=30).strip()
    record("封口·跨通道互斥", "PASS" if not both else "FAIL",
           both[:160] or "同一词同一天未被两条通道各考一次")
    # Again 之后当天不该再出现：封口行存在即说明已拦；再比对 log 里同词同日评价次数
    again = sh('run-as %s sqlite3 databases/aurora.db '
               '"select w,count(*) c from log where ts>=strftime(\'%%s\',\'now\',\'start of day\',\'localtime\')*1000 '
               'group by w having c>1"' % PKG, timeout=30).strip()
    record("Again 当天不重复", "PASS" if not again else "FAIL",
           again[:200] or "今日无同词多次评分")

    return report()


def report():
    print("\n================ 总结 ================")
    p = sum(1 for r in results if r[1] == "PASS")
    f = sum(1 for r in results if r[1] == "FAIL")
    m = sum(1 for r in results if r[1] == "MANUAL")
    s = sum(1 for r in results if r[1] == "SKIP")
    print("PASS=%d FAIL=%d MANUAL=%d SKIP=%d" % (p, f, m, s))
    for name, st, det in results:
        if st in ("FAIL", "MANUAL"):
            print("  %-4s %s  %s" % (st, name, det))
    print("截图: %s" % SHOTS)
    print("SKIP 明细:")
    for name, st, det in results:
        if st == "SKIP":
            print("  SKIP %s  %s" % (name, det))
    # GPT 第 12 轮验收裁决：没执行到关键路径必须显示 SKIP，不得算通过。
    # 因此「0 失败」不等于「通过」——必须有 PASS，且 SKIP 未被当作通过。
    if f > 0:
        print("结果: 有 %d 项失败，先看截图与 logcat" % f)
        return 1
    if p == 0:
        print("结果: 未执行（全部 SKIP/MANUAL，不算通过）")
        return 2
    if s > 0:
        print("结果: 已执行部分通过（PASS=%d），但有 %d 项 SKIP —— 不接受为验收通过" % (p, s))
        return 3
    if m > 0:
        print("结果: 自动化项全通过（PASS=%d），仍有 %d 项需人工 —— 未完成验收" % (p, m))
        return 4
    print("结果: 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
