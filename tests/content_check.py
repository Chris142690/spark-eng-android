#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""句型内容离线结构校验。

为什么需要它：真机上 `MainActivity.loadPatterns()` 用 `getJSONObject` 读 `examples`，
而 `f-be/f-have/f-do` 一度把 examples 写成 `["I am tired.","我累了。"]` 数组，
导致 `JSONException` → 整份 patterns.json 加载失败 → 15 条句型全废、
`kind='formal'` 事件永不产生、T1/T2/T3 全部假 FAIL。
单条坏数据能让整包静默失效，所以把「结构合法」变成构建前的硬门。

规则（与 MainActivity.Pat / Collocation 的读取方式一一对应）：
  patterns.json      : 数组；id 唯一；kind ∈ {pattern, function}；
                       examples 必须是 [{"en","cn"}]（对象，不是字符串数组）；
                       reference_answers 非空字符串数组；
                       formula / prompt_zh / meaning_zh / error_focus / content_version 存在
  collocations.json  : 数组；collocation_id 唯一；pid 必须存在于 patterns；
                       每个 pid ≤ 3 条；english / zh / teaching_note 非空
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(_ROOT, "app", "assets")
if not os.path.isdir(ASSETS):
    ASSETS = os.path.join(_ROOT, "android", "assets")
PATTERNS = os.path.join(ASSETS, "patterns.json")
COLLOC = os.path.join(ASSETS, "collocations.json")

PAT_KEYS = ("pattern_id", "cefr_target", "scenario", "formula", "prompt_zh",
            "meaning_zh", "reference_answers", "examples", "substitutions",
            "error_focus", "content_version", "required_vocab")
COLL_TEXT = ("collocation_id", "pattern_id", "english", "chinese", "scenario",
             "difficulty", "example_en", "example_zh", "content_version", "kind")
COLL_KEYS = COLL_TEXT + ("required_vocab", "new_vocab")

fails = []


def bad(msg):
    fails.append(msg)
    print("FAIL " + msg)


