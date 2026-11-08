#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""构造一份「有代表性的 V5 测试库」，用于离线验证 V5→V14 迁移语句。

为什么是合成样本，而不是声称「真实迁移已验证」（GPT 第 13 轮裁决）：
  手上没有真实 V5 历史库。合成样本只能证明「迁移语句对测试样本有效」，
  不能替代「真实历史库升级成功」。两者在报告里必须分开写。

样本覆盖（按裁决要求列举的状态）：
  * 未学习卡（state=0，reps=0）
  * 已学习卡（state=2，含不同 S/D）
  * 到期卡（due 在现在之前）与未到期卡
  * 不同 reps / lapses 组合（含 lapses>0 的困难卡）
  * 历史复习日志（log，含 V5 新增的 track 列，历史行一律 recognition）
  * 筛查记录（screen）与特殊状态（state=3 的「已归档/暂停」）

用法：python tests/make_v5_db.py out.db
"""
import os
import sqlite3
import sys
import time

V5_DDL = [
    "CREATE TABLE word(w TEXT PRIMARY KEY, n INTEGER, ipa TEXT, pos TEXT, cn TEXT, ex TEXT, excn TEXT, syl TEXT)",
    "CREATE TABLE card(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL,"
    " reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)",
    "CREATE TABLE spell(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL,"
    " reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)",
    "CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, track TEXT, ts INTEGER,"
    " rating INTEGER, ms INTEGER, prev_ivl REAL, new_ivl REAL, prev_s REAL, new_s REAL)",
    "CREATE TABLE screen(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, ts INTEGER, step INTEGER,"
    " input TEXT, ok INTEGER, ms INTEGER)",
    "CREATE INDEX idx_card_due ON card(state, due)",
]

NOW = int(time.time() * 1000)
DAY = 86400000

WORDS = [
    ("say", 1, "/sei/", "v.", "说；讲", "Say it again.", "再说一遍。", "say"),
    ("go", 2, "/gou/", "v.", "去；走", "Let's go.", "走吧。", "go"),
    ("book", 3, "/buk/", "n.", "书", "a good book", "一本好书", "book"),
    ("water", 4, "/wɔ:tə/", "n.", "水", "a glass of water", "一杯水", "wa-ter"),
]

CARDS = [
    # w, state, due, s, d, reps, lapses, last, ivl —— 未学习
    ("say", 0, 0, 0.0, 0.0, 0, 0, 0, 0.0),
    # 已学习、未到期
    ("go", 2, NOW + 3 * DAY, 12.3456789012, 5.25, 4, 0, NOW - 2 * DAY, 5.0),
    # 已学习、到期
    ("book", 2, NOW - 2 * DAY, 3.3333333333, 7.125, 7, 2, NOW - 9 * DAY, 7.0),
    # 特殊状态（暂停/归档），S/D 极小差异，用来验无损比较
    ("water", 3, NOW - 30 * DAY, 0.000000000123, 9.9999999999, 11, 5, NOW - 40 * DAY, 30.0),
]

SPELL = [
    ("go", 1, NOW - DAY, 1.0, 5.0, 0, 0, NOW - DAY, 1.0),
    ("book", 2, NOW + 5 * DAY, 6.5, 6.25, 3, 1, NOW - DAY, 6.0),
]

LOGS = [
    ("say", "recognition", NOW - 3 * DAY, 3, 2400, 0.0, 1.0, 0.0, 1.0),
    ("go", "recognition", NOW - 2 * DAY, 4, 1800, 1.0, 5.0, 1.0, 12.3456789012),
    ("book", "recognition", NOW - 9 * DAY, 1, 5200, 7.0, 3.3333333333, 7.0, 3.3333333333),
    # 历史拼写成绩：V5 之前没有拼写线，这里刻意只留 recognition，避免伪造
]

SCREENS = [
    ("say", NOW - 3 * DAY, 1, "说", 1, 3100),
    ("book", NOW - 9 * DAY, 3, "", 0, 8000),
]


def build(path):
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    con.execute("PRAGMA user_version=5")
    for d in V5_DDL:
        con.execute(d)
    con.executemany("INSERT INTO word VALUES(?,?,?,?,?,?,?,?)", WORDS)
    con.executemany("INSERT INTO card VALUES(?,?,?,?,?,?,?,?,?)", CARDS)
    con.executemany("INSERT INTO spell VALUES(?,?,?,?,?,?,?,?,?)", SPELL)
    con.executemany("INSERT INTO log(w,track,ts,rating,ms,prev_ivl,new_ivl,prev_s,new_s) VALUES(?,?,?,?,?,?,?,?,?)", LOGS)
    con.executemany("INSERT INTO screen(w,ts,step,input,ok,ms) VALUES(?,?,?,?,?,?)", SCREENS)
    con.commit()
    con.close()
    print("V5 样本库已生成：%s（user_version=5，card=%d spell=%d log=%d screen=%d）"
          % (path, len(CARDS), len(SPELL), len(LOGS), len(SCREENS)))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法：python tests/make_v5_db.py <out.db>")
        sys.exit(64)
    build(sys.argv[1])
