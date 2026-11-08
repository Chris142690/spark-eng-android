# 实现状态（逐项，禁止虚构）

版本：aurora.apk 360,997 B ｜ sha256 `5c3aeb456c2a019b9260b8e4f83545e05cb008a41f4a5ea1bec9531edc7a9271` ｜ dex 33,160 B
词表：1,208 条 + `assets/forms.json` 合法词形白名单（1,035 词有屈折形）｜ 源码：`src/com/aurora/word/` 三文件（Db / Fsrs / MainActivity）

状态口径：**已实现** = 代码存在且有运行证据；**部分** = 代码存在但有关键缺口或未验证；**未实现** = 无代码。

## 一、调度与评分

| 功能 | 状态 | 证据 / 缺口 |
|---|---|---|
| FSRS-4.5 主算法 | 已实现 | `Fsrs.java` 71 行，`W` 17 参数（0.4872…2.8755），`DECAY=-0.5`、`FACTOR=19/81`、`TARGET_R=0.9`；`retrievability/interval/initStability/initDifficulty/next` 五个方法 |
| 三档评分（忘记/难/记得） | 已实现 | `addRateButtons()` rating=1/2/3 → `rate()`；真机已跑通并落库（实测 `rating=3 → s=3.7145, d=5.1618, ivl=3.7145`） |
| 卡片状态机 | 已实现 | `state` 语义：**1**=新授后待首测、**2**=正式 FSRS 卡、**3**=懒筛通过待次日确认、无记录=未学。查询一律 `state>=1`，`state=3` 在 `rate()` 里走 init 分支 |
| 35 张上限 | **已修（未真机验证）** | 原实现是"每次会话 35"；现改为 SharedPreferences 持久化 `day`/`done`，`bumpDay()` 跨会话累加，`startReview()` 进入前若已达 35 直接拒绝。仍有一处缺口：`db.due(CAP)` 一次最多取 35，中途放弃的名额不回收 |
| 每日新词数 | **部分** | 读 SharedPreferences `newPerDay`，默认 3，范围 2–8；**自动调节器未实现**（原设计：近 7 天完成率 ≥80% 且均时 <12min → +1，<60% 或 >20min → −1，第 14 天前不调） |
| 首测延迟 | 已实现 | `firstDelayMin()` 读 `first_delay`，默认 20 分钟，clamp 5–1440 |

## 二、教学流程

| 功能 | 状态 | 证据 / 缺口 |
|---|---|---|
| 新授三屏 | 已实现 | 屏1 词+音标+「看释义」→ 屏2 词性/释义/例句/译文 → 屏3 遮住释义回想 → 「显示释义」→「记住了 · 20 分钟后再复习」。真机已跑通 |
| 例句/单词点读 | 已实现 | 系统 TTS，`speak()`；单词与例句各自可点 |
| 懒筛（双通道判定） | **部分** | `buildScreen()` + `submitScreen()`：第一步输入中文（`matchCn`），第二步填挖空的例句（`blank` + `matchEn`）。**本轮新增，从未在真机运行过** |
| 中文判定 | 已实现（保守） | 规范化只保留汉字，按 `；;，,\n、/（）()` 切义项，要求**完全相等**；不接受同义表达、不做子串包含。离线对 1,208 词全量跑过：每个词的首义项都能自匹配，0 失败 |
| 挖空生成 | 已实现 | 词边界正则 + 屈折形；离线全量 1,208/1,208 命中（`data/selfcheck.py` 可复现） |
| 次日确认 | **部分** | `state=3` 的卡 24h 后进复习队列，再走一次双通道；通过后才调 `rate()` 由 FSRS 初始化 S/D。**从未在真机运行过**；`due()` 只按 `due<=now`，不做"至少 24 小时"的时间戳校验 |

## 三、数据

| 功能 | 状态 | 证据 / 缺口 |
|---|---|---|
| 词表 | 已实现 | `assets/words.json` 1,208 条，字段 w/ipa/pos/cn/ex/excn/frq/tag。来源 ECDICT（770,611 条）+ Tatoeba 句对，构建脚本 `data/build_lexicon.py` |
| 例句口语化 | 已实现 | `data/colloquial.py` 30 条规则 + 保护名单；经规则改写 172 条，残留正式形式 0 条 |
| 词频削词 | 已实现 | `data/rebuild_select.py`：ECDICT frq/bnc 补真实词频 → 派生词归并 → 保护名单（旅行/礼貌/求助/功能词）→ 按频率取 1,200 |
| 存储 | 已实现 | SQLite `aurora.db` VER=2，四表 `word / card / log / screen`，索引 `idx_card_due(state,due)` |
| 复习日志 | 已实现 | `log` 表记 rating/ms/prev_ivl/new_ivl/prev_s/new_s |
| 筛查日志 | 已实现 | `screen` 表记原始输入 input、判定 ok、用时 ms、步骤 step；**不写 S/D，不写 log** |
| 数据导出 | 已实现 | 长按首页底部条 → `getExternalFilesDir/aurora-export.json`，导出 logs + cards；真机已验证 |

## 四、明确未实现（不要当成已有）

- **周复习 / 月度检测：完全没有代码**
- **干扰项 / 四选一：没有代码**（`cn` 里没有干扰项数据，`words.json` 也不含 distractor 字段）
- 拼写线独立 FSRS（原设计认义 R=0.90 / 拼写 R=0.50 两套状态）：**没有**
- 听辨测验：**没有**（原网页测站里有，原生 App 未迁移）
- 首页英语小知识详情面板：**没有**（`tips.json` 24 条在网页版，未进 assets）
- 新词数自动调节器：**没有**
- 词频分层 tag：**没有**（全部 `tag=core`，削词时按 frq≤5437 分过 core/ext，后被统一回 core）

