-- ============================================================
-- AURORA 迁移对账 SQL（只读）
-- 用法（真机）：
--   adb shell "run-as com.aurora.word cat databases/aurora.db" > before.db
--   adb shell run-as com.aurora.word sqlite3 databases/aurora.db < tests/MIGRATION-AUDIT.sql
--
-- 【重要：这里的 checksum 不是强校验】
-- GPT 第 12 轮验收裁决第一条：SQLite 里把浮点拼成文本再求和，会受浮点格式化、
-- 行返回顺序、SUM 溢出/精度影响 —— 只能当「粗筛」，不能当作「迁移前后完全一致」的证据。
-- 真正的指纹请用 tests/stable_digest.py：
--   * 固定排序列（card.w / review_event.eid / pattern_state.pid ...）
--   * 浮点按 repr(round(v,10)) 归一
--   * 逐行 JSON 化后整体 SHA-256，并记录行数
-- 比对两份库：
--   python tests/stable_digest.py --db before.db --compare after.db   # 0 一致 / 2 有差异
-- 因此本文件只负责：把「结构、行数、语义自洽、索引、新列齐全度」查出来。
-- 只支持 SELECT / PRAGMA，任何写操作都不该出现在这个文件里。
-- ============================================================

.headers on
.mode column

-- 0. 指纹方法说明（显式打印，防止有人把下面的粗筛当强校验）
SELECT 'digest_method' AS item,
       'rough-only; use tests/stable_digest.py for SHA-256' AS value;

-- 1. 版本与表清单
SELECT 'schema_version' AS item, (SELECT * FROM pragma_user_version) AS value;
SELECT 'tables' AS item, group_concat(name, ' ') AS value
FROM (SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name);

-- 2. 行数对账（迁移前后必须完全一致，除 pattern_* 新表）
SELECT 'word' AS tbl, COUNT(*) AS n FROM word
UNION ALL SELECT 'card', COUNT(*) FROM card
UNION ALL SELECT 'spell', COUNT(*) FROM spell
UNION ALL SELECT 'log', COUNT(*) FROM log
UNION ALL SELECT 'screen', COUNT(*) FROM screen
UNION ALL SELECT 'session', COUNT(*) FROM session
UNION ALL SELECT 'review_event', COUNT(*) FROM review_event
UNION ALL SELECT 'retrieval_guard', COUNT(*) FROM retrieval_guard
UNION ALL SELECT 'pattern_state', COUNT(*) FROM pattern_state
UNION ALL SELECT 'pattern_event', COUNT(*) FROM pattern_event;

-- 3. 粗筛指纹（仅作快速比对，不作为「一致」的最终证据）
SELECT 'card_rough' AS item,
       COUNT(*) || '/' || ROUND(SUM(s), 6) || '/' || ROUND(SUM(d), 6) || '/' ||
       SUM(state * 1000000 + reps * 1000 + lapses) AS value
FROM card;

SELECT 'spell_rough' AS item,
       COUNT(*) || '/' || ROUND(SUM(s), 6) || '/' || ROUND(SUM(d), 6) || '/' ||
       SUM(state * 1000000 + reps * 1000 + lapses) AS value
FROM spell;

-- 3b. 逐字段求和的独立口径（任一列变化都会让对应项变）
SELECT 'card_sums' AS item,
       's=' || ROUND(SUM(s), 8) || ' d=' || ROUND(SUM(d), 8) ||
       ' due=' || SUM(due) || ' last=' || SUM(COALESCE(last, 0)) AS value
FROM card;
SELECT 'spell_sums' AS item,
       's=' || ROUND(SUM(s), 8) || ' d=' || ROUND(SUM(d), 8) ||
       ' due=' || SUM(due) || ' last=' || SUM(COALESCE(last, 0)) AS value
FROM spell;

