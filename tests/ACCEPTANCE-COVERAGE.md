# ACCEPTANCE-COVERAGE.md — 验收覆盖矩阵（第 16 版）

> 依据 GPT 第 12/13 轮裁决：四档分离（JVM / 离线 / 真机 / 未覆盖），
> 每行给证据来源；无证据一律写「未覆盖」。
> 重建时间：2026-11-07 02:3x　|　APK 520,948 B，容器 md5 `3b506e521287c18cbcb0a15cb2ee5f5c`；
> `classes.dex` 104,228 B，md5 `28a9752bdf6d30f95b4f7a410ebf31b5`（此前文档把 dex 的 md5 误记成 APK 的，已更正）。
> APK 容器 md5 会随 zip 打包时间戳变化，验收以 dex/manifest/resources 的内容哈希与行为证据为准。
> 设备：`adb devices` 为空（用户 m00876「今晚不插线」）→ 真机列全部未执行。

> **证据版本：见 `EVIDENCE-VERSION.md`（当前有效 EV-3）。**
> 本文件只引用 EV-3 的证据；EV-2 之前的「迁移对账 PASS」「冒烟通过」已作废——
> 检查器曾因注释块误判使 21 条断言空转，旧结论不再作为依据。

## 关于指纹精度的诚实声明（GPT 第 13 轮第 3 点）

`stable_digest.py` 默认 **exact 模式**：REAL 用 `float.hex()`（IEEE-754 双精度的无损表示），
任何一位变化都会改变摘要；整型/文本/二进制分别带类型标签，避免 `3` 与 `3.0` 混淆。
`--rounded` 模式（`repr(round(v,10))`）只用于大范围一致性粗筛，输出首行会打印
`float_mode: rounded（粗比对，不代表逐位一致）`，不得据此宣称「检测任何一位变化」。
证据：`_selftest_digest.py` —— 行序不同→一致(0)；改 1ms→差异(2)；**S 变动 1e-12 → exact 差异(2)、rounded 一致(0)**。

## 四档定义

| 档位 | 含义 |
|---|---|
| **A. JVM 已验证** | 纯 Java 逻辑，桌面 JVM 单测通过 |
| **B. 离线已验证** | 不依赖 Android 运行时、本机可复现（SQL / 指纹 / 迁移语句重放 / 脚本自身） |
| **C. 真机已通过** | 真机上完成 UI 操作并比对前后 DB |
| **D. 未覆盖** | 需真机、未写成自动化、或依赖人工判断 |

## 一、认义 / 拼写 / 调度

| 验收项 | 档位 | 证据 |
|---|---|---|
| FSRS 评分与间隔钳制 | A | `FsrsTest` 23 PASS |
| 不变量（due/state/reps、D0 规则） | A | `InvariantTest` 31 PASS |
| 中文释义三态判定 | A | `SenseTest` 30 PASS |
| 同日封口唯一键冲突整笔回滚 | A（逻辑）/ D（行为） | 事务包裹见 `Db.java`；真实 UI 行为未验证 |
| `review_event.eid` 唯一索引缺失即硬失败 | A（代码）/ D（触发） | `IllegalStateException` 分支未在真机触发 |
| **V5→V14 迁移语句可执行、旧数据不丢** | **B（合成样本）** | `make_v5_db.py` + `migrate_replay.py`：4 卡/2 拼写/3 日志/2 筛查，共同字段逐行 SHA-256 迁移前后 `card:same spell:same log:same screen:same`；新表/新列/索引齐；`event_type` 回填规则 4/4 符合；第二次冷启动不重放、指纹不变 |
| **真实 V5 历史库升级** | **D** | 手上没有真实 V5 库 —— 合成样本通过 ≠ 真实历史数据升级成功 |
| 评分事务中途失败回滚（F1/F2/F4/F8） | D | `FAULT-INJECTION.md` |
| 申诉失败重试不重复扣费（F5） | D | 同上 |
| 提交前强杀无半笔数据（F6） | D | 同上 |
| 冷启三次不重复迁移（F7，V14 基线） | D | 同上；注意与 V5→V14 迁移**分开取基线**（GPT 第 13 轮第 2 点） |
| 认义闭环 / 拼写闭环 | D | `ACCEPTANCE-PLAN.md` P0-3 / P0-4 |

## 二、句型线 / 功能词