## 五、已知缺陷（会被审查抓到的）

1. **35 张不是真上限**：见上表；`startReview` 一次取 35，不足不补，跨会话可重复触发。
2. ~~**DB 升级丢弃全部数据**~~ **已修**：`onUpgrade` 改为 `CREATE TABLE IF NOT EXISTS` 增量迁移 + `loadWordsIfEmpty()`，不再 DROP card/log。
3. **无 IME 防护**：中文输入依赖系统输入法，未做"从 DOM/控件现读 + 二次确认"。历史上网页版出现过一次交卷答案全变成 "1" 的故障（`elapsed 1586s`），原生版尚未验证是否重现。
4. **无崩溃上报**：异常只 `Log.e("aurora", ...)`。
5. ~~**`screen` 表无导出**~~ **已修**：`exportJson()` 增 `screens` 数组与 `day` 计数。
6. **懒筛例句挖空提示性过强**（GPT 指出）：`I don't ___ if I can help.` 这类固定句型靠上下文就能猜中，测的是语境推断不是词汇回忆。**未修**，待改成"中文释义→英文自由输入"。
7. **无单测框架**：只有离线 Python 脚本对词表跑判定，Java 侧零测试。

## 六、审查后已修（P0）

| 缺陷 | 位置 | 后果 | 修法 | 状态 |
|---|---|---|---|---|
| 首次评分错误放大 FSRS 稳定性 | `MainActivity.java` `rate()` 的 `isNewCard` | 原判据 `k == null`（我上一轮改为 `k==null \|\| k.state==3`）**漏掉 state=1**，新授待首测的卡用 `s=0` 走 `next()`：Again 得 0.4872、Hard 得 **12.41**、Good 得 **177.53**，而非 0.4872/1.4003/3.7145。首次成功被放大 47 倍，直接污染全部后续调度 | 改判据为 `k.reps == 0`（从未评过分），同时覆盖 state=1 与 state=3 | **已修**，dex 29,540 B / apk sha256 `80549fa4…c903`，**尚未真机复验** |

上面这条是 GPT 独立审查第一轮抓到的，比我自查的七条缺陷更致命：它不是"功能缺失"，是"已经有了的功能算错"。

## 七、第二轮审查后的修复（2026-11-05 22:50–23:15）

| # | 缺陷 | GPT 判定 | 修法 | 文件 | 状态 |
|---|---|---|---|---|---|
| 1 | `matchEn()` 机械拼接后缀，接受 `goed/sayed/studyed` | P0 漏洞 | 改为查 `assets/forms.json` 白名单（数据来自 ECDICT `exchange` 字段：p/d/i/3/s/r/t 标记；1,208 词中 1,035 有形态，其余只接受原形） | `MainActivity.java` `matchEn` + `loadForms()` | 已修，未真机验证 |
| 2 | 次日确认失败后仍可评分 Good | P0 漏洞 | 失败分支：若残留 `state=3` 卡则重置为 `state=1, s=0, d=0, reps=0, due=now`；置 `curIsNew=true` 回完整三屏 | `MainActivity.java` `submitScreen` | 已修，未真机验证 |
| 3 | 35 张跨会话重置 | P0 | SharedPreferences `day`/`done`/`newcnt` 持久化；`bumpDay()`/`bumpNew()`；`startReview/startNew` 进入前按当日已用量封顶 | `MainActivity.java` | 已修，未真机验证 |
| 4 | DB 升级 DROP 全部表 | P0 | `onUpgrade` 改增量迁移，不再丢 card/log | `Db.java` | 已修，未真机验证 |
| 5 | `screen` 不导出 | P1 | `exportJson()` 增 `screens` + `day` | `Db.java` | 已修，未真机验证 |
| 6 | 构建脚本假成功 | 自查 | `build.sh` 增 `PIPESTATUS` 检查、d8 产出检查、APK 五条目齐全 + 4 字节对齐校验 | `build.sh` | 已修，本次即由该校验拦住一次假包 |

**数据来源更正**：`rebuild_select.py` 用的是 ECDICT 的 `frq`/`bnc` 字段，**不是 SUBTLEX**。此前文档里写 SUBTLEX 是错的。原始 `ecdict.csv` 66 MB 未放进审查包（许可证与体积原因），故削词结果无法从包内独立复现——这是已知缺口。

### 7.1 已验证 vs 未验证（更新）

| 项 | 状态 | 证据 |
|---|---|---|
| FSRS-4.5 首次评分正确性 | **已验证（运行）** | `tests/run_tests.sh` → pass=15 fail=0 |
| P0 缺陷存在性 | **已验证（运行）** | next(0,0,0.0,3)=177.5292 |
| 构建链失败即中止 | 已验证（构建日志） | javac 报错时 `!! JAVAC FAILED` 并退出 |
| APK 结构完整 + 4 字节对齐 | 已验证（脚本） | 五条目齐全，header_offset % 4 == 0 |
| 屈折形白名单拦截 | 未验证 | 无真机 |
| 次日确认失败回退 | 未验证 | 无真机 |
| 35 张跨会话封顶 | 未验证 | 无真机 |
| DB 升级保数据 | 未验证 | 无真机 |
