# FAULT-INJECTION.md — 事务与幂等故障注入（按 GPT 第 12 轮裁决修订）

目标：证明「任一步失败，卡片 / 事件 / 封口 / 日志同时回滚」，且不会出现半笔数据。

## 前置铁律（本轮按验收裁决改写）

1. **注入前必须有可复现基线**，不是随手 cp 一份就算：
   ```bash
   bash tests/fault_reset.sh save      # 抓真库到 tests/shots/baseline/ 并记录 SHA-256 指纹
   ```
2. **每次注入结束必须还原并校验指纹**：
   ```bash
   bash tests/fault_reset.sh restore   # 从基线覆盖回去，再跑 stable_digest --compare，必须 rc=0
   ```
   指纹不一致时脚本会打印 `PRAGMA integrity_check`，并拒绝在未确认的情况下继续。
3. **被测库按「一次性副本」对待**：真库本身会被注入命中（否则测不到事务），但它的唯一真身在
   `tests/shots/baseline/aurora-baseline.db` + 指纹文件里，任何一步坏了都能回到同一状态。
   绝不允许「带着注入残留」去提交验收结果。
4. **不做不可逆破坏**：改名（`RENAME TO`）优先；不 `DROP TABLE`、不改列类型、不清空表。
5. 注入只在**开发机自用的这份库**上做；如果这份库里有真实学习记录，先 `save` 再动手。

> 为什么不改系统时间、不改用户记录做跨天测试：见 `pattern_probe.py` —— 它用
> 「seed 一条 seed 状态 + 备份还原」模拟跨天，而不是拨手机日期。

---

## F1 卡片写入后、review_event 写入前失败

**注入**（写入必失败）：
```bash
adb shell "run-as com.aurora.word sqlite3 databases/aurora.db \"ALTER TABLE review_event RENAME TO review_event_x\""
```
**操作**：进复习，对一张卡评分。
**预期**：评分报错/提示保存失败；`card` 的 S/D/due/reps **完全没变**；`retrieval_guard` 无新增。
**恢复**：`bash tests/fault_reset.sh restore`
**判定**：卡片未变 + 无封口 = PASS；卡片变了但无事件 = FAIL（半笔数据）。

## F2 review_event 写入后、封口写入前失败

**注入**：`ALTER TABLE retrieval_guard RENAME TO guard_x`
**预期**：整笔回滚 —— `card` 不变、`review_event` **也不新增**。
**恢复**：`bash tests/fault_reset.sh restore`
**判定**：三张表都不变 = PASS；`review_event` 多了一条而 `card` 没变 = FAIL。

## F3 封口唯一键冲突 → 整笔回滚

**注入**（只插一行，不改结构）：
```bash
adb shell "run-as com.aurora.word sqlite3 databases/aurora.db \
  \"INSERT INTO retrieval_guard(w,local_date,track,ts,event_id) VALUES('<WORD>',date('now','localtime'),'recognition',0,'manual');\""
```
**操作**：对同一词评分。
**预期**：toast「这个词今天已经测过了，这次不计分」；`card` 不变；`review_event` 不新增；当前卡被跳过。
**判定**：无评分、无事件 = PASS；事件多一条 = FAIL。

## F4 日志写入失败 → 前面所有修改回滚

**注入**：`ALTER TABLE log RENAME TO log_x`
**预期**：`card` 不变、`review_event` 不新增、`retrieval_guard` 不新增。
**判定**：三者全不变 = PASS。这条最容易漏 —— 日志看起来不重要，但它参与同一事务。

## F5 申诉保存失败后重试，不能重复扣费

**注入**：申诉时让写入失败（同 F4，把 `log` 改名），点「我答的是对的 / 待核实」。
**预期**：提示「保存失败，可以再点一次申诉」；额度回补一次；恢复表后再点一次，成功落**恰好 1 条** `event_type='appeal'`。
**验证**：
```sql
SELECT COUNT(*) FROM review_event WHERE event_type='appeal';
SELECT COUNT(*) FROM retrieval_guard WHERE local_date=date('now','localtime');
```
**判定**：申诉事件 1 条、额度净消耗 1 次 = PASS；额度扣两次或事件两条 = FAIL。

## F6 事务提交前被强制终止

**操作**：评分按钮点下后立即 `adb shell am force-stop com.aurora.word`（可循环 `adb shell "sleep 0.05; am force-stop com.aurora.word"` 逼近窗口）。
**预期**：重开后要么「评分完整生效（卡片+事件+封口都在）」，要么「完全没发生」。
**验证**：
```sql
SELECT COUNT(*) FROM review_event WHERE date(ts/1000,'unixepoch','localtime')=date('now','localtime');
SELECT COUNT(*) FROM retrieval_guard WHERE local_date=date('now','localtime');
SELECT reps FROM card WHERE w='<WORD>';
```
**判定**：三者自洽 = PASS；不自洽 = FAIL（说明没走同一事务）。

## F7 重复打开数据库不产生额外迁移

**操作**：连续冷启动 3 次，每次间隔 10 秒。
**验证**：三次之间 `PRAGMA user_version` 与 `stable_digest.py` 的每表行数/指纹完全一致。
**判定**：完全一致 = PASS；行数增长或版本跳动 = FAIL。
（这条对应「V14 迁移必须幂等」，与 V5→V14 升级测试分开。）

## F8 句型线故障（V14 新增，仅验收不改功能）

**注入**：`ALTER TABLE pattern_event RENAME TO pattern_event_x`
**操作**：答一道句型题。
**预期**：提示保存失败；`pattern_state.stage/due` 不变（不能「题没存下但调度推进了」）。
**恢复**：`bash tests/fault_reset.sh restore`
**判定**：state 不变 = PASS；state 变了但无事件 = FAIL。

---

## F9 待确认「重新练习」的事件写入失败（V14 新增，P0 修复专用）

**目的**：`reviewPattern()` 把「清 needs_review」与「写 review_clear 事件」放在同一事务里。
这条注入就是验证它真的原子：事件写不进去时，`needs_review` 必须没有被清掉。

**注入**：`ALTER TABLE pattern_event RENAME TO pattern_event_x`
**前置**：先让某句型进入待确认（两次判不了，或 `pattern_probe.seed(pid, 2, 1, 3, streak=2, needs=1)`）。
**操作**：首页「待确认 N 句」→ 点「重新练习」。
**预期**：提示「操作失败，请重试」；`pattern_state` 里该句 `needs_review` 仍为 1、`due` 未变；
第 1 条 logcat 无「成功」字样（失败必须可见，不许静默成功）。
**恢复**：`bash tests/fault_reset.sh restore`
**判定**：`needs_review` 保持 1 = PASS；被清成 0 但无事件 = FAIL（半笔数据）。

---

## 每轮结束的收口

1. `bash tests/fault_reset.sh restore` → 必须打印 `还原校验：PASS`。
2. `adb shell "run-as com.aurora.word sqlite3 databases/aurora.db 'PRAGMA integrity_check'"` → `ok`。
3. 把「注入命令 / 操作 / 实际输出 / 判定」四段写进 `tests/shots/<时间戳>/fault.log`，
   并在 `ACCEPTANCE-COVERAGE.md` 里更新对应行状态。
4. 未执行的条目保持 `未覆盖`，不得因为「代码看起来没问题」改写成 PASS。