| 验收项 | 档位 | 证据 |
|---|---|---|
| `Pattern.judge` 三态与缩写展开 | A | `PatternTest` 44 PASS |
| 普通句型 D+3→D+7→D+14 封顶 30 | A | `PatternTest` |
| **功能词 D+1→D+3→D+7→D+14** | A（算法）/ D（真机） | 算法：`PatternTest`「stage=2 必须 D+7」；真机：`pattern_probe.py` T2 未执行 |
| **功能词反向：答错/判不了不推进、due 不变** | D | `pattern_probe.py` T2「反向·答错不推进」未执行 |
| 连续两次 UNVERIFIED → 暂停、保留 due、**不在到期队列** | A（算法）/ D（真机） | 算法：`PatternTest`；真机：T3 未执行（已在断言里加 `in_queue==0`） |
| **暂停后可恢复（P0 修复）** | C（已实现 + 构建）/ D（真机） | `Db.reviewPattern()` + 首页「待确认 N 句 → 详情页 → 重新练习」；`pattern_probe.py` T3「恢复后重新入队」未执行 |
| **待审核句型的用户恢复路径** | C / D | 原缺陷（`Db.java` 队列带 `needs_review=0`、全代码无处置 0）已按 GPT 第 15 轮裁决修复：`Db.patternPending/patternPendingCount/lastPatternAnswer/reviewPattern` + `MainActivity.showPending()`；两个动作都不推进 stage，写 `kind='review_clear'`（`verdict=-1`）事件，状态与事件同一事务 |
| **待确认页三要素（原始输入 / 参考答案 / 无法判断提示）** | C / D | `MainActivity.showPending()`；`pattern_probe.py` T5「详情页要素」未执行 |
| **搁置后仍可见 / 重启后保持** | C / D | `pattern_probe.py` T5「搁置后仍可见」「重启后保持」未执行 |
| **重新练习不写掌握证据（无虚假正确评分）** | B（SQL 断言）+ D（行为） | `MIGRATION-AUDIT.sql` `review_clear_bad_verdict`、`review_clear_moved_stage`、`pending_without_state`；`pattern_probe.py` T5「重新练习恢复队列」「审核事件不算作答」未执行 |
| **每次成功审核恰好 +1 条 `review_clear`** | D（行为） | `pattern_probe.py` T5「搁置后仍可见」「重新练习恢复队列」都断言 `review_clear+1 == 1`（不是 `>0`）；GPT 第 15 轮要求「恰好一条」 |
| **恢复不绕过每日 3 句上限** | D（行为） | `pattern_probe.py` T5b「恢复不绕过每日上限」：造 prefs `patIds_<今日>=3 个 pid` + 对应 formal 事件 → 恢复 f-do → 回首页点句型入口 → 必须不进教学页、`formal` 计数不变、今日不同句型 ≤3；未执行 |
| ↳ T5b 的证据边界 | — | T5b 同时写 DB 事件与 prefs，**只证明「额度已满时恢复被正确阻止」**；不证明正常学习流程一定把 DB 与 prefs 同时更新正确——那属 T1 的真实 UI 学习流程（GPT 第 16 轮指出） |
| 每日 3 句：前 3 可开始 / 第 4 被拒 / **重启后仍受限** / **被拒点击不消耗额度** / **日期键重置（非真实跨天）** | D | `pattern_probe.py` T1 五项断言未执行；次日重置用 `shift_prefs_key(-1)` 改日期键实现，只算「日期键逻辑测试」，不标为真实跨天 PASS |
| 场景表达替换不改 stage/due、**不进正式掌握率** | B（SQL 断言）+ D（行为） | `MIGRATION-AUDIT.sql` `collocation_moved_schedule`；`pattern_probe.py` T4 两断言未执行 |
| `pattern_event.kind` 只允许 formal/collocation/review_clear | B | `MIGRATION-AUDIT.sql` `bad_kind`；`audit_selftest` 负控制注入 `weird` 被抓出 |

## 三、工具自身可信度

| 项 | 档位 | 证据 |
|---|---|---|
| 对账 SQL 能在 V14 同构库整段执行 | B | `audit_selftest.py` `[2] PASS | 结果行数 = 37`（修复前只有 16，见下） |
| **检查器空转修复** | B | 原 `run_audit()` 用 `stmt.startswith('SELECT')` 判断是否取行，凡「注释 + SELECT」开头的语句一律被当成非查询 → **21 条断言从未真正取值**（结果行数 16 vs 应有 37）。现改为剥离注释行后判断，并新增正控制（干净库三项必须为 0） |
| 对账 SQL 自陈粗筛局限 | B | 首行打印 `digest_method='rough-only; use tests/stable_digest.py for SHA-256'` |
| 指纹与行序无关 / 能抓单字段改动 / 能抓 1e-12 级 REAL 变动 | B | `_selftest_digest.py` 三个正反控制 |
| rounded 模式不冒充强校验 | B | 输出首行打印模式说明；1e-12 用例在 rounded 下仍报「一致」 |
| smoke 不把 SKIP 当通过 | B | `report()`：`p==0→2`、`s>0→3`、`m>0→4` |
| 句型探针无设备时 SKIP 且 rc=2 | B | 实测 |
| 故障注入可重复恢复 | B（脚本）/ D（实跑） | `fault_reset.sh save|restore` + `stable_digest --compare` 必须 rc=0 |
| 一键验收入口（7 步） | B | `verify_v14.sh`：JVM → 离线自检（含迁移重放）→ 构建 → 设备探测（无设备 exit 2）→ smoke → 探针 → 对账+指纹 |

## 四、明确未覆盖

1. 真实 V5 历史库迁移（无样本；合成重放不能替代）。
2. 真机全部行为项：P0-1…P0-6、P1-1…P1-4、T1–T5b、F1–F9。
3. 迁移后长期一致性（需连续数天使用后再跑指纹比对）。
4. `retention7` / 提示依赖率的真实口径（只有构造数据）。
5. 场景表达 33 条的真人可用性（只有 GPT 审阅）。

## 五、本轮结论

- 功能冻结；构建通过；JVM 128 PASS。
- 离线（EV-3）：`audit_selftest.py` 6 项 PASS、**37 条断言真正取值**（含正控制）；`migrate_replay.py` 7 项 PASS（合成样本）；指纹正反控制 3 项 PASS。
- 真机：零执行 → 不构成验收通过；无设备时 `verify_v14.sh` exit 2。
- **产品缺陷 1 条已修（P0，GPT 第 15 轮授权）**：待审核句型不可达 → 新增待确认页 + `review_clear` 审计事件；代码已构建，**真机行为未验证**（C/D）。
- **T5 第 7 项（额度隔离）已按 GPT 第 15 轮授权补上**，属行为项，真机未执行。
- **GPT 第 16 轮已收口**：验收准备阶段结束，状态 = 验收待执行；不再增加脚本、不再扩大功能，只等设备接入按 `SHORTEST-RUN.md` 执行。
- 一句话：**离线能证的都证了，不能证的一律标 D，没有用「代码看起来对」换过任何一行 PASS；
  检查器空转那次连自己都没放过——旧结论全部撤回，写进 `EVIDENCE-VERSION.md`。**