def load(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def nonempty_str(v):
    return isinstance(v, str) and v.strip() != ""


def check_patterns(rows):
    if not isinstance(rows, list) or not rows:
        bad("patterns.json 顶层必须是数组")
        return {}
    ids = {}
    func = 0
    for i, o in enumerate(rows):
        where = "patterns[%d]" % i
        if not isinstance(o, dict):
            bad(where + " 不是对象")
            continue
        for k in PAT_KEYS:
            if k not in o:
                bad(where + " 缺字段 " + k)
        pid = o.get("pattern_id")
        if not nonempty_str(pid):
            bad(where + " pattern_id 非法")
            continue
        where = "patterns[" + pid + "]"
        if pid in ids:
            bad(where + " pattern_id 重复")
        ids[pid] = o
        kind = o.get("kind", "pattern")
        if kind not in ("pattern", "function"):
            bad(where + " kind 非法: " + repr(kind))
        if kind == "function":
            func += 1
        for k in ("cefr_target", "scenario", "formula", "prompt_zh", "meaning_zh",
                  "content_version"):
            if not nonempty_str(o.get(k)):
                bad(where + " " + k + " 必须是字符串")
        ef = o.get("error_focus")
        if isinstance(ef, str):
            if not ef.strip():
                bad(where + " error_focus 不能为空")
        elif isinstance(ef, list):
            if not ef or not all(nonempty_str(x) for x in ef):
                bad(where + " error_focus 数组必须非空且元素为字符串")
        else:
            bad(where + " error_focus 必须是字符串或字符串数组")
        ans = o.get("reference_answers")
        if not isinstance(ans, list) or not ans:
            bad(where + " reference_answers 必须是非空数组")
        else:
            for a in ans:
                if not nonempty_str(a):
                    bad(where + " reference_answers 含空项")
        ex = o.get("examples")
        if not isinstance(ex, list) or not ex:
            bad(where + " examples 必须是非空数组")
        else:
            for j, e in enumerate(ex):
                if not isinstance(e, dict):
                    bad(where + " examples[%d] 必须是 {en,cn} 对象，实为 %s"
                        % (j, type(e).__name__))
                elif not (nonempty_str(e.get("en")) and nonempty_str(e.get("cn"))):
                    bad(where + " examples[%d] 缺 en 或 cn" % j)
        sub = o.get("substitutions")
        if not isinstance(sub, list) or not sub:
            bad(where + " substitutions 必须是非空数组")
        rv = o.get("required_vocab")
        if not isinstance(rv, list):
            bad(where + " required_vocab 必须是数组")
        if "collocations" in o and not isinstance(o["collocations"], list):
            bad(where + " collocations 若存在必须是数组")
    print("patterns: %d 条（其中 function %d 条）" % (len(rows), func))
    return ids


def check_colloc(rows, ids):
    if not isinstance(rows, list) or not rows:
        bad("collocations.json 顶层必须是数组")
        return
    seen = {}
    per_pid = {}
    for i, o in enumerate(rows):
        where = "collocations[%d]" % i
        if not isinstance(o, dict):
            bad(where + " 不是对象")
            continue
        for k in COLL_TEXT:
            if not nonempty_str(o.get(k)):
                bad(where + " " + k + " 缺失或为空")
        rv, nv = o.get("required_vocab"), o.get("new_vocab")
        if not isinstance(rv, list) or not rv:
            bad(where + " required_vocab 必须是非空数组")
        if not isinstance(nv, list):
            bad(where + " new_vocab 必须是数组（可为空）")
        cid = o.get("collocation_id")
        if cid in seen:
            bad("collocation_id 重复: " + str(cid))
        seen[cid] = 1
        pid = o.get("pattern_id")
        if ids and pid not in ids:
            bad("collocation " + str(cid) + " 指向不存在的句型 " + str(pid))
        per_pid[pid] = per_pid.get(pid, 0) + 1
    for pid, n in sorted(per_pid.items()):
        if n > 3:
            bad("句型 %s 的场景表达超过 3 条（%d）" % (pid, n))
    print("collocations: %d 条，覆盖 %d 个句型" % (len(rows), len(per_pid)))


def selftest(pats, colls):
    """负控制：证明这个校验器真的会失败（历史上就是 examples 写成数组把整包弄废的）。
    每条变异都必须被抓到，抓不到说明检查器在空转。"""
    import copy
    ok = True
    cases = []

    m = copy.deepcopy(pats)
    m[0]["examples"] = ["I am tired.", "我累了。"]          # 真机上炸掉 loadPatterns 的写法
    cases.append(("examples 写成字符串数组", m, colls))

    m = copy.deepcopy(pats)
    m[1]["pattern_id"] = m[0]["pattern_id"]                 # id 重复
    cases.append(("pattern_id 重复", m, colls))

    m = copy.deepcopy(colls)
    m[0]["pattern_id"] = "__no_such_pattern__"              # 指向不存在的句型
    cases.append(("场景表达指向不存在的句型", pats, m))

    m = copy.deepcopy(pats)
    m[2]["kind"] = "function-word"                           # kind 拼错
    cases.append(("kind 非法", m, colls))

    for name, pp, cc in cases:
        del fails[:]
        try:
            ids = check_patterns(pp)
            check_colloc(cc, ids)
        except Exception as e:                              # 抛异常也算抓到
            fails.append("exception: %s" % e)
        hit = len(fails) > 0
        print("%s 负控制 %s：%s" % ("PASS" if hit else "FAIL", name,
                                   "已抓 %d 项" % len(fails)))
        ok = ok and hit
    del fails[:]
    return ok


def main():
    for p in (PATTERNS, COLLOC):
        if not os.path.exists(p):
            print("FAIL 文件不存在: " + p)
            return 1
    try:
        pats = load(PATTERNS)
        colls = load(COLLOC)
    except ValueError as e:
        print("FAIL JSON 解析失败: %s" % e)
        return 1
    ids = check_patterns(pats)
    check_colloc(colls, ids)
    if fails:
        print("\n内容校验 FAIL %d 项" % len(fails))
        return 1
    if not selftest(pats, colls):
        print("\n内容校验 FAIL（负控制未抓到变异，检查器空转）")
        return 1
    print("\n内容校验 PASS（含 4 条负控制）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
