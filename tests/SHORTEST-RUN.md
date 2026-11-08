# SHORTEST-RUN.md — 插线后 30 分钟内跑完的最短验收序列

> 目的：手机一插上就能按顺序执行，不在现场临时想步骤。
> 前提：`adb devices` 能看到 `device`；仓库在 `D:\手机\re\ccg`。
> 每一项都要留下 `tests/shots/<时间戳>/` 里的日志与截图，失败不许「再试一次就当过」。

## 0. 准备（2 分钟）

```bash
cd /d/手机/re/ccg
bash tests/verify_v14.sh --apk        # 7 步全跑；第 4 步会打印设备是否就位
```
- 若第 4 步仍 SKIP：设备没连上，停，别往下走。
- 若第 1–3 步有 FAIL：先修，不在设备上验一个已经红的构建。

跑完先看一眼 `tests/shots/<ts>/jvm.log`、`audit-selftest.log`、`migrate-replay.log` 是否全绿。

## 1. 备份与还原演练（3 分钟，必做第一步）

```bash
bash tests/fault_reset.sh save        # .backup 一致快照 + 指纹
bash tests/fault_reset.sh restore     # 必须打印「还原校验：PASS」
```
- 目的：证明「就算后面的注入把库弄坏，我也有能力回到同一状态」。
- 没通过就别做任何注入 —— 否则是在没有降落伞的情况下跳伞。

## 2. V5→V14 真实迁移（5 分钟，需手上真有 V5 库）

- 若存在 V5 老库（`aurora.db` 且 `PRAGMA user_version=5`）：
  1. 把老库放到 `databases/aurora.db`（先按第 1 步备份当前库）
  2. 冷启 App，等首屏出现
  3. `run-as com.aurora.word sqlite3 databases/aurora.db < tests/MIGRATION-AUDIT.sql`
  4. 拉库 → `python tests/stable_digest.py --db 老库 --compare 新库`（**只比共同字段**，见 `migrate_replay.py` 的 `COMMON`）
- 若没有 V5 老库：这一项记 **未覆盖**，不许用 `migrate_replay.py` 的合成样本冒充。
- 判定：旧卡 S/D/due/reps/lapses 逐行一致、日志条数一致、新表新列索引齐、`bad_kind=0`、`dup_eid=0`。

## 3. 认义闭环（5 分钟）

按 `tests/ACCEPTANCE-PLAN.md` P0-3：显示英文 → 不给释义 → 先输中文 → 首次答案不可改 →
揭示 → 三态判定 → 评分。每步都比一次 `card` 的 S/D/due/reps。
关键反例：首次答对时**不得**出现「忘记了」这类矛盾评分按钮。

## 4. 拼写闭环（4 分钟）

P0-4：`go` 答 `goes` 必须判 FAIL（不接受屈折形式）；`book` 答 `books` 同理。
答对一次看 `spell` 表是否推进，且不得影响 `card`。

## 5. 同日封口（3 分钟）

同一词当天认义测过后，进拼写线应被拒（`retrieval_guard` 主键冲突）。
查：`SELECT * FROM retrieval_guard WHERE local_date=date('now','localtime');`

## 6. 故障注入（8 分钟，只在第 1 步通过后做）

按 `tests/FAULT-INJECTION.md` 顺序做 F1 → F2 → F3 → F4 → F5 → F8，每条之后：
```bash
bash tests/fault_reset.sh restore
```
F6（强杀）与 F7（三次冷启）最后做。

## 7. 句型 T1–T5/T5b（8 分钟）

```bash
python tests/pattern_probe.py
```
- T1 五项：前 3 可开始 / 第 4 被拒 / 重启后仍受限 / 被拒点击不消耗额度 / 日期键重置
- T2 两项：功能词 D+1/3/7/14 推进 + 答错不推进
- T3 两项：暂停（不在到期队列）+ **恢复入口可达**（找不到 → FAIL，对应 `BLOCKERS.md` 缺陷 1）
- T4 两项：替换练习不改调度 + 不进正式掌握率
- T5/T5b 六项（P0 修复专用）：待确认可见 / 详情页三要素 / 搁置后仍可见 / 重启后保持 / 重新练习只清阻塞 / 审核事件不算作答

## 8. 收口（2 分钟）

```bash
python tests/stable_digest.py --db tests/shots/<ts>/aurora.db > tests/shots/<ts>/digest-before.txt
# 冷启三次
for i in 1 2 3; do adb shell am force-stop com.aurora.word; adb shell am start -n com.aurora.word/.MainActivity; sleep 8; done
adb shell "run-as com.aurora.word cat databases/aurora.db" > tests/shots/<ts>/aurora-after.db
python tests/stable_digest.py --db tests/shots/<ts>/aurora.db --compare tests/shots/<ts>/aurora-after.db
```
- 期望 rc=0；非 0 说明冷启动有意外写入 → 停下来定位，不要继续。

## 9. 报告

把每项结果写进 `tests/ACCEPTANCE-COVERAGE.md` 的对应行，档位只升不降：
只有**真的执行并通过**才从 D 升到 C；执行了但失败写 FAIL，没执行保持 D。

## 时间预算

| 步骤 | 预算 | 累积 |
|---|---|---|
| 0 准备 | 2 | 2 |
| 1 备份演练 | 3 | 5 |
| 2 迁移 | 5 | 10 |
| 3 认义 | 5 | 15 |
| 4 拼写 | 4 | 19 |
| 5 封口 | 3 | 22 |
| 6 故障注入 | 8 | 30 |
| 7 句型 T1–T5/T5b | 8 | 38 |
| 8 收口 | 2 | 38 |

若只有 20 分钟：做 0/1/2/3/5/7（先证明迁移与闭环，故障注入可次日补）。
