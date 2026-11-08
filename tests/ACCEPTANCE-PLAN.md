# ACCEPTANCE-PLAN.md — AURORA V14 真机验收计划

状态：**待真机验收**。本文件只描述「怎么证明」，不描述「代码里有什么」。
规则：没有真实输出（截图 + SQL 结果 + 日志）的条目一律记 FAIL，不接受「代码已实现」。

## 0. 前置条件与环境

| 项 | 要求 |
|---|---|
| 设备 | Android 7.0（API 24）及以上真机，`adb devices` 可见 |
| 安装包 | `D:\手机\re\ccg\aurora.apk`（V14，APK 520,948 B / dex 104,160 B / md5 `14a73ed253ee909bd4f2639eb619c041`） |
| 上行旧库 | 测迁移时必须先装**上一版** APK 造数据，再 `adb install -r` 覆盖安装；**不要卸载**，卸载会清库 |
| 备份 | 每次故障注入前 `adb shell "run-as com.aurora.word cp databases/aurora.db /sdcard/backup-<step>.db"` |
| 证据目录 | `D:\手机\re\ccg\tests\shots\<yyyyMMdd>\`，每条一张截图 + 一份 SQL 输出 |

一键自动检查（12→24 项）：`python D:\手机\re\ccg\tests\smoke_adb.py`
手动置换的步骤（如中文输入、视觉判断）由 `MANUAL` 标记，必须人工过一遍。

## 1. P0 项目（不过就不算能交付）

### P0-1 APK 安装与首次启动
- 步骤：`adb install -r aurora.apk` → `adb shell am start -n com.aurora.word/.MainActivity`
- 预期：启动无崩溃；首页显示「今日待复习」大数字、「开始今日复习」、「学新词 · 今天 N 个」、「练句型 / 学句型」
- PASS 标准：`adb shell pidof com.aurora.word` 非空；`adb logcat -d | grep -c "FATAL EXCEPTION"` = 0；`PRAGMA user_version` = 14；14 张业务表齐全
- FAIL 表现：闪退、白屏、首页数字与点进去的卡数不一致

### P0-2 V5→V14 迁移
- 步骤：旧版造数据（≥3 词有 card 记录、≥1 条 review_event、≥1 条 screen 日志）→ 存 baseline（`MIGRATION-AUDIT.sql`）→ 覆盖安装 → 启动 → 重跑同一 SQL
- 预期：word/card/spell/log/screen/session/review_event 行数与 baseline 完全一致；`card_checksum` / `spell_checksum` 完全一致；`idx_review_eid` 存在；`review_event` 有 `hinted/verdict/answer/matched_sense/event_type`；`pattern_state` 有 `unverified_streak/needs_review`；`pattern_event` 有 `kind`
- PASS 标准：逐字段一致 + `PRAGMA integrity_check` = ok + 重启一次不再触发迁移（版本已 14，数据不变）
- FAIL：任何一行 S/D/due/reps/lapses 被改写；历史事件 `event_type` 出现空值；重复启动导致行数增长

### P0-3 认义复习闭环
- 步骤：首页 →「开始今日复习」→ 看到英文单词（此时不得显示中文）→ 输入中文 → 提交 → 看对比 → 三档评分 → 回首页
- 预期：提交前无答案泄露；提交后显示标准释义与你写的答案；首次答对时评分按钮只有「有点难 / 记得」；评分后 `card` 的 S/D/due/reps 有变化、`review_event` 新增 1 条、`retrieval_guard` 新增 1 条
- PASS 标准：三者同时变化且只变化一次；`event_type='fsrs_review'`；`hinted` 与是否看过答案一致；`verdict` ∈ {0,1,2}
- FAIL：评分后无事件、事件无封口、同一卡在同一次会话里被评两次

### P0-4 拼写复习闭环
- 步骤：让拼写卡到期（或等待 D+2）→ 输入目标词原形 → 提交；再测一次输入屈折形式（go→goes）
- 预期：原形正确通过；屈折形式判错并提示「goes 是 go 的第三人称单数形式，拼写线只接受原形」；拼写评分只写 `spell` 表，不改 `card`
- PASS 标准：`spell` 的 S/D/due 变化、`card` 的 S/D/due 不变；`review_event.track='spelling'`
- FAIL：屈折形式被判通过；拼写评分污染认义卡

### P0-5 同日封口（Again 后不得当日再测）
- 步骤：某词评分选「忘记了」(rating=1) → 返回首页 → 再进复习 → 观察该词是否出现；再尝试同一词的另一通道
- 预期：当天不再出现；另一通道也被拒（toast「这个词今天已经测过了，这次不计分」）；该词 `due` 保持评分时算出的值，不被「今天测过」二次改写
- PASS 标准：`retrieval_guard` 里 `(w, local_date)` 唯一；当日该词 `review_event` 只有 1 条正式评分
- FAIL：Again 后 2.4 小时又冒出来（旧缺陷回归）；同词当日两条正式评分

### P0-6 事务故障注入
- 见 `FAULT-INJECTION.md` 的 F1–F6，全部 PASS 才算通过
- PASS 标准：任何一步失败，卡片、事件、封口、日志同时回滚，无半笔数据；重启后状态一致

## 2. P1 项目

### P1-1 跨天调度
- 步骤：D0 学一张新卡（新授三步）→ 当天不得再进复习队列 → 次日进复习 → 再次日
- 预期：D0 完成后当天不再出现（`isTaughtToday` 生效）；次日恢复为候选；`due` 按 FSRS 计算，未被 D0 覆盖
- PASS 标准：D0 当天该词无 `review_event` 正式评分；次日 `review_event` 出现且 `elapsed_hours` ≥ 12
- FAIL：D0 当天被要求再测；次日不出队

### P1-2 申诉持久化
- 步骤：认义输入一个意思对但词表里没有的同义表达（如针对 cn「漂亮的；美丽的」输「好看」）→ 判 INCORRECT → 点「我答的是对的 / 待核实」→ 重启 App → 再进复习 → 观察该词
- 预期：申诉落一条 `event_type='appeal'`、`rating=0`、`verdict=2` 的事件；`card` 的 S/D/reps/lapses 完全不变；当天该词被封口；次日恢复候选；额度只退一次；重启后申诉记录仍在
- PASS 标准：S/D 与申诉前完全相同；`retrieval_guard` 有该词；重复点申诉不产生第二条事件
- FAIL：申诉改变了 FSRS 状态；申诉后当天可以无限重测；重启后记录消失

### P1-3 句型与功能词
- 步骤：首页 →「学句型」→ 做 3 个不同 pattern_id → 试第 4 个；功能词单元（f-be / f-have / f-do）答对后看次日是否复现
- 预期：第 4 个被拒（每日 3 句共享上限）；功能词 D0 答对只推到 D+1，D+1 再独立答对才进 D+3，之后 D+7、D+14，不跳级；连续两次 UNVERIFIED 进 `needs_review=1` 且 `due` 不变
- PASS 标准：`pattern_state` 的 stage/due 与上述阶梯一致；`pattern_event.kind` ∈ {formal, collocation}；场景表达替换练习不改 stage/due
- FAIL：功能词 D0 直接进 3 天；跳级；UNVERIFIED 被改 due

### P1-4 UI 与统计口径
- 步骤：首页数字 vs 点进去的卡数；看「预计掌握 N」与「至少一义项 X%」「拼写 X%」
- 预期：首页 dueAll = 实际队列长度（认义 + 拼写，已排除封口与 D0 卡）；掌握数按词去重（card ∪ spell）；保持率分母只含 `elapsed_days>=7 AND hinted=0 AND verdict IN (0,1) AND event_type='fsrs_review'`
- PASS 标准：手算 SQL 与界面数字一致
- FAIL：界面写「记住 N」（旧口径回归）；样本 <5 仍显示百分比（应显示「数据积累中」）

## 3. 结果记录格式

每条在 `tests/accept-<yyyyMMdd>.md` 里写四行：

```
### P0-3 认义复习闭环 — PASS
证据：shots/20261107/p03-before.png, p03-after.png
SQL：card S=2.31 D=5.12 due=..., review_event 新增 1 条 eid=...
差异：无
```

FAIL 必须写「实际输出 vs 预期差在哪一行」，不接受「大概是缓存问题」。

## 4. 已知未验收风险（如实记录，不粉饰）

1. 真机一次都没跑过（`adb devices` 长期为空）。
2. 5→14 迁移只在纸面和 SQL 对账层面设计过，未在真实旧库上跑过。
3. 事务回滚只做了代码路径推理，未做真实注入。
4. 认义自动判分对词表外同义表达会判错，靠申诉兜底；申诉链路未真机验证。
5. 句型判定为「精确匹配 + 已知结构错误」，非精确匹配一律 UNVERIFIED，因此句型掌握率会偏低，这是设计取舍不是 bug。