-- 4. 事件语义对账（V10 之后：event_type/hinted/verdict 必须有值且自洽）
SELECT 'event_total' AS item, COUNT(*) AS value FROM review_event;
SELECT 'event_type_null' AS item, COUNT(*) AS value
FROM review_event WHERE event_type IS NULL OR event_type = '';
SELECT 'bad_fsrs_rating' AS item, COUNT(*) AS value
FROM review_event WHERE event_type = 'fsrs_review' AND (rating < 1 OR rating > 4);
SELECT 'appeal_with_rating' AS item, COUNT(*) AS value
FROM review_event WHERE event_type = 'appeal' AND rating <> 0;
SELECT 'event_bad_verdict' AS item, COUNT(*) AS value
FROM review_event WHERE verdict NOT IN (-1, 0, 1, 2);

-- 5. 幂等约束是否真的建上了（V7 起必须有，建不上必须硬失败）
SELECT 'idx_review_eid' AS item, COUNT(*) AS value
FROM sqlite_master WHERE type = 'index' AND name = 'idx_review_eid';
SELECT 'dup_eid' AS item, COUNT(*) AS value
FROM (SELECT eid FROM review_event GROUP BY eid HAVING COUNT(*) > 1);
SELECT 'dup_guard' AS item, COUNT(*) AS value
FROM (SELECT w, local_date FROM retrieval_guard GROUP BY w, local_date HAVING COUNT(*) > 1);
SELECT 'dup_pattern_eid' AS item, COUNT(*) AS value
FROM (SELECT eid FROM pattern_event GROUP BY eid HAVING COUNT(*) > 1);

-- 6. 新列是否齐（V7–V14 每一步都要在）
SELECT 'review_event_cols' AS item,
       (SELECT COUNT(*) FROM pragma_table_info('review_event') WHERE name IN ('hinted','verdict','answer','matched_sense','event_type')) AS value;
SELECT 'pattern_state_cols' AS item,
       (SELECT COUNT(*) FROM pragma_table_info('pattern_state') WHERE name IN ('unverified_streak','needs_review')) AS value;
SELECT 'pattern_event_kind' AS item,
       (SELECT COUNT(*) FROM pragma_table_info('pattern_event') WHERE name = 'kind') AS value;

-- 7. 句型状态分布（迁移后不应突然出现 needs_review=1）
SELECT 'pattern_state' AS item,
       COUNT(*) || ' need_review=' || COALESCE(SUM(needs_review), 0) || ' streak_gt0=' ||
       COALESCE(SUM(CASE WHEN unverified_streak > 0 THEN 1 ELSE 0 END), 0) AS value
FROM pattern_state;

-- 7b. 句型事件语义（kind 只允许 formal/collocation/review_clear；场景表达与审核动作不得改调度）
SELECT 'bad_kind' AS item, COUNT(*) AS value
FROM pattern_event WHERE kind IS NULL OR kind NOT IN ('formal','collocation','review_clear');
SELECT 'collocation_moved_schedule' AS item, COUNT(*) AS value
FROM pattern_event WHERE kind = 'collocation'
  AND (stage_before <> stage_after OR due_before <> due_after);
-- 7c. 审核动作只能是「清人工阻塞」，绝不能冒充作答：verdict 必须 -1，且 stage 不得推进
SELECT 'review_clear_bad_verdict' AS item, COUNT(*) AS value
FROM pattern_event WHERE kind = 'review_clear' AND verdict <> -1;
SELECT 'review_clear_moved_stage' AS item, COUNT(*) AS value
FROM pattern_event WHERE kind = 'review_clear' AND stage_before <> stage_after;
SELECT 'pending_without_state' AS item, COUNT(*) AS value
FROM pattern_event WHERE kind = 'review_clear'
  AND pid NOT IN (SELECT pid FROM pattern_state);

-- 8. 今日封口与 D0 卡（跑一天真实学习后再看）
SELECT 'guard_today' AS item, COUNT(*) AS value
FROM retrieval_guard WHERE local_date = strftime('%Y-%m-%d', 'now', 'localtime');
SELECT 'd0_today' AS item, COUNT(*) AS value
FROM card WHERE state = 1 AND reps = 0
  AND date(last / 1000, 'unixepoch', 'localtime') = date('now', 'localtime');
