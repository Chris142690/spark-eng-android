# 验收证据版本记录

一句话规则：**每条 PASS 必须能指到「哪个版本的检查器 + 哪条命令 + 哪行输出」**，
指不到的一律按「未执行」处理。检查器自身有缺陷时，它给出的 PASS 全部作废。

## EV-3（当前有效）— 2026-11-07

| 项 | 值 |
| --- | --- |
| 检查器版本 | `audit_selftest.py` **v3**（修正注释块导致 21 条断言空转；读审计自身输出取负控制值；增正控制） |
| 断言条数 | `MIGRATION-AUDIT.sql` 共 **37** 条 `AS item` 断言，全部被真正 `fetchall` |
| 离线结果 | `[1] DDL 建库 OK` / `[2] 结果行数 = 37` / `[3] 单库指纹 PASS` / `[4] 负控制(改 last) 差异 rc=2 PASS` / `[5] 负控制(注入非法 kind / collocation 改调度) PASS` / `[6] 正控制(干净库三项计数均为 0) PASS` |
| 迁移重放 | `migrate_replay.py`：V5→V14 `user_version=14`、旧数据 `card/spell/log/screen` 共同字段 `same`、新结构齐全、`event_type` 回填 4/4、幂等 PASS |
| JVM 单测 | Fsrs 23 + Invariant 31 + Sense 30 + Pattern 44 = **128 PASS / 0 FAIL** |
| 构建 | APK **525,044 B** md5 `d304b2fd123c6643d474137985dd2078`；`classes.dex` 108,520 B md5 `1e93de2eb789e8b37a8c2d7d3aec0955` |
| 真机 | **0 项**。`adb devices` 为空，P0/P1 与 T1–T5b 全部 SKIP，不计入通过 |

## EV-2（作废）— 检查器空转期

- `audit_selftest.py` v2 用 `stmt.lstrip().upper().startswith("SELECT")` 判定是否取行，
  凡「`--` 注释 + SELECT」的语句被判为非查询 → **21 条断言从未执行**，结果行数只有 16。
- 期间报出的「迁移对账全 PASS」**全部撤回**，不作为任何结论的依据。
- 该缺陷由自查发现（行数 16 与 SQL 里 37 条 `AS item` 不符），非外部指出。

## EV-1（作废）— 早期冒烟

- `smoke_adb.py` 的 `report()` 在 `f==0` 时直接打印「装机可用」并返回 0，
  于是「全部 SKIP」会被读成「通过」。已改为：`f>0→1`、`p==0→2`、`s>0→3`、`m>0→4`。
- 期间「真机 smoke 通过」的说法作废；当时无设备，一条真机项都没跑过。

## 证据定位表

| 结论 | 命令 | 有效版本 |
| --- | --- | --- |
| JVM 128 PASS | `bash tests/run_tests.sh` | EV-3 |
| 离线对账 37 断言 + 正负控制 | `python tests/audit_selftest.py` | EV-3 |
| V5→V14 迁移可重放 | `python tests/migrate_replay.py` | EV-3 |
| 构建可复现 | `cd /d/aw && bash build.sh` | EV-3 |
| 真机 smoke | `python tests/smoke_adb.py` | 无（0 项执行） |
| 句型 T1–T5b | `python tests/pattern_probe.py` | 无（0 项执行） |
| 一键全链路 | `bash tests/verify_v14.sh` | EV-3（真机段 exit 2） |

## 收口状态（GPT 第 16 轮，2026-11-07）

- 验收准备阶段**正式收口**，功能保持冻结；不再增加脚本、不再扩功能。
- 当前状态：**验收待执行**——即「纸面部分到此为止，剩下只能靠真机」。
- 证据边界（记在这里防止以后自我拔高）：T5b 是**构造**了「额度已满」的前置状态，
  只能证明「满额时恢复被阻止」；正常学习流程是否把 DB 与 prefs 同时更新正确，仍须由 T1 的真实 UI 流程证明。

## 撤回声明

1. 撤回 EV-2 期间全部「迁移对账 PASS」结论（检查器空转）。
2. 撤回 EV-1 期间全部「冒烟通过」结论（全 SKIP 被读成通过）。
3. 撤回此前「`resumePattern` 已实现」的报告——代码里该函数不存在，待审核句子当时永久不可达；
   现由 `Db.reviewPattern()` + 首页「待确认」入口替代，属**代码实现**，真机未验证。

---

## EV-4 模拟器实跑（2026-11-07 07:2x，Android 11 / API 30 / x86_64，AVD a2，端口 5556）

- 设备类型：**模拟器**，不是真机；真机 P0/P1 仍为 0 项执行。
- `smoke_adb.py`：`PASS=39 FAIL=0 MANUAL=1 SKIP=0`（`tests/shots/smoke-run2.log`）。
- `pattern_probe.py`：T5 六项全 PASS、T4 两项全 PASS、T2 反向 PASS、T1 额度不消耗 PASS；
  T1 正向/第4个被拒、T2 阶梯、T3 暂停仍 FAIL，原因是**正式测试屏作答未落库**（自动化流程未打通），
  不是产品判定，明细见 `tests/EMULATOR-EVIDENCE.md`。
- 本次实测顺手修掉 6 个脚本缺陷（返回键文案、缺 `import time`、`find` 子串误命中提示语、
  点完不等待就 dump、`run_one_pattern` 改状态机、输入前先点中框），清单同上文档。
- 旧结论不变：EV-3 仍是离线证据的有效版本；EV-4 只增加「模拟器行为证据」一档。
