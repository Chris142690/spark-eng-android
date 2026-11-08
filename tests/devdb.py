# -*- coding: utf-8 -*-
"""真机数据库访问垫片（vivo/Android 14 没有 /system/bin/sqlite3）。

为什么需要它：smoke_adb.py 与 pattern_probe.py 原本一律用
    run-as com.aurora.word sqlite3 databases/aurora.db "<SQL>"
读取库。模拟器（AOSP system image）自带 sqlite3 可执行文件，真机没有，
于是所有 DB 检查都会拿到空输出、批量 FAIL —— 那是环境缺件，不是产品缺陷。

本模块只做一件事：拦下这条命令形态，改成
    拉库到本机 -> python sqlite3 执行 -> 只有写语句才推回
只读语句永不写设备；写语句执行后清 WAL 再推回，由调用方负责 force-stop。

判据（写 vs 读）：语句首关键字属于 WRITE_KEYWORDS 即视为写。
推回失败会抛 RuntimeError，绝不静默当成功。
"""

import os
import re
import shutil
import sqlite3
import subprocess

PKG = "com.aurora.word"
ADB = r"D:\asdk\platform-tools\adb.exe"
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "shots", "devdb")
DB_REMOTE_DIR = "databases"
DB_NAME = "aurora.db"
DBFILES = ("aurora.db", "aurora.db-wal", "aurora.db-shm", "aurora.db-journal")

WRITE_KEYWORDS = (
    "insert", "update", "delete", "replace", "create", "drop", "alter",
    "vacuum", "reindex", "attach", "detach",
)

_RE = re.compile(r"^\s*run-as\s+(\S+)\s+sqlite3\s+(\S+)\s*(.*)$", re.S)

_stats = {"pull": 0, "push": 0}


def serial():
    return os.environ.get("ADB_SERIAL", "").strip()


def adb(*args, timeout=60, stdin_bytes=None):
    cmd = [ADB]
    s = serial()
    if s:
        cmd += ["-s", s]
    cmd += list(args)
    p = subprocess.run(cmd, input=stdin_bytes, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def _work(tag=""):
    d = os.path.join(WORK, tag) if tag else WORK
    os.makedirs(d, exist_ok=True)
    return d


def remote_list():
    code, out, _ = adb("shell", "run-as %s sh -c 'ls %s'" % (PKG, DB_REMOTE_DIR))
    if code != 0:
        raise RuntimeError("列目录失败: run-as 不可用（APK 是否 debuggable？）")
    return out.decode("utf-8", "replace").split()


def pull(tag=""):
    """把 aurora.db 及 -wal/-shm/-journal 拉到本地，返回本地主库路径。"""
    d = _work(tag)
    names = remote_list()
    for f in DBFILES:
        dst = os.path.join(d, f)
        if f in names:
            code, out, err = adb("exec-out", "run-as", PKG, "cat",
                                 "%s/%s" % (DB_REMOTE_DIR, f), timeout=90)
            if code != 0:
                raise RuntimeError("拉取 %s 失败: %s" % (f, err.decode("utf-8", "replace")))
            with open(dst, "wb") as fh:
                fh.write(out)
        elif os.path.exists(dst):
            os.remove(dst)          # 设备上已无该文件，本地残留必须清掉
    main = os.path.join(d, DB_NAME)
    if not os.path.exists(main) or os.path.getsize(main) == 0:
        raise RuntimeError("拉到的 aurora.db 为空")
    _stats["pull"] += 1
    return main


def push(local_main=None, tag=""):
    """把本地主库推回设备（清掉 -wal/-shm）。调用方应先 force-stop 应用。"""
    main = local_main or os.path.join(_work(tag), DB_NAME)
    if not os.path.exists(main):
        raise RuntimeError("没有可推回的库: " + main)
    conn = sqlite3.connect(main)
    try:
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.commit()
    finally:
        conn.close()
    code, _, err = adb("push", main, "/data/local/tmp/aurora-push.db", timeout=90)
    if code != 0:
        raise RuntimeError("push 失败: " + err.decode("utf-8", "replace"))
    # 注意：/data/local/tmp 属 shell:shell，run-as 降到 app uid 后删不掉它，
    # 所以清理临时文件必须由 adb shell（shell 身份）单独做，不能塞进 run-as 的 sh -c 里。
    cmd = ("run-as %s sh -c 'cp /data/local/tmp/aurora-push.db %s/%s; "
           "rm -f %s/%s-wal %s/%s-shm'"
           % (PKG, DB_REMOTE_DIR, DB_NAME, DB_REMOTE_DIR, DB_NAME, DB_REMOTE_DIR, DB_NAME))
    code, out, err = adb("shell", cmd, timeout=90)
    adb("shell", "rm", "-f", "/data/local/tmp/aurora-push.db", timeout=30)
    if code != 0:
        raise RuntimeError("推回失败: " + (err or out).decode("utf-8", "replace"))
    _stats["push"] += 1


def is_write(sql):
    s = sql.strip().lstrip("(")
    head = s.split(None, 1)[0].lower() if s else ""
    if head == "pragma":
        return "=" in s          # PRAGMA user_version=14 是写；PRAGMA user_version 是读
    return head in WRITE_KEYWORDS


def run_sql(sql, tag=""):
    """在本地副本上执行 SQL，返回 sqlite3 CLI 风格的文本输出。"""
    main = pull(tag)
    conn = sqlite3.connect(main)
    out_lines = []
    try:
        cur = conn.cursor()
        cur.execute(sql)
        if cur.description:
            for row in cur.fetchall():
                out_lines.append("|".join("" if v is None else str(v) for v in row))
        writes = is_write(sql)
        if writes:
            conn.commit()
    finally:
        conn.close()
    if writes:
        adb("shell", "am force-stop %s" % PKG)   # 覆盖库文件前必须停掉写入者
        push(main, tag)
    return "\n".join(out_lines)


def tables(tag=""):
    main = pull(tag)
    conn = sqlite3.connect(main)
    try:
        rows = conn.execute(
            "select name from sqlite_master where type='table' order by name"
        ).fetchall()
    finally:
        conn.close()
    return " ".join(r[0] for r in rows)


def snapshot(tag="baseline"):
    """只读快照：把当前库复制到 <WORK>/<tag>/。"""
    src = pull()
    d = _work(tag)
    for f in DBFILES:
        s = os.path.join(WORK, f)
        if os.path.exists(s):
            shutil.copy2(s, os.path.join(d, f))
    return d


def restore(tag="baseline"):
    """把快照推回设备（会 force-stop 并清 WAL）。"""
    d = os.path.join(WORK, tag)
    main = os.path.join(d, DB_NAME)
    if not os.path.exists(main):
        raise RuntimeError("没有快照可还原: " + main)
    adb("shell", "am force-stop %s" % PKG)
    push(main, tag)


def handle(cmd):
    """拦截 `run-as <pkg> sqlite3 <db> <sql>`；命中返回 (True, 输出)。"""
    m = _RE.match(cmd or "")
    if not m:
        return False, ""
    sql = m.group(3).strip()
    if len(sql) >= 2 and sql[0] == sql[-1] and sql[0] in "\"'":
        sql = sql[1:-1]
    if not sql:
        return True, ""
    if sql.lower() in (".tables", ".tables;"):
        return True, tables()
    if sql.startswith("."):
        raise RuntimeError("devdb 不支持点命令: " + sql)
    return True, run_sql(sql)


def stats():
    return dict(_stats)
