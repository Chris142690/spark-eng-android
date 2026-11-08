#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""稳定指纹：对迁移前后 / 注入前后做可复现比对。

为什么不用 SQL 里拼字符串当校验和（GPT 第 12 轮验收裁决第一条）：
  SQLite 里 sum(length(col)) 或 || 拼接再取哈希，会受浮点文本化格式、
  行返回顺序、NULL 与空串混淆影响 —— 那不叫强校验。
做法：
  1) 指定列、稳定排序（ORDER BY 主键）、固定浮点格式（repr 精度 17 位有效小数，%.17g 归一）；
  2) 每行产出规范化 JSON，逐行编号，整体 SHA-256；
  3) 同时记录行数，便于区分「内容变了」与「行数变了」。
用法：
  python stable_digest.py --db <pull 到本地的 aurora.db>        # 打印各表指纹
  python stable_digest.py --db a.db --compare b.db              # 比对两份
"""
import argparse
import hashlib
import json
import os
import sqlite3
import sys

# 表 -> (排序列, 需要参与指纹的列)
SPEC = {
    "card": ("w", ["w", "state", "due", "s", "d", "reps", "lapses", "last"]),
    "spell": ("w", ["w", "state", "due", "s", "d", "reps", "lapses", "last"]),
    "review_event": ("eid", ["eid", "w", "track", "ts", "rating", "elapsed_days",
                             "hinted", "verdict", "answer", "matched_sense", "event_type"]),
    "retrieval_guard": ("w, local_date", ["w", "local_date", "track", "ts", "event_id"]),
    "pattern_state": ("pid", ["pid", "stage", "due", "last_attempt_at", "first_try_correct",
                              "hint_level", "error_tags", "attempts", "content_version",
                              "unverified_streak", "needs_review"]),
    "pattern_event": ("eid", ["eid", "pid", "ts", "answer", "verdict", "hint_used",
                              "elapsed_hours", "error_tags", "missing",
                              "stage_before", "stage_after", "due_before", "due_after",
                              "content_version", "kind"]),
    "log": ("rowid", ["w", "action", "ts"]),
    "session": ("rowid", ["rowid"]),
    "word": ("w", ["w", "ipa", "pos", "cn"]),
}


MODE = "exact"   # exact = IEEE-754 无损（float.hex）；rounded = 十位小数归一（仅粗比对）


def norm(v):
    """把值规范化成与 SQLite 文本化无关的形式。

    exact 模式（默认，GPT 第 13 轮裁决要求）：
      REAL 用 float.hex() —— 这是 IEEE-754 双精度的无损表示，任何一位变化都会改变字符串。
    rounded 模式：
      repr(round(v,10)) —— 只用于「大致一致」的粗比对，会掩盖极小差异，
      所以输出里必须写明模式，不能宣称它能检测任何一位变化。
    """
    if v is None:
        return None
    if isinstance(v, float):
        if MODE == "exact":
            return {"f": v.hex()}
        return repr(round(v, 10))
    if isinstance(v, bytes):
        return {"b": v.hex()}
    if isinstance(v, int):
        return {"i": v}
    return v


def digest_table(con, table, order, cols):
    try:
        rows = con.execute("SELECT %s FROM %s ORDER BY %s" % (", ".join(cols), table, order)).fetchall()
    except sqlite3.Error as e:
        return {"table": table, "error": str(e), "rows": None, "sha256": None}
    h = hashlib.sha256()
    for r in rows:
        h.update(json.dumps([norm(x) for x in r], ensure_ascii=False,
                            separators=(",", ":")).encode("utf-8"))
        h.update(b"\n")
    return {"table": table, "rows": len(rows), "sha256": h.hexdigest()}


def collect(path):
    con = sqlite3.connect(path)
    try:
        out = {"db": os.path.basename(path),
               "user_version": con.execute("PRAGMA user_version").fetchone()[0],
               "float_mode": MODE}
        out["tables"] = {}
        for t, (order, cols) in SPEC.items():
            out["tables"][t] = digest_table(con, t, order, cols)
        return out
    finally:
        con.close()


def main():
    global MODE
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--compare")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--rounded", action="store_true",
                    help="粗比对模式：REAL 规一到十位小数（会掩盖极小差异，只用于大范围一致性检查）")
    a = ap.parse_args()
    MODE = "rounded" if a.rounded else "exact"

    base = collect(a.db)
    if a.compare:
        other = collect(a.compare)
        diffs = []
        for t in SPEC:
            x, y = base["tables"][t], other["tables"][t]
            if x.get("sha256") != y.get("sha256") or x.get("rows") != y.get("rows"):
                diffs.append({"table": t, "a_rows": x.get("rows"), "b_rows": y.get("rows"),
                              "a_sha": (x.get("sha256") or "")[:12], "b_sha": (y.get("sha256") or "")[:12]})
        same = not diffs and base["user_version"] == other["user_version"]
        res = {"same": same, "float_mode": MODE, "a": base, "b": other, "diffs": diffs}
        if a.json:
            print(json.dumps(res, ensure_ascii=False, indent=2))
        else:
            print("float_mode: %s%s" % (MODE, "" if MODE == "exact" else "（粗比对，不代表逐位一致）"))
            print("user_version: %s vs %s" % (base["user_version"], other["user_version"]))
            for t in SPEC:
                x, y = base["tables"][t], other["tables"][t]
                mark = "same" if x.get("sha256") == y.get("sha256") else "DIFF"
                print("%-16s %-5s rows %-6s %-6s sha %s vs %s" %
                      (t, mark, x.get("rows"), y.get("rows"),
                       (x.get("sha256") or "-")[:12], (y.get("sha256") or "-")[:12]))
            print("结论:", "完全一致" if same else "存在差异")
        return 0 if same else 2

    if a.json:
        print(json.dumps(base, ensure_ascii=False, indent=2))
    else:
        print("db=%s user_version=%s float_mode=%s" % (base["db"], base["user_version"], MODE))
        for t in SPEC:
            d = base["tables"][t]
            if d.get("error"):
                print("%-16s ERROR %s" % (t, d["error"]))
            else:
                print("%-16s rows=%-6s sha256=%s" % (t, d["rows"], d["sha256"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
