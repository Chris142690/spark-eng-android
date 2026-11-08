package com.aurora.word;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 本地数据库：词表 + 卡片状态 + 复习日志 */
public class Db extends SQLiteOpenHelper {
    private static final String NAME = "aurora.db";
    private static final int VER = 14;
    /** 事件类型：正式 FSRS 评分 vs 申诉（见 V10 迁移注释）。 */
    public static final String EV_FSRS = "fsrs_review";
    public static final String EV_APPEAL = "appeal";
    private final Context ctx;

    public Db(Context c) {
        super(c, NAME, null, VER);
        this.ctx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE word(w TEXT PRIMARY KEY, n INTEGER, ipa TEXT, pos TEXT, cn TEXT, ex TEXT, excn TEXT, syl TEXT)");
        db.execSQL("CREATE TABLE card(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL, reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)");
        // 拼写线独立表：与 card 结构相同，但物理分开。GPT 裁决「认义与拼写两套独立 FSRS，不共用 S/D」，
        // 两张表正好对应这个语义，且认义线的代码与数据完全不受影响。
        db.execSQL("CREATE TABLE spell(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL, reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)");
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, ts INTEGER, rating INTEGER, ms INTEGER, prev_ivl REAL, new_ivl REAL, prev_s REAL, new_s REAL)");
        db.execSQL("CREATE TABLE screen(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, ts INTEGER, step INTEGER, input TEXT, ok INTEGER, ms INTEGER)");
        db.execSQL(Ddl.EVENT);
        db.execSQL(Ddl.SESSION);
        db.execSQL(Ddl.SENTENCE);
        db.execSQL(Ddl.GUARD);
        db.execSQL(Ddl.PATTERN_STATE);
        db.execSQL(Ddl.PATTERN_EVENT);
        db.execSQL(Ddl.PATTERN_EID);
        db.execSQL("CREATE INDEX idx_card_due ON card(state, due)");
        db.execSQL(Ddl.EID_INDEX);
        loadWords(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        // 逐版本迁移：每个版本一个独立函数，禁止堆成一个大方法靠猜。
        // 协议见 GPT 审查：新增表 CREATE TABLE；新增列 ALTER ADD COLUMN（带默认值）；
        // 改列类型/删列必须建新表 + INSERT SELECT + 核对记录数 + 事务包裹。
        if (o < 2) migrateToV2(db);
        if (o < 3) migrateToV3(db);
        if (o < 4) migrateToV4(db);
        if (o < 5) migrateToV5(db);
        if (o < 6) migrateToV6(db);
        if (o < 7) migrateToV7(db);
        if (o < 8) migrateToV8(db);
        if (o < 9) migrateToV9(db);
        if (o < 10) migrateToV10(db);
        if (o < 11) migrateToV11(db);
        if (o < 12) migrateToV12(db);
        if (o < 13) migrateToV13(db);
        if (o < 14) migrateToV14(db);
        // 兜底：确保四张表都存在（老库若因异常缺表，这里补齐，不删任何数据）
        db.execSQL("CREATE TABLE IF NOT EXISTS word(w TEXT PRIMARY KEY, n INTEGER, ipa TEXT, pos TEXT, cn TEXT, ex TEXT, excn TEXT, syl TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS card(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL, reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS spell(w TEXT PRIMARY KEY, state INTEGER, due INTEGER, s REAL, d REAL, reps INTEGER, lapses INTEGER, last INTEGER, ivl REAL)");
        // 拼写表故意不做历史补建。
        // GPT 裁决：「旧卡全部迁移为 recog，不能自动复制出 spell 卡，否则会凭空产生拼写学习历史。」
        //           「只在实际开始拼写训练时初始化相应学习状态。」
        // 因此老库里已学过的词不会自动获得拼写行：只有 finishNew() 新授通过时才建。
        // 代价是升级前学过的词永远不进拼写线——宁可少覆盖，也不伪造历史。
        db.execSQL("CREATE TABLE IF NOT EXISTS log(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, track TEXT, ts INTEGER, rating INTEGER, ms INTEGER, prev_ivl REAL, new_ivl REAL, prev_s REAL, new_s REAL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS screen(id INTEGER PRIMARY KEY AUTOINCREMENT, w TEXT, ts INTEGER, step INTEGER, input TEXT, ok INTEGER, ms INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_card_due ON card(state, due)");
        db.execSQL(Ddl.GUARD);
        // word 表若为空（旧库没灌词）则补灌，不覆盖已有
        loadWordsIfEmpty(db);
    }

    /** V3：新增账本三表（SCHEMA v1）——review_event / session / sentence_event。只加表。 */
    private void migrateToV3(SQLiteDatabase db) {
        db.execSQL(Ddl.EVENT);
        db.execSQL(Ddl.SESSION);
        db.execSQL(Ddl.SENTENCE);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_sid ON review_event(sid)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_w ON review_event(w)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sentence_sid ON sentence_event(sid)");
    }

