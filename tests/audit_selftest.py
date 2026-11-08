#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""MIGRATION-AUDIT.sql 的离线自检：不插手机也能证明这份对账 SQL 真的能跑。

为什么需要它：GPT 第 12 轮验收裁决要求「每项 PASS 都要有可复现的操作证据」。
真机没插时，至少可以在本机按 V14 的 DDL 建一个同构库，把对账 SQL 整段执行一遍——
这样「SQL 写错列名/语法」这类错误会在真机之前暴露，而不是等到插线才发现。

它做三件事：
  1. 按 Db.java 的 DDL（V14 终态）建库，灌几条样本数据；
  2. 去掉 sqlite3 CLI 的 .dot 命令后整段执行 MIGRATION-AUDIT.sql，任何异常都算 FAIL；
  3. 用 stable_digest.py 对同一个库算指纹，并对副本做一次「改一个字段」的负控制。

用法：python tests/audit_selftest.py
"""
import io
import os
import re
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = os.path.join(HERE, "MIGRATION-AUDIT.sql")

# 与 Db.java onCreate（V14 终态）一致的 DDL
DDL = [
    "CREATE TABLE word(w TEXT PRIMARY KEY, n INTEGER, ipa TEXT, pos TEXT, cn TEXT, ex TEXT, excn TEXT, syl TEXT)",
    "CREATE TABLE card(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL, reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)",
    "CREATE TABLE spell(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL, reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)",
    "CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, track TEXT, ts INTEGER, rating INTEGER, ms INTEGER, prev_ivl REAL, new_ivl REAL, prev_s REAL, new_s REAL)",
    "CREATE TABLE screen(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, ts INTEGER, step INTEGER, input TEXT, ok INTEGER, ms INTEGER)",
    """CREATE TABLE review_event(id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, sid TEXT, w TEXT, track TEXT,
        rating INTEGER, ts INTEGER, response_ms INTEGER, elapsed_days REAL, r REAL,
        s_before REAL, s_after REAL, d_before REAL, d_after REAL,
        ivl_before REAL, ivl_after REAL, state_before INTEGER, state_after INTEGER,
        is_new INTEGER, algo TEXT, dr REAL, hinted INTEGER NOT NULL DEFAULT 0,
        verdict INTEGER NOT NULL DEFAULT -1, answer TEXT, matched_sense TEXT, event_type TEXT)""",
    "CREATE UNIQUE INDEX idx_review_eid ON review_event(eid)",
    """CREATE TABLE session(sid TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER,
        duration_ms INTEGER, active_ms INTEGER, track TEXT, cap INTEGER,
        actions_used INTEGER, words_new INTEGER, words_reviewed INTEGER,
        synced INTEGER NOT NULL DEFAULT 0)""",
    """CREATE TABLE sentence_event(id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, sid TEXT, pid TEXT, ts INTEGER,
        prompt_type TEXT, prompt_text TEXT, answer_text TEXT,
        first_try INTEGER, hint_level INTEGER, attempts INTEGER, error_tags TEXT,
        semantic INTEGER, grammar INTEGER, transfer_level INTEGER, transfer_success INTEGER,
        latency_ms INTEGER, last_exposure_at INTEGER, delay_hours REAL, delayed_success INTEGER)""",
    """CREATE TABLE retrieval_guard(w TEXT NOT NULL, local_date TEXT NOT NULL, track TEXT NOT NULL,
        ts INTEGER, event_id TEXT, PRIMARY KEY(w, local_date))""",
    """CREATE TABLE pattern_state(pid TEXT PRIMARY KEY, stage INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL,
        last_attempt_at INTEGER, first_try_correct INTEGER NOT NULL DEFAULT -1,
        hint_level INTEGER NOT NULL DEFAULT 0, error_tags TEXT, transfer_success INTEGER,
        attempts INTEGER NOT NULL DEFAULT 0, content_version TEXT,
        unverified_streak INTEGER NOT NULL DEFAULT 0, needs_review INTEGER NOT NULL DEFAULT 0)""",
    """CREATE TABLE pattern_event(id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, pid TEXT, ts INTEGER,
        answer TEXT, verdict INTEGER, hint_used INTEGER, elapsed_hours REAL,
        error_tags TEXT, missing TEXT, stage_before INTEGER, stage_after INTEGER,
        due_before INTEGER, due_after INTEGER, content_version TEXT,
        kind TEXT NOT NULL DEFAULT 'formal')""",
    "CREATE UNIQUE INDEX idx_pattern_eid ON pattern_event(eid)",
]

SEED = [
    ("word", "INSERT INTO word VALUES('say',1,'/sei/','v.','说','Say it.','说出来。','say')"),
    ("card", "INSERT INTO card VALUES('say',2,1700000000000,3.5,5.0,2,0,1699000000000,3.5)"),
    ("card", "INSERT INTO card VALUES('go',1,1700000000000,1.2,5.0,0,0,1699990000000,1.2)"),
    ("spell", "INSERT INTO spell VALUES('say',2,1700000000000,2.0,5.0,1,0,1699000000000,2.0)"),
    ("guard", "INSERT INTO retrieval_guard VALUES('say','2026-11-07','recognition',1700000000000,'e1')"),
    ("event", "INSERT INTO review_event(eid,w,track,rating,ts,hinted,verdict,event_type) VALUES('e1','say','recognition',3,1700000000000,0,1,'fsrs_review')"),
    ("event", "INSERT INTO review_event(eid,w,track,rating,ts,hinted,verdict,event_type) VALUES('e2','go','recognition',0,1700000000000,0,2,'appeal')"),
    ("pstate", "INSERT INTO pattern_state(pid,stage,due,last_attempt_at,attempts) VALUES('f-be',2,1700000000000,1699000000000,3)"),
    ("pevent", "INSERT INTO pattern_event(eid,pid,ts,verdict,stage_before,stage_after,due_before,due_after,kind) VALUES('pe1','f-be',1700000000000,1,1,2,1699000000000,1700000000000,'formal')"),
    ("pevent", "INSERT INTO pattern_event(eid,pid,ts,verdict,stage_before,stage_after,due_before,due_after,kind) VALUES('pe2','f-be',1700000000000,1,2,2,1700000000000,1700000000000,'collocation')"),
    ("session", "INSERT INTO session(sid,started_at,cap) VALUES('s1',1700000000000,50)"),
]


def build(path):
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    con.execute("PRAGMA user_version=14")
    for d in DDL:
        con.execute(d)
    for _, sql in SEED:
        con.execute(sql)
    con.commit()
    con.close()


LAST_ROWS = []


def run_audit(path):
    """去掉 .headers/.mode 这类 CLI 点命令，整段执行；返回 (ok, 行数, 错误)。
    结果行同时留在 LAST_ROWS，供负控制直接读 audit 自己的输出。"""
    global LAST_ROWS
    raw = io.open(AUDIT, encoding="utf-8").read()
    body = "\n".join(l for l in raw.splitlines() if not l.strip().startswith("."))
    con = sqlite3.connect(path)
    rows = []
    buf = ""
    try:
        cur = con.cursor()
        for line in body.splitlines():
            buf += line + "\n"
            # 用 complete_statement 判断语句结束，避免把字符串里的分号当分隔符
            if not sqlite3.complete_statement(buf):
                continue
            stmt = buf.strip()
            buf = ""
            if not stmt:
                continue
            # 去掉注释行再判断这条语句是不是 SELECT ——
            # 否则「注释 + SELECT」开头的语句会被当成非查询，fetchall 永远不执行，
            # 那些断言就等于没跑（这正是「检查器空转」那一类缺陷）。
            code = chr(10).join(l for l in stmt.splitlines() if not l.strip().startswith("--")).strip()
            if not code:
                continue
            try:
                cur.execute(stmt)
                if code.lstrip().upper().startswith("SELECT"):
                    rows.extend(cur.fetchall())
            except sqlite3.Error as e:
                return False, len(rows), "语句失败: %s | %s" % (str(e), stmt.replace("\n", " ")[:70])
        LAST_ROWS = rows[:]
        return True, len(rows), ""
    except sqlite3.Error as e:
        return False, len(rows), str(e)
    finally:
        con.close()


def digest(path, other=None):
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    args = [sys.executable, os.path.join(HERE, "stable_digest.py"), "--db", path]
    if other:
        args += ["--compare", other]
    r = subprocess.run(args, capture_output=True, env=env)
    return r.returncode, r.stdout.decode("utf-8", "replace")


def main():
    d = tempfile.mkdtemp(prefix="aurora-audit-")
    a = os.path.join(d, "a.db")
    b = os.path.join(d, "b.db")
    build(a)
    print("[1] DDL 建库 OK ->", a)

    ok, n, err = run_audit(a)
    print("[2] MIGRATION-AUDIT.sql 执行:", "PASS" if ok else "FAIL", "| 结果行数 =", n, ("| " + err) if err else "")
    if not ok:
        return 1

    rc, out = digest(a)
    card = [l for l in out.splitlines() if l.startswith("card ")]
    print("[3] stable_digest 单库:", "PASS" if rc == 0 else "FAIL", "|", card[0].strip() if card else out.strip()[:80])

    # 负控制：改一个字段，指纹必须变
    build(b)
    con = sqlite3.connect(b)
    con.execute("UPDATE card SET last = last + 1 WHERE w = 'say'")
    con.commit()
    con.close()
    rc2, out2 = digest(a, b)
    print("[4] 负控制（改 last）期望 rc=2 且 DIFF:",
          "PASS" if rc2 == 2 else "FAIL", "|", out2.strip().splitlines()[-1] if out2.strip() else "")

    # 负控制：乱插 kind / collocation 改调度 / 审核动作冒充作答，audit 必须能抓出来。
    # 关键：这三个数从 audit 自己的输出里取，不在这里重写一遍 WHERE ——
    # 否则检查的是「我抄的那条 SQL」，而不是被测的 MIGRATION-AUDIT.sql。
    build(b)
    con = sqlite3.connect(b)
    con.execute("INSERT INTO pattern_event(eid,pid,ts,verdict,stage_before,stage_after,due_before,due_after,kind)"
                " VALUES('pe3','f-be',1,1,1,2,100,200,'collocation')")
    con.execute("INSERT INTO pattern_event(eid,pid,ts,verdict,kind) VALUES('pe4','f-be',1,0,'weird')")
    con.execute("INSERT INTO pattern_event(eid,pid,ts,verdict,stage_before,stage_after,kind)"
                " VALUES('pe5','f-be',1,1,1,1,'review_clear')")
    con.commit()
    con.close()
    ok3, _, _ = run_audit(b)
    vals = {str(r[0]): str(r[1]) for r in LAST_ROWS if len(r) >= 2}
    bad_kind = int(vals.get("bad_kind", "-1"))
    moved = int(vals.get("collocation_moved_schedule", "-1"))
    bad_verdict = int(vals.get("review_clear_bad_verdict", "-1"))
    print("[5] 负控制（非法 kind / collocation 改调度 / review_clear 冒充作答）:",
          "PASS" if (bad_kind == 1 and moved == 1 and bad_verdict == 1) else "FAIL",
          "| bad_kind=%d collocation_moved=%d review_clear_bad_verdict=%d" % (bad_kind, moved, bad_verdict))
    if not (bad_kind == 1 and moved == 1 and bad_verdict == 1):
        return 1
    # 正控制：干净的库上这三个数必须都是 0（否则等于检查器恒报错）
    build(b)
    ok4, _, _ = run_audit(b)
    v2 = {str(r[0]): str(r[1]) for r in LAST_ROWS if len(r) >= 2}
    zero = all(int(v2.get(k, "-1")) == 0 for k in ("bad_kind", "collocation_moved_schedule", "review_clear_bad_verdict"))
    print("[6] 正控制（干净库三项必须为 0）:", "PASS" if zero else "FAIL",
          "| bad_kind=%s moved=%s bad_verdict=%s" % (v2.get("bad_kind"), v2.get("collocation_moved_schedule"),
                                                    v2.get("review_clear_bad_verdict")))
    return 0 if zero else 1


if __name__ == "__main__":
    sys.exit(main())
