#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""V5→V14 迁移语句的离线重放与对账（合成样本）。

诚实边界（GPT 第 13 轮裁决第 4 条）：
  * 这里有 V5 合成库，没有真实 V5 历史库。
  * 所以本脚本只能证明「迁移语句对这份样本有效」，不能声称「真实历史数据升级成功」。
  * 真机上的迁移必须另跑一次，并用 fault_reset/stable_digest 记录证据。

它做什么：
  1. 用 make_v5_db.py 造 V5 库（word/card/spell/log(track)/screen, user_version=5）
  2. 按 Db.java 的 migrateToV6…V14 原文顺序重放 DDL / ALTER / 回填 UPDATE
  3. 【旧数据保留】对 V5 已有表的共同字段做逐行 SHA-256（REAL 用 float.hex 无损），
     迁移前后必须完全一致；行数必须一致
  4. 【新结构正确】V6–V14 的新表/新列/新索引/新默认值必须齐，user_version=14
  5. 【回填规则正确】历史 review_event 按 event_type 回填规则逐条核对（V10 规则）
  6. 【幂等】把第 2 步再跑一遍，共同字段与回填结果必须不变，行数不得增长

用法：python tests/migrate_replay.py
"""
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# ---- 与 Db.java migrateToV6…V14 一一对应的语句（顺序不得改） ----
MIGRATION = [
    # V6 检索封口表
    """CREATE TABLE IF NOT EXISTS retrieval_guard(w TEXT NOT NULL, local_date TEXT NOT NULL, track TEXT NOT NULL,
        ts INTEGER, event_id TEXT, PRIMARY KEY(w, local_date))""",
    # V7 eid 唯一索引 + hinted
    "ALTER TABLE review_event ADD COLUMN hinted INTEGER NOT NULL DEFAULT 0",
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_review_eid ON review_event(eid)",
    # V8 verdict + answer
    "ALTER TABLE review_event ADD COLUMN verdict INTEGER NOT NULL DEFAULT -1",
    "ALTER TABLE review_event ADD COLUMN answer TEXT",
    # V9 matched_sense
    "ALTER TABLE review_event ADD COLUMN matched_sense TEXT",
    # V10 event_type + 回填（规则照抄 Db.java：rating 1–4 且 verdict in (0,1) 才算 fsrs_review）
    "ALTER TABLE review_event ADD COLUMN event_type TEXT",
    "UPDATE review_event SET event_type = CASE"
    " WHEN rating>=1 AND rating<=4 AND verdict IN (0,1) THEN 'fsrs_review'"
    " ELSE 'appeal' END WHERE event_type IS NULL",
    # V11 句型两表
    """CREATE TABLE IF NOT EXISTS pattern_state(pid TEXT PRIMARY KEY, stage INTEGER NOT NULL DEFAULT 0,
        due INTEGER NOT NULL, last_attempt_at INTEGER, first_try_correct INTEGER NOT NULL DEFAULT -1,
        hint_level INTEGER NOT NULL DEFAULT 0, error_tags TEXT, transfer_success INTEGER,
        attempts INTEGER NOT NULL DEFAULT 0, content_version TEXT,
        unverified_streak INTEGER NOT NULL DEFAULT 0, needs_review INTEGER NOT NULL DEFAULT 0)""",
    """CREATE TABLE IF NOT EXISTS pattern_event(id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, pid TEXT,
        ts INTEGER, answer TEXT, verdict INTEGER, hint_used INTEGER, elapsed_hours REAL,
        error_tags TEXT, missing TEXT, stage_before INTEGER, stage_after INTEGER,
        due_before INTEGER, due_after INTEGER, content_version TEXT,
        kind TEXT NOT NULL DEFAULT 'formal')""",
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_pattern_eid ON pattern_event(eid)",
]

# V3 的账本三表在 V5 库里必须已存在（V5 建库脚本里补上，模拟真实老库）
LEDGER_DDL = [
    """CREATE TABLE review_event(id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, sid TEXT, w TEXT, track TEXT,
        rating INTEGER, ts INTEGER, response_ms INTEGER, elapsed_days REAL, r REAL,
        s_before REAL, s_after REAL, d_before REAL, d_after REAL,
        ivl_before REAL, ivl_after REAL, state_before INTEGER, state_after INTEGER,
        is_new INTEGER, algo TEXT, dr REAL)""",
    """CREATE TABLE session(sid TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER,
        duration_ms INTEGER, active_ms INTEGER, track TEXT, cap INTEGER,
        actions_used INTEGER, words_new INTEGER, words_reviewed INTEGER,
        synced INTEGER NOT NULL DEFAULT 0)""",
    """CREATE TABLE sentence_event(id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, sid TEXT, pid TEXT, ts INTEGER,
        prompt_type TEXT, prompt_text TEXT, answer_text TEXT, first_try INTEGER, hint_level INTEGER,
        attempts INTEGER, error_tags TEXT, semantic INTEGER, grammar INTEGER, transfer_level INTEGER,
        transfer_success INTEGER, latency_ms INTEGER, last_exposure_at INTEGER,
        delay_hours REAL, delayed_success INTEGER)""",
]

OLD_EVENTS = [
    # eid, w, track, rating, ts, s_after, d_after —— 正式评分行
    ("ev1", "go", "recognition", 3, 1700000000000, 12.3456789012, 5.25),
    ("ev2", "book", "recognition", 4, 1700100000000, 3.3333333333, 7.125),
    # rating=0 的行：必须是 appeal（历史申诉）
    ("ev3", "book", "recognition", 0, 1700200000000, 3.0, 7.0),
    # rating=5 的越界行：也不得被标成 fsrs_review
    ("ev4", "water", "recognition", 5, 1700300000000, 0.000000000123, 9.9999999999),
]

COMMON = {
    "card": ["w", "state", "due", "s", "d", "reps", "lapses", "last"],
    "spell": ["w", "state", "due", "s", "d", "reps", "lapses", "last"],
    "log": ["w", "track", "ts", "rating", "ms", "prev_ivl", "new_ivl", "prev_s", "new_s"],
    "screen": ["w", "ts", "step", "input", "ok", "ms"],
}


def norm(v):
    if v is None:
        return None
    if isinstance(v, float):
        return {"f": v.hex()}          # 无损：IEEE-754
    if isinstance(v, bytes):
        return {"b": v.hex()}
    return v


def fingerprint(con, table, cols):
    rows = con.execute("SELECT %s FROM %s ORDER BY rowid" % (", ".join(cols), table)).fetchall()
    h = hashlib.sha256()
    for r in rows:
        h.update(json.dumps([norm(x) for x in r], ensure_ascii=False, separators=(",", ":")).encode())
        h.update(b"\n")
    return len(rows), h.hexdigest()


def prepare_v5(path):
    """造 V5 库：基础表 + 账本三表（V3 就有）+ 4 条历史事件（V7 之前没有 eid 唯一索引，允许重复 eid 测试另做）"""
    subprocess.run([sys.executable, os.path.join(HERE, "make_v5_db.py"), path], check=True)
    con = sqlite3.connect(path)
    for d in LEDGER_DDL:
        con.execute(d)
    for eid, w, track, rating, ts, s_after, d_after in OLD_EVENTS:
        con.execute("INSERT INTO review_event(eid,w,track,rating,ts,s_before,s_after,d_before,d_after)"
                    " VALUES(?,?,?,?,?,?,?,?,?)", (eid, w, track, rating, ts, s_after, s_after, d_after, d_after))
    con.commit()
    con.close()


def migrate(con):
    for stmt in MIGRATION:
        try:
            con.execute(stmt)
        except sqlite3.Error as e:
            return "迁移语句失败: %s | %s" % (e, stmt.replace("\n", " ")[:60])
    con.execute("PRAGMA user_version=14")
    return None


def open_like_android(con):
    """模拟 SQLiteOpenHelper：只有 user_version < 14 才会跑 onUpgrade。
    第二次冷启动不该重放任何语句 —— 这才是「幂等」该验的东西，
    而不是把 ALTER ADD COLUMN 再执行一遍（那在真机上永远不会发生，
    重放它只会得到 duplicate column 的假失败）。"""
    v = con.execute("PRAGMA user_version").fetchone()[0]
    if v >= 14:
        return None
    return migrate(con)


def main():
    d = tempfile.mkdtemp(prefix="aurora-v5-")
    db = os.path.join(d, "v5.db")
    prepare_v5(db)

    con = sqlite3.connect(db)
    before = {t: fingerprint(con, t, cols) for t, cols in COMMON.items()}
    print("[1] V5 样本就绪：", ", ".join("%s=%d" % (t, v[0]) for t, v in before.items()))

    err = migrate(con)
    if err:
        print("[2] 迁移重放: FAIL |", err)
        return 1
    print("[2] 迁移重放 V6–V14: PASS（user_version=%d）"
          % con.execute("PRAGMA user_version").fetchone()[0])

    after = {t: fingerprint(con, t, cols) for t, cols in COMMON.items()}
    same = all(before[t] == after[t] for t in COMMON)
    detail = " ".join("%s:%s" % (t, "same" if before[t] == after[t] else "DIFF") for t in COMMON)
    print("[3] 旧数据保留（共同字段逐行 SHA-256, REAL 无损）:", "PASS" if same else "FAIL", "|", detail)
    if not same:
        for t in COMMON:
            if before[t] != after[t]:
                print("    %s rows %s->%s sha %s->%s" % (t, before[t][0], after[t][0],
                                                         before[t][1][:12], after[t][1][:12]))

    need_tables = {"retrieval_guard", "pattern_state", "pattern_event"}
    have = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    missing = need_tables - have
    cols_ok = all(
        con.execute("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?", (t, c)).fetchone()[0] == 1
        for t, c in [("review_event", "hinted"), ("review_event", "verdict"), ("review_event", "answer"),
                     ("review_event", "matched_sense"), ("review_event", "event_type"),
                     ("pattern_state", "unverified_streak"), ("pattern_state", "needs_review"),
                     ("pattern_event", "kind")])
    idx_ok = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='index'")} >= \
             {"idx_review_eid", "idx_pattern_eid"}
    print("[4] 新结构齐全:", "PASS" if (not missing and cols_ok and idx_ok) else "FAIL",
          "| 缺表=%s 新列齐=%s 索引齐=%s" % (sorted(missing) or "无", cols_ok, idx_ok))

    # V10 回填规则逐条核对
    rows = con.execute("SELECT eid, rating, verdict, event_type FROM review_event ORDER BY id").fetchall()
    bad = []
    for eid, rating, verdict, et in rows:
        want = "fsrs_review" if (rating is not None and 1 <= rating <= 4 and verdict in (0, 1)) else "appeal"
        if et != want:
            bad.append("%s rating=%s verdict=%s got=%s want=%s" % (eid, rating, verdict, et, want))
    print("[5] event_type 回填规则(V10):", "PASS" if not bad else "FAIL",
          "| " + ("；".join(bad) if bad else "%d 条全部符合（历史 verdict=-1 → appeal）" % len(rows)))

    # 幂等：模拟第二次冷启动（user_version 已是 14 → 不重放语句），数据必须不变
    snap_after = dict(after)
    err2 = open_like_android(con)
    again = {t: fingerprint(con, t, cols) for t, cols in COMMON.items()}
    rows2 = con.execute("SELECT eid, event_type FROM review_event ORDER BY id").fetchall()
    idem = (err2 is None) and again == snap_after and \
           [(r[0], r[1]) for r in rows2] == [(r[0], r[3]) for r in rows]
    print("[6] 迁移幂等（第二次冷启动不重放、数据不变）:", "PASS" if idem else "FAIL",
          "| err=%s 指纹一致=%s 回填不变=%s" % (err2, again == snap_after,
                                              [(r[0], r[1]) for r in rows2] == [(r[0], r[3]) for r in rows]))

    # 顺带用对账 SQL 跑一遍，确保 V14 结构下对账不报错
    con.commit()
    con.close()
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    r = subprocess.run([sys.executable, os.path.join(HERE, "audit_selftest.py")],
                       capture_output=True, env=env)
    print("[7] 附：audit_selftest.py 复跑:", "PASS" if r.returncode == 0 else "FAIL")
    print("样本库：", db)
    return 0


if __name__ == "__main__":
    sys.exit(main())