    /** V4：word 表新增 syl（音节切点，pyphen 预生成）。
     *  加列用 ALTER ADD COLUMN；随后重灌词表才能填上 syl。
     *  重灌只动 word 表，card/log/screen 用 w 字符串关联，学习进度不受影响。 */
    private void migrateToV4(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE word ADD COLUMN syl TEXT");
        } catch (Exception ignored) {
            // 列已存在（重复升级）时忽略
        }
        db.execSQL("DELETE FROM word");
        loadWords(db);
    }

    /** V5：log 表新增 track（recognition / spelling）。
     *  GPT 裁决：「log 必须记录 track；已有历史默认映射到 recog，但不得伪造历史拼写成绩。」
     *  历史行一律标 recognition —— 这是默认映射，不是编造拼写成绩。 */
    private void migrateToV5(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE log ADD COLUMN track TEXT");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("UPDATE log SET track='recognition' WHERE track IS NULL");
        } catch (Exception ignored) {
        }
    }

    /** V6：新增 retrieval_guard（同日封口）。
     *  GPT 复裁 Q2 原文：「选乙：独立持久化封口记录。不修改 FSRS 计算结果。」
     *  主键 (w, local_date) —— 一天一个词只封一次，**跨通道**（认义/拼写在同一天互斥），
     *  这比按 track 分开封更严：同一词当天已经在拼写线被正式提取过，认义线当晚就不再考它。
     *  只建表，不动 card/spell/log 任何既有数据。 */
    private void migrateToV6(SQLiteDatabase db) {
        db.execSQL(Ddl.GUARD);
    }

    /** V7：账本补两件 GPT 复裁要求的东西。
     *  ① review_event.eid 唯一索引（Q7）：「每次正式作答生成稳定 event_id，同一事件重试写入
     *     不得重复计费、不得重复更新 FSRS、不得重复写日志」。只要求表内唯一，不做跨表触发器。
     *  ② review_event.hinted 列（Q3）：区分「首次无提示独立作答」与「看提示后作答」。
     *     没有这一列，7 天实测保持率的分母就必然被提示污染。 */
    private void migrateToV7(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE review_event ADD COLUMN hinted INTEGER NOT NULL DEFAULT 0");
        } catch (Exception ignored) {
            // 列已存在（重复升级 / 新库已由 CREATE 带上）
        }
        try {
            db.execSQL(Ddl.EID_INDEX);
        } catch (Exception e) {
            // GPT 复裁：「唯一索引是保证评分事件幂等性的数据库约束。如果建立失败后 App 继续运行，
            // 后续可能发生重复计分……必须终止该次升级，不允许以数据库约束缺失的状态正常进入学习。」
            // 抛出去 → SQLiteOpenHelper 让整次 onUpgrade 回滚，App 下次打开会重试迁移，而不是带病运行。
            Log.e("AURORA", "eid unique index failed, aborting upgrade", e);
            throw new IllegalStateException(
                    "review_event.eid 唯一索引建立失败（历史库可能有重复 eid），拒绝在无幂等约束的库上继续运行", e);
        }
    }

    // ================= 事务（GPT 复裁：评分/事件/封口必须同一事务） =================
    /** 开事务。失败返回 false（调用方按「没开事务」继续单步执行，不假装原子）。 */
    public boolean begin() {
        try {
            getWritableDatabase().beginTransaction();
            return true;
        } catch (Exception e) {
            Log.e("AURORA", "begin failed: " + e.getMessage());
            return false;
        }
    }

    /** 结束事务。ok=false 一律回滚——宁可让这次评分不算，也不能留下「卡片变了但没有账本/封口」的半截状态。 */
    public void end(boolean opened, boolean ok) {
        if (!opened) return;
        SQLiteDatabase d2 = getWritableDatabase();
        try {
            if (ok) d2.setTransactionSuccessful();
        } finally {
            d2.endTransaction();
        }
    }

    /** V8：认义复习改成「先作答再揭示答案」后，必须把**首次独立作答**本身存进账本。
     *  GPT 本轮要求：「记录首次独立答案，提交后不可修改」「独立答案与自评分别记录，
     *  禁止矛盾评分」「无法确定答案是否正确的事件标记 unverified，不得自动算正确」。
     *  verdict: 1=首次独立答对 / 0=答错 / 2=无法判定（空答、无法自动判）/ -1=历史行（无作答）。
     *  answer: 首次提交的原始输入，原样保留，供事后审计；不参与判分。 */
    private void migrateToV8(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE review_event ADD COLUMN verdict INTEGER NOT NULL DEFAULT -1");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("ALTER TABLE review_event ADD COLUMN answer TEXT");
        } catch (Exception ignored) {
        }
    }

    /** V9：多义词「命中任一义项即 PASS」的过渡方案要求留下义项命中记录。
     *  GPT 裁决（2026-11-07）：「保留义项命中记录。记录标准化的义项文本、词表版本、
     *  首次回答，不必现在引入独立义项 FSRS」「不能据此宣布全部义项掌握」。
     *  所以 matched_sense 存的是**命中的那个义项原文**（标准化后），不是整个 cn 字段。 */
    private void migrateToV9(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE review_event ADD COLUMN matched_sense TEXT");
        } catch (Exception ignored) {
        }
    }

    /** V10：事件类型显式化（GPT「申诉事件存储复裁」：同一张表可以存不同事件，
     *  但不同事件绝不能共享错误的统计和调度语义）。
     *  历史行按既有语义回填：只有带 1–4 评分的行才算正式评分，不能无条件标记。 */
    /** V13：句型「待审核」标记。GPT：连续两次 UNVERIFIED 不代表用户需要 14 天后再学，
     *  而是系统判不了。所以不改 due，只把该句型暂停正式测验，等人工确认后恢复。 */
    /** V14：区分「句型正式练习」与「场景表达替换练习」（GPT：collocation:<id> 是练习来源，
     *  不是错误类型，不能混进语法错误统计，也不能混进句型掌握率）。 */
    private void migrateToV14(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE pattern_event ADD COLUMN kind TEXT NOT NULL DEFAULT 'formal'");
        Log.i("AURORA", "db migrated to 14");
    }

    private void migrateToV13(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE pattern_state ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 0");
    }

    /** V12：句型连续待核实计数。GPT 指出 UNVERIFIED 不能无限次日循环，
     *  连续两次后应进人工审核队列，所以需要持久化 streak。 */
    private void migrateToV12(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE pattern_state ADD COLUMN unverified_streak INTEGER NOT NULL DEFAULT 0");
    }

    /** V11：句型线两表。只新增表，不动任何既有表——单词模块的数据可靠性优先于新功能。 */
    private void migrateToV11(SQLiteDatabase db) {
        db.execSQL(Ddl.PATTERN_STATE);
        db.execSQL(Ddl.PATTERN_EVENT);
        db.execSQL(Ddl.PATTERN_EID);
    }

    private void migrateToV10(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE review_event ADD COLUMN event_type TEXT");
        } catch (Exception ignored) {
        }
        try {
            // GPT 边界一：只按 rating 会误标「verdict=2 但 rating 有值」的历史行，
            // 所以同时要求 verdict 不是 UNVERIFIED；verdict 缺失（-1）的历史行保守归为 appeal，
            // 宁可少算一次正式评分，也不能把无法确定语义的行塞进参数拟合。
            db.execSQL("UPDATE review_event SET event_type = CASE"
                    + " WHEN rating>=1 AND rating<=4 AND verdict IN (0,1) THEN '" + EV_FSRS + "'"
                    + " ELSE '" + EV_APPEAL + "' END WHERE event_type IS NULL");
        } catch (Exception e) {
            android.util.Log.e("AURORA", "event_type backfill failed", e);
        }
    }

    /** 账本表 DDL。review_event / session / sentence_event 字段与 github.com/Chris142690/aurora-data
     *  的 SCHEMA.md v1 严格一致；retrieval_guard 是本地调度表，不上云。 */
    static final class Ddl {
        static final String GUARD = "CREATE TABLE IF NOT EXISTS retrieval_guard("
                + "w TEXT NOT NULL, local_date TEXT NOT NULL, track TEXT NOT NULL,"
                + "ts INTEGER, event_id TEXT, PRIMARY KEY(w, local_date))";
        static final String EID_INDEX = "CREATE UNIQUE INDEX IF NOT EXISTS idx_review_eid ON review_event(eid)";
        static final String EVENT = "CREATE TABLE IF NOT EXISTS review_event("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, sid TEXT, w TEXT, track TEXT,"
                + "rating INTEGER, ts INTEGER, response_ms INTEGER, elapsed_days REAL, r REAL,"
                + "s_before REAL, s_after REAL, d_before REAL, d_after REAL,"
                + "ivl_before REAL, ivl_after REAL, state_before INTEGER, state_after INTEGER,"
                + "is_new INTEGER, algo TEXT, dr REAL, hinted INTEGER NOT NULL DEFAULT 0,"
                + " verdict INTEGER NOT NULL DEFAULT -1, answer TEXT, matched_sense TEXT,"
                + " event_type TEXT)";
        static final String SESSION = "CREATE TABLE IF NOT EXISTS session("
                + "sid TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER,"
                + "duration_ms INTEGER, active_ms INTEGER, track TEXT, cap INTEGER,"
                + "actions_used INTEGER, words_new INTEGER, words_reviewed INTEGER,"
                + "synced INTEGER NOT NULL DEFAULT 0)";
        static final String SENTENCE = "CREATE TABLE IF NOT EXISTS sentence_event("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, sid TEXT, pid TEXT, ts INTEGER,"
                + "prompt_type TEXT, prompt_text TEXT, answer_text TEXT,"
                + "first_try INTEGER, hint_level INTEGER, attempts INTEGER, error_tags TEXT,"
                + "semantic INTEGER, grammar INTEGER, transfer_level INTEGER, transfer_success INTEGER,"
                + "latency_ms INTEGER, last_exposure_at INTEGER, delay_hours REAL, delayed_success INTEGER)";
        // 句型线（GPT「三项复裁」授权的最小闭环）：
        // 独立表、轻量间隔调度、不接 FSRS。教学内容和练习事件分开存——
        // 改例句不能破坏历史学习证据，所以 state 只存调度状态，event 只存发生过的作答。
        static final String PATTERN_STATE = "CREATE TABLE IF NOT EXISTS pattern_state("
                + "pid TEXT PRIMARY KEY, stage INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL,"
                + "last_attempt_at INTEGER, first_try_correct INTEGER NOT NULL DEFAULT -1,"
                + "hint_level INTEGER NOT NULL DEFAULT 0, error_tags TEXT, transfer_success INTEGER,"
                + "attempts INTEGER NOT NULL DEFAULT 0, content_version TEXT,"
                + " unverified_streak INTEGER NOT NULL DEFAULT 0,"
                + " needs_review INTEGER NOT NULL DEFAULT 0)";
        static final String PATTERN_EVENT = "CREATE TABLE IF NOT EXISTS pattern_event("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, eid TEXT, pid TEXT, ts INTEGER,"
                + "answer TEXT, verdict INTEGER, hint_used INTEGER, elapsed_hours REAL,"
                + "error_tags TEXT, missing TEXT, stage_before INTEGER, stage_after INTEGER,"
                + "due_before INTEGER, due_after INTEGER, content_version TEXT,"
                + "kind TEXT NOT NULL DEFAULT 'formal')";
        static final String PATTERN_EID = "CREATE UNIQUE INDEX IF NOT EXISTS idx_pattern_eid ON pattern_event(eid)";
    }

    /** V2：新增 screen 表（懒筛双通道记录）。只加表，不碰 card/log。 */
    private void migrateToV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS screen(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "w TEXT, ts INTEGER, step INTEGER, input TEXT, ok INTEGER, ms INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_screen_w ON screen(w)");
    }

    private void loadWordsIfEmpty(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM word", null);
        int n0 = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        if (n0 > 0) return;
        try {
            loadWords(db);
        } catch (Exception e) {
            // P1-12：导入失败不能让 App 在 onCreate 里崩掉，也不能静默空着词库。
            // 标记落盘（word 表为空 + words_load_failed=true），首页据此显示可重试提示。
            android.util.Log.e("aurora", "loadWordsIfEmpty: " + e);
            try { ctx.getSharedPreferences("aurora", 0).edit().putBoolean("words_load_failed", true).commit(); }
            catch (Throwable ignored) { }
        }
    }

    /** A12：词表应有条数。assets 被截断/打包漏了文件时，绝不能把半份词库当成完整词库提交。 */
    private static final int EXPECTED_WORDS = 1208;

    private void loadWords(SQLiteDatabase db) {
        int inserted = 0;
        try {
            InputStream in = ctx.getAssets().open("words.json");
            byte[] buf = new byte[in.available()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            in.close();
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            db.beginTransaction();
            try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("w", o.optString("w"));
                v.put("n", o.optInt("n", i + 1));
                v.put("ipa", o.optString("ipa"));
                v.put("pos", o.optString("pos"));
                v.put("cn", o.optString("cn"));
                v.put("ex", o.optString("ex"));
                v.put("excn", o.optString("excn"));
                v.put("syl", o.optString("syl"));
                if (db.insertWithOnConflict("word", null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1) inserted++;
            }
            if (inserted < EXPECTED_WORDS) {
                // 少一条都不提交：宁可词库为空让上层报错，也不要让用户在一个残缺词表上学到一半
                throw new IllegalStateException("words.json only " + inserted + " < " + EXPECTED_WORDS);
            }
            // P1-12：inserted 只数「插入语句执行了几次」，重复词条会被 CONFLICT_REPLACE 覆盖后
            // 仍然计数。必须再核对表里的实际行数与数组长度、以及词条唯一性。
            if (arr.length() != EXPECTED_WORDS) {
                throw new IllegalStateException("words.json array=" + arr.length() + " != " + EXPECTED_WORDS);
            }
            Cursor chk = db.rawQuery("SELECT COUNT(*) FROM word", null);
            int rows = chk.moveToFirst() ? chk.getInt(0) : 0;
            chk.close();
            Cursor dis = db.rawQuery("SELECT COUNT(DISTINCT w) FROM word", null);
            int uniq = dis.moveToFirst() ? dis.getInt(0) : 0;
            dis.close();
            if (rows != EXPECTED_WORDS || uniq != EXPECTED_WORDS) {
                throw new IllegalStateException("word rows=" + rows + " uniq=" + uniq + " expected=" + EXPECTED_WORDS);
            }
            db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            // P1-12：原来只 Log 不上抛，词库空着 App 照常启动，用户以为能用其实一张词都没有。
            // 这里把失败标记落盘并向调用方抛出，下次启动会重试导入。
            android.util.Log.e("aurora", "loadWords failed: " + e + " inserted=" + inserted);
            try {
                ctx.getSharedPreferences("aurora", 0).edit().putBoolean("words_load_failed", true).commit();
            } catch (Throwable ignored) { }
            throw new IllegalStateException("loadWords failed: " + e, e);
        }
        ctx.getSharedPreferences("aurora", 0).edit().putBoolean("words_load_failed", false).commit();
    }

    /** 待复习卡（已进入学习状态的）。localDate 非空时在 SQL 层就排除今天已封口的词——
     *  GPT 复裁纠正：「remaining + sealed.size() 不是可靠分页，应在 SQL 层排除封口再分页」。 */
    public List<Card> due(int limit, String localDate) {
        List<Card> out = new ArrayList<>();
        String sql = "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM card WHERE state IN (1,2,3) AND due<=?";
        String[] args;
        if (localDate == null) {
            args = new String[]{String.valueOf(System.currentTimeMillis()), String.valueOf(limit)};
        } else {
            sql += " AND w NOT IN (SELECT w FROM retrieval_guard WHERE local_date=?)";
            args = new String[]{String.valueOf(System.currentTimeMillis()), localDate, String.valueOf(limit)};
        }
        Cursor c = getReadableDatabase().rawQuery(sql + " ORDER BY due ASC LIMIT ?", args);
        while (c.moveToNext()) out.add(Card.from(c));
        c.close();
        return out;
    }

    public List<Card> due(int limit) { return due(limit, null); }

    // ================= 拼写线（第二套独立 FSRS） =================
    /** 拼写线到期的卡。state 语义与认义线一致（1=待首评 2=正式复习）。 */
    public List<Card> spellDue(int limit, String localDate) {
        List<Card> out = new ArrayList<>();
        String sql = "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM spell WHERE state IN (1,2) AND due<=?";
        String[] args;
        if (localDate == null) {
            args = new String[]{String.valueOf(System.currentTimeMillis()), String.valueOf(limit)};
        } else {
            sql += " AND w NOT IN (SELECT w FROM retrieval_guard WHERE local_date=?)";
            args = new String[]{String.valueOf(System.currentTimeMillis()), localDate, String.valueOf(limit)};
        }
        Cursor c = getReadableDatabase().rawQuery(sql + " ORDER BY due ASC LIMIT ?", args);
        while (c.moveToNext()) out.add(Card.from(c));
        c.close();
        return out;
    }

    public List<Card> spellDue(int limit) { return spellDue(limit, null); }

    public int spellDueCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM spell WHERE state IN (1,2) AND due<=?",
                new String[]{String.valueOf(System.currentTimeMillis())});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ================= 句型线（独立表 / 轻量调度，不进 FSRS） =================

    public static class PatState {
        public String pid;
        public int stage;
        public long due;
        public long last;
        public int firstTryCorrect;   // -1 未知 / 0 否 / 1 是
        public int hintLevel;
        public String errorTags;
        public int attempts;
        public String contentVersion;
        public int unverifiedStreak;
        public boolean needsReview;
    }

    public PatState patternState(String pid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pid,stage,due,last_attempt_at,first_try_correct,hint_level,error_tags,attempts,content_version,unverified_streak,needs_review"
                        + " FROM pattern_state WHERE pid=?", new String[]{pid});
        PatState p = null;
        if (c.moveToNext()) {
            p = new PatState();
            p.pid = c.getString(0);
            p.stage = c.getInt(1);
            p.due = c.getLong(2);
            p.last = c.getLong(3);
            p.firstTryCorrect = c.getInt(4);
            p.hintLevel = c.getInt(5);
            p.errorTags = c.getString(6);
            p.attempts = c.getInt(7);
            p.contentVersion = c.getString(8);
            p.unverifiedStreak = c.getInt(9);
            p.needsReview = c.getInt(10) == 1;
        }
        c.close();
        return p;
    }

    /** 到期句型（按 due 升序）。*/
    public List<PatState> patternDue(int limit, long now) {
        List<PatState> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pid,stage,due,last_attempt_at,first_try_correct,hint_level,error_tags,attempts,content_version,unverified_streak,needs_review"
                        + " FROM pattern_state WHERE due<=? AND needs_review=0 ORDER BY due ASC LIMIT ?",
                new String[]{String.valueOf(now), String.valueOf(limit)});
        while (c.moveToNext()) {
            PatState p = new PatState();
            p.pid = c.getString(0);
            p.stage = c.getInt(1);
            p.due = c.getLong(2);
            p.last = c.getLong(3);
            p.firstTryCorrect = c.getInt(4);
            p.hintLevel = c.getInt(5);
            p.errorTags = c.getString(6);
            p.attempts = c.getInt(7);
            p.contentVersion = c.getString(8);
            p.unverifiedStreak = c.getInt(9);
            p.needsReview = c.getInt(10) == 1;
            out.add(p);
        }
        c.close();
        return out;
    }

    public int patternDueCount(long now) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM pattern_state WHERE due<=? AND needs_review=0", new String[]{String.valueOf(now)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public Set<String> patternSeenIds() {
        Set<String> out = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT pid FROM pattern_state", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /** 待确认（needs_review=1）的句型，最近作答的排前面。
     *  GPT 第 15 轮裁决：连续两次判不了之后必须有一条用户可达的恢复路径，
     *  否则这句内容永久不可达 —— 这是 P0 缺陷，不是统计口径问题。 */
    public List<PatState> patternPending() {
        List<PatState> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pid,stage,due,last_attempt_at,first_try_correct,hint_level,error_tags,attempts,content_version,unverified_streak,needs_review"
                        + " FROM pattern_state WHERE needs_review=1 ORDER BY last_attempt_at DESC", null);
        while (c.moveToNext()) {
            PatState p = new PatState();
            p.pid = c.getString(0);
            p.stage = c.getInt(1);
            p.due = c.getLong(2);
            p.last = c.getLong(3);
            p.firstTryCorrect = c.getInt(4);
            p.hintLevel = c.getInt(5);
            p.errorTags = c.getString(6);
            p.attempts = c.getInt(7);
            p.contentVersion = c.getString(8);
            p.unverifiedStreak = c.getInt(9);
            p.needsReview = c.getInt(10) == 1;
            out.add(p);
        }
        c.close();
        return out;
    }

    public int patternPendingCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM pattern_state WHERE needs_review=1", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 该句型最近一次「正式」作答的原始输入 —— 待确认页要原样展示用户当时写了什么。
     *  只认 kind='formal'：场景表达与审核动作都不是正式作答，不能混进来。 */
    public String lastPatternAnswer(String pid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(answer,'') FROM pattern_event WHERE pid=? AND kind='formal'"
                        + " ORDER BY ts DESC, id DESC LIMIT 1", new String[]{pid});
        String s = c.moveToNext() ? c.getString(0) : "";
        c.close();
        return s;
    }

    /** 待确认句型的用户动作。
     *
     *  resume=true  「重新练习」：清 needs_review、清 unverified_streak、due=now、**stage 不变**。
     *                不调用 FSRS、不调用 Pattern.next —— 用户认为自己对，不等于系统验证过正确。
     *  resume=false 「暂时搁置」：状态一个字段都不动，只留审计事件，用户以后仍可从列表恢复。
     *
     *  两种动作都写一条 kind='review_clear' 的独立事件（verdict=-1，不参与正式评分与掌握率），
     *  且状态更新与事件写入必须在同一事务里：否则会出现「清掉了待审核但没有审计记录」的半截状态。
     */
    public boolean reviewPattern(String pid, boolean resume, long now, String eid, String answer) {
        if (pid == null || pid.length() == 0 || eid == null || eid.length() == 0) return false;
        PatState st = patternState(pid);
        if (st == null) return false;
        boolean tx = begin();
        boolean ok = true;
        try {
            if (resume) {
                ContentValues v = new ContentValues();
                v.put("needs_review", 0);
                v.put("unverified_streak", 0);
                v.put("due", now);
                ok = getWritableDatabase().update("pattern_state", v, "pid=?", new String[]{pid}) == 1;
                if (!ok) Log.e("AURORA", "reviewPattern resume update failed pid=" + pid);
            }
            if (ok) {
                ok = recordPatternEvent(eid, pid, now, answer, -1, false, 0.0, null, null,
                        st.stage, st.stage, st.due, resume ? now : st.due, st.contentVersion, "review_clear");
                if (!ok) Log.e("AURORA", "reviewPattern event failed pid=" + pid);
            }
        } catch (Exception e) {
            Log.e("AURORA", "reviewPattern exception pid=" + pid, e);
            ok = false;
        }
        end(tx, ok);
        return ok;
    }

    /** 写句型调度状态。调用方必须先跑完不变量检查（stage>=0，due>0）。 */
    public boolean savePatternState(String pid, int stage, long due, long last, int firstTryCorrect,
                                    int hintLevel, String errorTags, int attempts, String cv,
                                    int unverifiedStreak, boolean needsReview) {
        if (pid == null || pid.length() == 0 || stage < 0 || due <= 0) return false;
        ContentValues v = new ContentValues();
        v.put("pid", pid);
        v.put("stage", stage);
        v.put("due", due);
        v.put("last_attempt_at", last);
        v.put("first_try_correct", firstTryCorrect);
        v.put("hint_level", hintLevel);
        v.put("error_tags", errorTags);
        v.put("attempts", attempts);
        v.put("content_version", cv);
        v.put("unverified_streak", unverifiedStreak);
        v.put("needs_review", needsReview ? 1 : 0);
        long r = getWritableDatabase().insertWithOnConflict("pattern_state", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
        return r != -1;
    }

    /** 句型练习事件：只追加，不覆盖。eid 冲突返回 false（幂等）。 */
    public boolean recordPatternEvent(String eid, String pid, long ts, String answer, int verdict,
                                      boolean hintUsed, double elapsedHours, String errorTags,
                                      String missing, int stageBefore, int stageAfter,
                                      long dueBefore, long dueAfter, String cv) {
        return recordPatternEvent(eid, pid, ts, answer, verdict, hintUsed, elapsedHours, errorTags,
                missing, stageBefore, stageAfter, dueBefore, dueAfter, cv, "formal");
    }

    public boolean recordPatternEvent(String eid, String pid, long ts, String answer, int verdict,
                                      boolean hintUsed, double elapsedHours, String errorTags,
                                      String missing, int stageBefore, int stageAfter,
                                      long dueBefore, long dueAfter, String cv, String kind) {
        ContentValues v = new ContentValues();
        v.put("eid", eid);
        v.put("pid", pid);
        v.put("ts", ts);
        v.put("answer", answer);
        v.put("verdict", verdict);
        v.put("hint_used", hintUsed ? 1 : 0);
        v.put("elapsed_hours", elapsedHours);
        v.put("error_tags", errorTags);
        v.put("missing", missing);
        v.put("stage_before", stageBefore);
        v.put("stage_after", stageAfter);
        v.put("due_before", dueBefore);
        v.put("due_after", dueAfter);
        v.put("content_version", cv);
        v.put("kind", kind);
        long r = getWritableDatabase().insertWithOnConflict("pattern_event", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
        return r != -1;
    }

    public Card spellCard(String w) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM spell WHERE w=?", new String[]{w});
        Card k = c.moveToNext() ? Card.from(c) : null;
        c.close();
        return k;
    }

    /** 拼写线的写入。规则与 saveCard 一致：先过不变量，再写，写失败返回 false。 */
    public boolean saveSpell(Card k) {
        String bad = invariantViolation(k);
        if (bad != null) {
            android.util.Log.e("AURORA", "SPELL INVARIANT word=" + (k == null ? "?" : k.w) + " : " + bad);
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("w", k.w);
        v.put("state", k.state);
        v.put("due", k.due);
        v.put("s", k.s);
        v.put("d", k.d);
        v.put("reps", k.reps);
        v.put("lapses", k.lapses);
        v.put("last", k.last);
        v.put("ivl", k.ivl);
        try {
            long row = getWritableDatabase().insertWithOnConflict("spell", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            return row != -1;
        } catch (Exception ex) {
            android.util.Log.e("AURORA", "SPELL WRITE EXCEPTION word=" + k.w + " : " + ex);
            return false;
        }
    }

    /** 这个词是否属于拼写线（前 400 高频）。用户要求：400 个高频词要求会拼，其余认得就行。 */
    public boolean isSpellWord(String w) {
        Cursor c = getReadableDatabase().rawQuery("SELECT n FROM word WHERE w=?", new String[]{w});
        boolean yes = false;
        if (c.moveToNext()) {
            int n = c.getInt(0);
            yes = (n > 0 && n <= SPELL_LIMIT);
        }
        c.close();
        return yes;
    }

    /** 拼写线词量上限（前 N 个高频词）。 */
    public static final int SPELL_LIMIT = 400;

    /** 进入拼写线的词数（用于首页显示「会拼 N 个」）。 */
    public int spellStarted() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM spell WHERE reps>=1", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 拼写已稳固的词数（s>=7，口径同 countStable）。 */
    public int spellStable() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM spell WHERE state>=2 AND s>=7", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int dueCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM card WHERE state IN (1,2,3) AND due<=?",
                new String[]{String.valueOf(System.currentTimeMillis())});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 首页「今日待复习」必须等于 startReview() 真正会排进队列的量，否则用户点了按钮发现是空的。
     *  所以要扣掉两类：①今天已经封过口的词（任一通道）②今天刚完成新授、当天不该再测的词（Q6）。 */
    public int dueCount(String localDate, long dayStart) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM card WHERE state IN (1,2,3) AND due<=?"
                        + " AND w NOT IN (SELECT w FROM retrieval_guard WHERE local_date=?)"
                        + " AND NOT (state=1 AND reps=0 AND last>=?)",
                new String[]{String.valueOf(System.currentTimeMillis()), localDate,
                        String.valueOf(dayStart)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int spellDueCount(String localDate, long dayStart) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM spell WHERE state IN (1,2) AND due<=?"
                        + " AND w NOT IN (SELECT w FROM retrieval_guard WHERE local_date=?)"
                        + " AND NOT (state=1 AND reps=0 AND last>=?)",
                new String[]{String.valueOf(System.currentTimeMillis()), localDate,
                        String.valueOf(dayStart)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** state=3：懒筛通过、等次日确认的临时卡（尚未初始化 FSRS） */
    public List<Card> pending() {
        List<Card> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM card WHERE state=3 ORDER BY due ASC", null);
        while (c.moveToNext()) out.add(Card.from(c));
        c.close();
        return out;
    }

    /** 累计开始学习的词数（reps>=1）。
     *  GPT 裁决的正向反馈口径：答错只影响调度参数，不会把它清零。
     *  用户要看得到「我一共碰过多少词」，这是单调递增的，不会被一次失败抹掉。 */
    public int countStarted() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM card WHERE reps>=1", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 已记住的词（稳定度 ≥ 7 天）。
     *  口径依据：FSRS 的 s 就是「记忆稳定度（天）」，s=7 意味着 7 天后仍能回忆的概率是 R=0.9。
     *  所以这个数正好对应 GPT 要的「7 天延迟无提示正确率」的近似——不需要等真实历史数据，
     *  也不会因为某次答错而倒退（答错只会让 s 变小，但已经达到 7 天的词不会凭空消失）。 */
    public int countStable() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM card WHERE state>=1 AND s>=7", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ================= 同日封口（GPT 复裁 Q2 方案乙） =================
    /** 封口：把一个词在某个学习日标记为「已正式提取」。
     *  主键 (w, local_date) 天然幂等——同一天第二次调用写不进去，返回 false。
     *  这与 FSRS 无关：due / s / d 一个都不改，只拦调度层的重复出题。 */
    public boolean sealRetrieval(String w, String localDate, String track, long ts, String eid) {
        ContentValues v = new ContentValues();
        v.put("w", w);
        v.put("local_date", localDate);
        v.put("track", track);
        v.put("ts", ts);
        if (eid != null) v.put("event_id", eid);
        try {
            long r = getWritableDatabase().insertWithOnConflict(
                    "retrieval_guard", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            return r != -1;
        } catch (Exception e) {
            Log.e("AURORA", "seal failed w=" + w + " " + e.getMessage());
            return false;
        }
    }

    /** 拼写「输入失误」申诉：只记一次待核实事件，不写任何评分。
     *  GPT 裁决：「不必为此再新建一张表，可以放入统一学习事件账本」——这里用本地动作账本 screen，
     *  step=-2 是申诉哨兵值（-1 未用，0..2 是筛查步）。同词同日只接受一次，重启后仍生效，
     *  所以它不会退化成「无限免费重试」。 */
    public boolean claimSpell(String w) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM screen WHERE w=? AND step=-2 AND ts>=?",
                new String[]{w, String.valueOf(dayStart)});
        int used = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        if (used > 0) return false;
        ContentValues v = new ContentValues();
        v.put("w", w);
        v.put("ts", System.currentTimeMillis());
        v.put("step", -2);
        v.put("input", "");
        v.put("ok", 0);
        v.put("ms", 0);
        try {
            return getWritableDatabase().insert("screen", null, v) != -1;
        } catch (Exception e) {
            Log.e("AURORA", "claim failed w=" + w + " " + e.getMessage());
            return false;
        }
    }

    /** 今天已经封过的词集合。队列入口据此过滤，跨通道生效。 */
    public java.util.HashSet<String> sealedToday(String localDate) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT w FROM retrieval_guard WHERE local_date=?", new String[]{localDate});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    public int sealedCount(String localDate) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM retrieval_guard WHERE local_date=?", new String[]{localDate});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 「预计已掌握 N 词」：按不同词去重（GPT 裁决：双通道同一单词只能去重计数）。 */
    public int countStableDistinct() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM (SELECT w FROM card WHERE state>=2 AND s>=7 AND reps>=1"
                        + " UNION SELECT w FROM spell WHERE state>=2 AND s>=7 AND reps>=1)", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 7 天实测保持率（GPT 复裁 Q3 口径）：
     *  分母=真正发生的、间隔≥7 天的**首次无提示独立作答**；分子=其中独立答对（rating>=2）。
     *  看提示后作答（hinted=1）不进分母——否则「先看答案再选」会把保持率刷高。
     *  返回 4 元组：{认义有效数, 认义答对数, 拼写有效数, 拼写答对数}，两条通道分开算，不混。 */
    public int[] retention7() {
        int[] out = new int[]{0, 0, 0, 0};
        Cursor c = getReadableDatabase().rawQuery(
                // 分子分母都按**首次独立作答的客观判定**算，不按自评——否则「答错但自评记得」会污染保持率。
                "SELECT track, COUNT(*), SUM(CASE WHEN verdict=1 THEN 1 ELSE 0 END)"
                        + " FROM review_event WHERE elapsed_days>=7 AND hinted=0"
                        + " AND verdict IN (0,1) AND event_type='" + EV_FSRS + "' GROUP BY track", null);
        while (c.moveToNext()) {
            String tr = c.getString(0);
            int n = c.getInt(1);
            int ok = c.getInt(2);
            if ("spelling".equals(tr)) { out[2] = n; out[3] = ok; }
            else { out[0] += n; out[1] += ok; }
        }
        c.close();
        return out;
    }

    public int pendingCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM card WHERE state=3", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 取还没学过的词（按词频序） */
    /** P1-12：词库是否可用（导入成功且条数达标）。首页据此决定是否允许开始学习。 */
    public boolean wordsReady() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM word", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n >= EXPECTED_WORDS;
    }

    /** P1-11：待重新学习（state=4）的卡数。这些卡不占新词预算，必须能进新授队列。 */
    public int relearnCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM card WHERE state=4", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public List<String> fresh(int limit) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                // state=4 的词重新回到新授队列（重新学习），state=1/2/3 不算
                "SELECT w FROM word WHERE w NOT IN (SELECT w FROM card WHERE state<>4) ORDER BY n ASC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    public Word word(String w) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT w,ipa,pos,cn,ex,excn,syl FROM word WHERE w=?", new String[]{w});
        Word r = null;
        if (c.moveToFirst()) {
            r = new Word();
            r.w = c.getString(0);
            r.ipa = c.getString(1);
            r.pos = c.getString(2);
            r.cn = c.getString(3);
            r.ex = c.getString(4);
            r.excn = c.getString(5);
            r.syl = c.getString(6);
        }
        c.close();
        return r;
    }

    public Card card(String w) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM card WHERE w=?", new String[]{w});
        Card r = null;
        if (c.moveToFirst()) r = Card.from(c);
        c.close();
        return r;
    }

    /** 状态不变量（审查要求）：违反即拒写并记 ErrorCat。
     *  合法组合只有三种：
     *    state=1, reps=0, s=0, d=0   新词待首测（学没学过）
     *    state=3, reps=0, s=0, d=0   懒筛通过待次日确认
     *    state=2, reps>=1, s>0, d>0  已初始化 FSRS 的正常卡
     *  返回 null 表示通过，否则返回违反项描述。 */
    public static String invariantViolation(Card k) {
        if (k == null) return "card=null";
        // 规则本体在 CardRules（纯 JDK），便于 JVM 单测直接验证真实规则而不是复制品
        return CardRules.violation(k.state, k.reps, k.lapses, k.s, k.d, k.ivl, k.due, k.last);
    }

    public boolean saveCard(Card k) {
        String bad = invariantViolation(k);
        if (bad != null) {
            android.util.Log.e("AURORA", "INVARIANT VIOLATION word=" + (k == null ? "?" : k.w) + " : " + bad);
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("w", k.w);
        v.put("state", k.state);
        v.put("due", k.due);
        v.put("s", k.s);
        v.put("d", k.d);
        v.put("reps", k.reps);
        v.put("lapses", k.lapses);
        v.put("last", k.last);
        v.put("ivl", k.ivl);
        // 审查 FIX-01：insertWithOnConflict 失败返回 -1，原来忽略返回值 -> saveCard 永远"成功"，
        // 上层的 if (!db.saveCard(k)) 保护形同虚设。
        try {
            long row = getWritableDatabase().insertWithOnConflict("card", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            if (row == -1) {
                android.util.Log.e("AURORA", "SQLITE WRITE RETURNED -1 word=" + k.w);
                return false;
            }
            return true;
        } catch (Exception ex) {
            android.util.Log.e("AURORA", "SQLITE WRITE EXCEPTION word=" + k.w + " : " + ex);
            return false;
        }
    }

    /** A13：日志写入必须回传结果。原来 void + 裸 insert，写失败时既没异常也没痕迹，
     *  调用方以为「卡片已存+日志已记」，复盘时才发现 revlog 断档。 */
    /** track: "recognition" 或 "spelling"。GPT 裁决要求 log 必须记录 track。 */
    public boolean log(String w, String track, int rating, long ms, double prevIvl, double newIvl, double prevS, double newS) {
        try {
        ContentValues v = new ContentValues();
        v.put("w", w);
        v.put("track", track);
        v.put("ts", System.currentTimeMillis());
        v.put("rating", rating);
        v.put("ms", ms);
        v.put("prev_ivl", prevIvl);
        v.put("new_ivl", newIvl);
        v.put("prev_s", prevS);
        v.put("new_s", newS);
        long row = getWritableDatabase().insert("log", null, v);
        if (row == -1) { android.util.Log.e("AURORA", "log insert failed w=" + w); return false; }
        return true;
        } catch (Exception e) {
            android.util.Log.e("AURORA", "log insert threw w=" + w + " " + e);
            return false;
        }
    }

    /** 记录懒筛的原始输入与判定依据（不写入 S/D，不进复习日志） */
    /** 记录一次筛查尝试。返回 false 表示没写进库——调用方必须停下并补偿已扣额度，
     *  不能把「记录失败」当成「判错」继续推进。 */
    public boolean saveScreen(String w, int step, String input, int ok, long ms) {
        try {
            ContentValues v = new ContentValues();
            v.put("w", w);
            v.put("ts", System.currentTimeMillis());
            v.put("step", step);
            v.put("input", input);
            v.put("ok", ok);
            v.put("ms", ms);
            long row = getWritableDatabase().insert("screen", null, v);
            if (row == -1) {
                Log.e("AURORA", "saveScreen insert failed w=" + w + " step=" + step);
                return false;
            }
            return true;
        } catch (Exception ex) {
            Log.e("AURORA", "saveScreen exception w=" + w, ex);
            return false;
        }
    }

    public int studiedToday() {
        long t0 = startOfDay();
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM log WHERE ts>=?", new String[]{String.valueOf(t0)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public static long startOfDay() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 导出全部日志与卡片为 JSON 文本 */
    private String esc(String s) {
        if (s == null) return "";
        String a = s.replace("\\", "\\\\");
        a = a.replace("\"", "\\\"");
        a = a.replace("\n", "\\n");
        // P2-03：JSON 必须转义 CR / TAB / 退格 / 换页 以及所有 C0 控制字符。
        // 原来只处理 \\ \" \n 三种，导出文件在严格 JSON 解析器上会直接非法。
        a = a.replace("\r", "\\r");
        a = a.replace("\t", "\\t");
        a = a.replace("\b", "\\b");
        a = a.replace("\f", "\\f");
        StringBuilder sb = new StringBuilder(a.length());
        for (int i = 0; i < a.length(); i++) {
            char ch = a.charAt(i);
            if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.toString();
    }

    /** 当日已完成卡数（与 App 的 SharedPreferences 同口径，便于外部核对 35 张封顶是否生效） */
    public int dayDoneStat() {
        // A14：账本键是 done_yyyyMMdd（MainActivity.tryRecordStudyAction 写的），
        // 原实现读的是早已废弃的 day/done，导出里的今日完成数永远是 0。
        android.content.SharedPreferences sp = ctx.getSharedPreferences("aurora", 0);
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        return sp.getInt("done_" + today, 0);
    }

    // ================== 账本（SCHEMA v1） ==================

    /** 会话开始。sid 由调用方生成（UUIDv4），同一 sid 重复调用不覆盖。 */
    public void beginSession(String sid, String track, int cap, long now) {
        try {
            ContentValues v = new ContentValues();
            v.put("sid", sid);
            v.put("started_at", now);
            v.put("ended_at", 0L);
            v.put("duration_ms", 0L);
            v.put("active_ms", 0L);
            v.put("track", track);
            v.put("cap", cap);
            v.put("actions_used", 0);
            v.put("words_new", 0);
            v.put("words_reviewed", 0);
            v.put("synced", 0);
            getWritableDatabase().insertWithOnConflict("session", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) {
            Log.e("AURORA", "beginSession failed", e);
        }
    }

    /** 会话结束写汇总。失败返回 false。 */
    public boolean endSession(String sid, long now, int actionsUsed, int wordsNew,
                              int wordsReviewed, long activeMs) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT started_at FROM session WHERE sid=?", new String[]{sid});
            long started = 0L;
            if (c.moveToFirst()) started = c.getLong(0);
            c.close();
            ContentValues v = new ContentValues();
            v.put("ended_at", now);
            v.put("duration_ms", started > 0 ? now - started : 0L);
            v.put("active_ms", activeMs);
            v.put("actions_used", actionsUsed);
            v.put("words_new", wordsNew);
            v.put("words_reviewed", wordsReviewed);
            return db.update("session", v, "sid=?", new String[]{sid}) > 0;
        } catch (Exception e) {
            Log.e("AURORA", "endSession failed", e);
            return false;
        }
    }

    /** 一次评分一条。这是 FSRS 调参的唯一原始数据，缺一条就没法回放。 */
    public boolean recordReview(String sid, String w, String track, int rating,
                                long ts, long responseMs, double elapsedDays, double r,
                                double sB, double sA, double dB, double dA,
                                double iB, double iA, int stB, int stA, boolean isNew) {
        return recordReview(sid, w, track, rating, ts, responseMs, elapsedDays, r,
                sB, sA, dB, dA, iB, iA, stB, stA, isNew, 0);
    }

    /** hinted=1 表示这一次作答之前用户已经看过答案/提示——它不能进保持率分母。 */
    public boolean recordReview(String sid, String w, String track, int rating,
                                long ts, long responseMs, double elapsedDays, double r,
                                double sB, double sA, double dB, double dA,
                                double iB, double iA, int stB, int stA, boolean isNew,
                                int hinted) {
        return recordReview(sid, w, track, rating, ts, responseMs, elapsedDays, r,
                sB, sA, dB, dA, iB, iA, stB, stA, isNew, hinted, -1, null);
    }

    /** verdict: 1=首次独立答对 / 0=答错 / 2=无法判定 / -1=无作答；answer=首次原始输入。 */
    public boolean recordReview(String sid, String w, String track, int rating,
                                long ts, long responseMs, double elapsedDays, double r,
                                double sB, double sA, double dB, double dA,
                                double iB, double iA, int stB, int stA, boolean isNew,
                                int hinted, int verdict, String answer) {
        return recordReview(sid, w, track, rating, ts, responseMs, elapsedDays, r,
                sB, sA, dB, dA, iB, iA, stB, stA, isNew, hinted, verdict, answer, null);
    }

    public boolean recordReview(String sid, String w, String track, int rating,
                                long ts, long responseMs, double elapsedDays, double r,
                                double sB, double sA, double dB, double dA,
                                double iB, double iA, int stB, int stA, boolean isNew,
                                int hinted, int verdict, String answer, String matchedSense) {
        try {
            ContentValues v = new ContentValues();
            v.put("eid", java.util.UUID.randomUUID().toString());
            v.put("sid", sid);
            v.put("w", w);
            v.put("track", track);
            v.put("rating", rating);
            v.put("ts", ts);
            v.put("response_ms", responseMs);
            v.put("elapsed_days", elapsedDays);
            v.put("r", r);
            v.put("s_before", sB);
            v.put("s_after", sA);
            v.put("d_before", dB);
            v.put("d_after", dA);
            v.put("ivl_before", iB);
            v.put("ivl_after", iA);
            v.put("state_before", stB);
            v.put("state_after", stA);
            v.put("is_new", isNew ? 1 : 0);
            v.put("hinted", hinted);
            v.put("verdict", verdict);
            if (answer != null) v.put("answer", answer);
            if (matchedSense != null) v.put("matched_sense", matchedSense);
            // GPT 边界二：event_type 决定「是不是一次正式 FSRS 评分」，
            // hinted 决定「这次评分能不能进无提示保持率」——两件正交的事，不能混。
            // 「不会，直接看答案」时 hinted=1，但评分有效，仍必须是 fsrs_review。
            v.put("event_type", (rating >= 1 && rating <= 4 && verdict != 2) ? EV_FSRS : EV_APPEAL);
            v.put("algo", Fsrs.ALGORITHM_VERSION);
            v.put("dr", Fsrs.DESIRED_RETENTION);
            return getWritableDatabase().insert("review_event", null, v) != -1;
        } catch (Exception e) {
            Log.e("AURORA", "recordReview failed w=" + w, e);
            return false;
        }
    }

    /** 一次句型作答一条。 */
    public boolean recordSentence(String sid, String pid, long ts, String promptType,
                                  String promptText, String answer, boolean firstTry,
                                  int hintLevel, int attempts, String tags,
                                  int semantic, int grammar, int transferLevel,
                                  boolean transferOk, long latencyMs,
                                  long lastExposure, double delayHours, boolean delayedOk) {
        try {
            ContentValues v = new ContentValues();
            v.put("eid", java.util.UUID.randomUUID().toString());
            v.put("sid", sid);
            v.put("pid", pid);
            v.put("ts", ts);
            v.put("prompt_type", promptType);
            v.put("prompt_text", promptText);
            v.put("answer_text", answer);
            v.put("first_try", firstTry ? 1 : 0);
            v.put("hint_level", hintLevel);
            v.put("attempts", attempts);
            v.put("error_tags", tags == null ? "" : tags);
            v.put("semantic", semantic);
            v.put("grammar", grammar);
            v.put("transfer_level", transferLevel);
            v.put("transfer_success", transferOk ? 1 : 0);
            v.put("latency_ms", latencyMs);
            v.put("last_exposure_at", lastExposure);
            v.put("delay_hours", delayHours);
            v.put("delayed_success", delayedOk ? 1 : 0);
            return getWritableDatabase().insert("sentence_event", null, v) != -1;
        } catch (Exception e) {
            Log.e("AURORA", "recordSentence failed pid=" + pid, e);
            return false;
        }
    }

    /** 按 SCHEMA v1 把一个会话导成 JSON。 */
    public JSONObject sessionJson(String sid) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject o = new JSONObject();
        Cursor c = db.rawQuery("SELECT started_at,ended_at,duration_ms,active_ms,track,cap,"
                + "actions_used,words_new,words_reviewed FROM session WHERE sid=?",
                new String[]{sid});
        if (!c.moveToFirst()) { c.close(); return null; }
        long started = c.getLong(0);
        o.put("schema_version", 1);
        o.put("session_id", sid);
        o.put("app_version", "0.3.0");
        o.put("device", "android");
        o.put("started_at", started);
        o.put("ended_at", c.getLong(1));
        o.put("duration_ms", c.getLong(2));
        o.put("active_ms", c.getLong(3));
        o.put("track", c.getString(4));
        o.put("cap", c.getInt(5));
        o.put("actions_used", c.getInt(6));
        o.put("words_new", c.getInt(7));
        o.put("words_reviewed", c.getInt(8));
        c.close();

        JSONArray ev = new JSONArray();
        Cursor ce = db.rawQuery("SELECT eid,ts,w,track,rating,response_ms,elapsed_days,r,"
                + "s_before,s_after,d_before,d_after,ivl_before,ivl_after,"
                + "state_before,state_after,is_new,algo,dr FROM review_event "
                + "WHERE sid=? ORDER BY id", new String[]{sid});
        while (ce.moveToNext()) {
            JSONObject e = new JSONObject();
            e.put("event_id", ce.getString(0));
            e.put("session_id", sid);
            e.put("at", ce.getLong(1));
            e.put("word_id", ce.getString(2));
            e.put("track", ce.getString(3));
            e.put("rating", ce.getInt(4));
            e.put("response_ms", ce.getLong(5));
            e.put("elapsed_days", ce.getDouble(6));
            e.put("retrievability", ce.getDouble(7));
            e.put("stability_before", ce.getDouble(8));
            e.put("stability_after", ce.getDouble(9));
            e.put("difficulty_before", ce.getDouble(10));
            e.put("difficulty_after", ce.getDouble(11));
            e.put("interval_before", ce.getDouble(12));
            e.put("interval_after", ce.getDouble(13));
            e.put("state_before", ce.getInt(14));
            e.put("state_after", ce.getInt(15));
            e.put("is_new_card", ce.getInt(16) == 1);
            e.put("algorithm_version", ce.getString(17));
            e.put("desired_retention", ce.getDouble(18));
            ev.put(e);
        }
        ce.close();
        o.put("review_events", ev);

        JSONArray cs = new JSONArray();
        Cursor cc = db.rawQuery("SELECT w,state,due,s,d,reps,lapses,last,ivl FROM card", null);
        while (cc.moveToNext()) {
            JSONObject k = new JSONObject();
            k.put("word_id", cc.getString(0));
            k.put("track", "recognition");
            k.put("state", cc.getInt(1));
            k.put("due", cc.getLong(2));
            k.put("stability", cc.getDouble(3));
            k.put("difficulty", cc.getDouble(4));
            k.put("interval", cc.getDouble(8));
            k.put("reps", cc.getInt(5));
            k.put("lapses", cc.getInt(6));
            k.put("last", cc.getLong(7));
            cs.put(k);
        }
        cc.close();
        o.put("card_states", cs);

        JSONArray se = new JSONArray();
        Cursor cse = db.rawQuery("SELECT eid,ts,pid,prompt_type,prompt_text,answer_text,"
                + "first_try,hint_level,attempts,error_tags,semantic,grammar,transfer_level,"
                + "transfer_success,latency_ms,last_exposure_at,delay_hours,delayed_success "
                + "FROM sentence_event WHERE sid=? ORDER BY id", new String[]{sid});
        while (cse.moveToNext()) {
            JSONObject e = new JSONObject();
            e.put("event_id", cse.getString(0));
            e.put("session_id", sid);
            e.put("at", cse.getLong(1));
            e.put("pattern_id", cse.getString(2));
            e.put("prompt_type", cse.getString(3));
            e.put("prompt_text", cse.getString(4));
            e.put("answer_text", cse.getString(5));
            e.put("first_try_correct", cse.getInt(6) == 1);
            e.put("hint_level", cse.getInt(7));
            e.put("attempt_count", cse.getInt(8));
            e.put("error_tags", cse.getString(9));
            e.put("semantic_score", cse.getInt(10));
            e.put("grammar_score", cse.getInt(11));
            e.put("transfer_level", cse.getInt(12));
            e.put("transfer_success", cse.getInt(13) == 1);
            e.put("latency_ms", cse.getLong(14));
            e.put("last_exposure_at", cse.getLong(15));
            e.put("delay_hours", cse.getDouble(16));
            e.put("delayed_success", cse.getInt(17) == 1);
            se.put(e);
        }
        cse.close();
        o.put("sentence_events", se);
        return o;
    }

    /** 还没同步的会话 id。 */
    public List<String> pendingSids() {
        List<String> out = new ArrayList<String>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT sid FROM session WHERE synced=0 AND ended_at>0 ORDER BY started_at", null);
            while (c.moveToNext()) out.add(c.getString(0));
            c.close();
        } catch (Exception e) {
            Log.e("AURORA", "pendingSids failed", e);
        }
        return out;
    }

    /** 标记已同步（幂等：重复标记不影响）。 */
    public boolean markSynced(String sid) {
        try {
            ContentValues v = new ContentValues();
            v.put("synced", 1);
            return getWritableDatabase().update("session", v, "sid=?", new String[]{sid}) > 0;
        } catch (Exception e) {
            Log.e("AURORA", "markSynced failed", e);
            return false;
        }
    }

    public String exportJson() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("{\"exported_at\":").append(System.currentTimeMillis());
            sb.append(",\"logs\":[");
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT w,ts,rating,ms,prev_ivl,new_ivl,prev_s,new_s FROM log ORDER BY ts", null);
            boolean first = true;
            while (c.moveToNext()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"w\":\"").append(esc(c.getString(0))).append("\",\"ts\":").append(c.getLong(1))
                        .append(",\"rating\":").append(c.getInt(2)).append(",\"ms\":").append(c.getLong(3))
                        .append(",\"prev_ivl\":").append(c.getDouble(4)).append(",\"new_ivl\":").append(c.getDouble(5))
                        .append(",\"prev_s\":").append(c.getDouble(6)).append(",\"new_s\":").append(c.getDouble(7)).append('}');
            }
            c.close();
            sb.append("],\"cards\":[");
            Cursor k = getReadableDatabase().rawQuery(
                    "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM card", null);
            first = true;
            while (k.moveToNext()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"w\":\"").append(esc(k.getString(0))).append("\",\"state\":").append(k.getInt(1))
                        .append(",\"due\":").append(k.getLong(2)).append(",\"s\":").append(k.getDouble(3))
                        .append(",\"d\":").append(k.getDouble(4)).append(",\"reps\":").append(k.getInt(5))
                        .append(",\"lapses\":").append(k.getInt(6)).append(",\"last\":").append(k.getLong(7))
                        .append(",\"ivl\":").append(k.getDouble(8)).append('}');
            }
            k.close();
            // 筛查原始答案与判定（GPT 审查 P1：原来不导出，无法回查误判）
            sb.append("],\"screens\":[");
            Cursor sc = getReadableDatabase().rawQuery(
                    "SELECT w,ts,step,input,ok,ms FROM screen ORDER BY ts", null);
            first = true;
            while (sc.moveToNext()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"w\":\"").append(esc(sc.getString(0))).append("\",\"ts\":").append(sc.getLong(1))
                        .append(",\"step\":").append(sc.getInt(2))
                        .append(",\"input\":\"").append(esc(sc.getString(3))).append("\"")
                        .append(",\"ok\":").append(sc.getInt(4)).append(",\"ms\":").append(sc.getLong(5)).append('}');
            }
            sc.close();
            // 拼写线独立表：不导出就无法核对两条 FSRS 是否各走各的
            sb.append("],\"spells\":[");
            Cursor sp = getReadableDatabase().rawQuery(
                    "SELECT w,state,due,s,d,reps,lapses,last,ivl FROM spell", null);
            first = true;
            while (sp.moveToNext()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"w\":\"").append(esc(sp.getString(0))).append("\",\"state\":").append(sp.getInt(1))
                        .append(",\"due\":").append(sp.getLong(2)).append(",\"s\":").append(sp.getDouble(3))
                        .append(",\"d\":").append(sp.getDouble(4)).append(",\"reps\":").append(sp.getInt(5))
                        .append(",\"lapses\":").append(sp.getInt(6)).append(",\"last\":").append(sp.getLong(7))
                        .append(",\"ivl\":").append(sp.getDouble(8)).append('}');
            }
            sp.close();
            // 多日统计：原来只有一个「今天」快照，跨天回看就没了。
            // 从 log 表按本地日期聚合（log 表时区跟随设备），不依赖 SharedPreferences。
            sb.append("],\"days\":[");
            Cursor dy = getReadableDatabase().rawQuery(
                    "SELECT ts, rating, ms FROM log ORDER BY ts", null);
            java.util.TreeMap<String, int[]> agg = new java.util.TreeMap<String, int[]>();
            java.util.TreeMap<String, long[]> aggMs = new java.util.TreeMap<String, long[]>();
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            while (dy.moveToNext()) {
                String key = f.format(new java.util.Date(dy.getLong(0)));
                int[] a = agg.get(key);
                if (a == null) { a = new int[4]; agg.put(key, a); aggMs.put(key, new long[1]); }
                a[0]++;                                  // 总次数
                int rt = dy.getInt(1);
                if (rt == 1) a[1]++;                     // Again
                else if (rt == 2) a[2]++;                // Hard
                else a[3]++;                             // Good
                aggMs.get(key)[0] += dy.getLong(2);
            }
            dy.close();
            first = true;
            for (java.util.Map.Entry<String, int[]> e : agg.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                int[] a = e.getValue();
                long tot = aggMs.get(e.getKey())[0];
                sb.append("{\"day\":\"").append(e.getKey()).append("\",\"n\":").append(a[0])
                        .append(",\"again\":").append(a[1]).append(",\"hard\":").append(a[2])
                        .append(",\"good\":").append(a[3])
                        .append(",\"avg_ms\":").append(a[0] > 0 ? tot / a[0] : 0).append('}');
            }
            sb.append("],\"day\":{\"key\":\"").append(new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date())).append("\",\"done\":").append(dayDoneStat()).append('}');
            sb.append("}");
        } catch (Exception e) {
            return "{\"error\":\"" + e + "\"}";
        }
        return sb.toString();
    }

    public static class Word {
        public String w, ipa, pos, cn, ex, excn, syl;
    }

    public static class Card {
        public String w;
        public int state, reps, lapses;
        public long due, last;
        public double s, d, ivl;

        static Card from(Cursor c) {
            Card k = new Card();
            k.w = c.getString(c.getColumnIndex("w"));
            k.state = c.getInt(c.getColumnIndex("state"));
            k.due = c.getLong(c.getColumnIndex("due"));
            k.s = c.getDouble(c.getColumnIndex("s"));
            k.d = c.getDouble(c.getColumnIndex("d"));
            k.reps = c.getInt(c.getColumnIndex("reps"));
            k.lapses = c.getInt(c.getColumnIndex("lapses"));
            k.last = c.getLong(c.getColumnIndex("last"));
            k.ivl = c.getDouble(c.getColumnIndex("ivl"));
            return k;
        }
    }
}
