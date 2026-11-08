# BLOCKERS.md — 真机验收阻断项与最小修复方案

> 依据 GPT 第 14 轮最终复裁：「下一轮只接受两类结果：真机验收证据，或者明确的真机阻断原因及最小修复方案。」
> 本文件记录两类东西：**阻断原因**（不是能力问题，是条件问题）与**已发现的产品缺陷**（有代码证据）。

---

## 阻断 1：没有设备，真机项一条都执行不了

- 现象：`adb devices` 为空字符串，`/d/asdk/platform-tools/adb.exe` 可用。
- 影响：P0-1…P0-6、P1-1…P1-4、T1–T5、F1–F9 全部未执行；`verify_v14.sh` 在第 4 步 `exit 2`。
- 这是**用户明确要求**（m00876：「今晚不插」），不是环境故障。
- 解除条件：插线并 `adb devices` 出现 `device`。届时最短序列见 `SHORTEST-RUN.md`。
- 这是本文件当前**唯一仍在生效**的阻断。

### 缺陷 1（已修复，待真机验证，P0）：待审核句型没有任何用户可达的恢复路径

**这不是测试脚本的问题，是产品缺陷。** GPT 第 15 轮定性 P0 并授权最小修复，已实现。

代码事实（修复前，`D:\手机\re\ccg\android\src\com\aurora\word\`）：

1. `Db.java` 取到期句型的 SQL 带 `AND needs_review=0`：一旦 `needs_review=1`，该句型永久不再进复习队列。
2. 唯一写这个字段的地方是 `savePatternState(...)`，参数来自 `Pattern.next(...).pendingReview`；
   而 `Pattern.next` 只会置 true、永不置 false，`MainActivity` 也没有任何界面入口把它改回 false。
3. `grep -n "resumePattern" *.java` → **零命中**。此前报告里提到的 `resumePattern()` 并不存在，
   是本人在叙述中误当成已实现。这一条我认错并已在代码里核实。

修复（按 GPT 第 15 轮裁决，**不含**「我说得对就推进」与「跳过自动顺延 3 天」）：

1. `Db.patternPending()` / `patternPendingCount()` / `lastPatternAnswer(pid)`（只认 `kind='formal'`）。
2. `Db.reviewPattern(pid, resume, now, eid, answer)`：`resume=true` 只做「清 `needs_review`、清 `unverified_streak`、`due=now`、`stage` 不变」；
   `resume=false` 一个字段都不改。两者都写一条 `kind='review_clear'`、`verdict=-1` 的事件，状态与事件在同一事务里。
3. `MainActivity.showPending()`：首页仅在 `needs_review>0` 时出现「待确认 N 句 ›」，详情页显示中文情境、原始输入、参考答案，
   并写明「系统暂时无法判断这句话是否正确，你可以查看参考答案后重新练习。」
4. 每日 3 句额度不变：恢复只是把句子放回队列，当日额度用完时仍然要等到下一个可用学习日（`startPattern()` 的原有限额判断未改）。
5. 白名单与断言同步：`MIGRATION-AUDIT.sql` 增 `review_clear_bad_verdict` / `review_clear_moved_stage` / `pending_without_state`；
   `smoke_adb.py` 的 kind 白名单与待确认入口检查同步；`pattern_probe.py` 新增 T5 六项。
6. 状态：**代码已实现并构建通过，真机行为未验证**（属阻断 1）。`tests/ACCEPTANCE-COVERAGE.md` 记为 C/D。

## 缺陷 2（已修）：备份/还原不是一致快照

- 原实现：`cp databases/aurora.db databases/ab-baseline.db` —— 库在跑时 WAL 可能压着未合并事务，复制出来是半截状态。
- 已改：`pattern_probe.backup()` 与 `tests/fault_reset.sh save` 都改成
  「先 `am force-stop` → `sqlite3 databases/aurora.db ".backup databases/ab-baseline.db"` → 重启 App」；
  还原时先删 `aurora.db-wal` / `aurora.db-shm` 再覆盖主库，并用 `md5sum` 比对还原前后指纹。
- 状态：脚本层已修；真机实跑未验证（属阻断 1）。

## 缺陷 3（已改口径）：T1 的「次日重置」不是真实跨天

- 现状：通过把 prefs 里 `patIds_<今天>` 改写成 `patIds_<昨天>` 来验证计数逻辑。
- 它能证明：当日计数按日期键隔离，键变了限制就重置。
- 它**不能**证明：真实系统时间跨零点、时区变化、App 恢复前台时的行为。
- 已在 `ACCEPTANCE-COVERAGE.md` 与测试输出里标注为「日期键逻辑测试」，不标为「真实跨天 PASS」。
