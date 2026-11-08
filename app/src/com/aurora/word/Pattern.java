package com.aurora.word;

/** 句型线的判定与轻量调度（纯 Java，可在 JVM 单测，不依赖 Android / 真机）。
 *
 *  GPT「句型线 V11 复裁」后的判定职责被明确为：
 *   「可靠地区分确定正确、确定错误、无法判定」，而不是尽可能多地给结论。
 *   宁可承认系统无法判断，也不能把用户正确表达判错。
 *
 *  因此这里：
 *   - 常见缩写在比对前展开为等价形式（I'm new. == I am new.）
 *   - 只有「规则能可靠识别」的结构错误才判 INCORRECT（I new. 缺 be 动词）
 *   - 其余非精确匹配一律 UNVERIFIED，**不再用「缺必备词」当语法错误的证据**
 *   - 空答案单独记 NO_RESPONSE：不算独立成功，也不算语义错误
 */
public final class Pattern {

    public static final int INCORRECT = 0, CORRECT = 1, UNVERIFIED = 2, NO_RESPONSE = 3;

    public static final class Verdict {
        public final int verdict;
        public final String matched;
        public final String missing;   // 保留字段：缺词信息仍记录，但不再据此判错
        /** 触发判错的具体规则（null 表示没有触发）。GPT：不能对所有 INCORRECT 只说泛泛的
         *  error_focus，必须说清这次实际命中了哪条规则、否则用户会把常见错误清单当成自己的错。 */
        public final String rule;
        public Verdict(int v, String m, String miss) { this(v, m, miss, null); }
        public Verdict(int v, String m, String miss, String r) {
            verdict = v; matched = m; missing = miss; rule = r;
        }
        public boolean correct() { return verdict == CORRECT; }
        public String toString() {
            return "verdict=" + verdict + " matched=" + matched + " missing=" + missing;
        }
    }

    /** 缩写展开表：把口语缩写改写成书面等价形式。顺序敏感，长的先替换。 */
    private static final String[][] EXPAND = {
            {"won't", "will not"}, {"can't", "cannot"}, {"shan't", "shall not"},
            {"n't", " not"},
            {"i'm", "i am"}, {"you're", "you are"}, {"we're", "we are"},
            {"they're", "they are"}, {"he's", "he is"}, {"she's", "she is"}, {"it's", "it is"},
            {"that's", "that is"}, {"there's", "there is"}, {"what's", "what is"},
            {"i'll", "i will"}, {"you'll", "you will"}, {"we'll", "we will"}, {"they'll", "they will"},
            {"i've", "i have"}, {"you've", "you have"}, {"we've", "we have"}, {"they've", "they have"},
            {"let's", "let us"}
    };

    /** 标准化：小写、弯引号拉直、去标点（保留撇号供展开）、压空格。 */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201c', ' ').replace('\u201d', ' ');
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\'') { b.append(c); continue; }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                b.append(Character.toLowerCase(c));
            } else {
                b.append(' ');
            }
        }
        String out = b.toString().trim();
        while (out.indexOf("  ") >= 0) out = out.replace("  ", " ");
        return out;
    }

    /** `'d` 只在后接这些词时才确定是 would（I'd like / I'd rather / I'd better...）。
     *  I'd finished 里的 'd 是 had —— 固定展开成 would 会误判，所以按上下文处理。 */
    private static final String[] WOULD_NEXT = {
            "like", "love", "rather", "better", "prefer", "say", "go", "be", "have", "want"
    };

    /** 标准化 + 缩写展开。比对必须用这个，不能用 norm。 */
    public static String canon(String s) {
        String t = norm(s);
        if (t.length() == 0) return "";
        for (int i = 0; i < EXPAND.length; i++) {
            t = t.replace(EXPAND[i][0], EXPAND[i][1]);
        }
        String[] parts = t.split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String w = parts[i];
            if (w.endsWith("'d")) {
                String nxt = (i + 1 < parts.length) ? parts[i + 1] : "";
                boolean would = false;
                for (int k = 0; k < WOULD_NEXT.length; k++) if (WOULD_NEXT[k].equals(nxt)) would = true;
                if (would) b.append(w.substring(0, w.length() - 2)).append(" would");
                else b.append(w);          // 判不了是 would 还是 had → 原样保留，交给 hasAmbiguity
            } else {
                b.append(w);
            }
            if (i < parts.length - 1) b.append(' ');
        }
        String out = b.toString();
        while (out.indexOf("  ") >= 0) out = out.replace("  ", " ");
        return out.trim();
    }

    /** 还有无法确定含义的缩写（'d）。这种情况不能判对也不能判错。 */
    public static boolean hasAmbiguity(String canonText) {
        if (canonText == null || canonText.length() == 0) return false;
        String[] parts = canonText.split(" ");
        for (int i = 0; i < parts.length; i++) if (parts[i].endsWith("'d")) return true;
        return false;
    }

    /** 规则能可靠识别的结构错误：主语代词后直接跟状态词、没有 be 动词。 */
    private static final String[] BE_MISSING = {
            "i new", "i tired", "i busy", "i happy", "i sad", "i ready", "i hungry",
            "i late", "i free", "i fine", "i okay", "i ok",
            "she busy", "he busy", "we busy", "they busy",
            "i very", "i so"
    };

    public static Verdict judge(String answer, String[] refs, String[] requiredVocab) {
        String a = canon(answer);
        if (a.length() == 0) return new Verdict(NO_RESPONSE, null, null);
        if (refs != null) {
            for (int i = 0; i < refs.length; i++) {
                String r = canon(refs[i]);
                if (r.length() > 0 && r.equals(a)) return new Verdict(CORRECT, refs[i], null);
            }
        }
        // GPT V12 复裁：删掉「单个单词即判错」。Help! / Thanks. / Yes. 在对应情境里可能就是
        // 正确表达，系统没有证据判错。只有下面 BE_MISSING 这种规则能可靠识别的结构错误才判错。
        if (hasAmbiguity(a)) {
            // GPT：无法确定 I'd 是 would 还是 had 时，不要用固定替换规则误判。
            return new Verdict(UNVERIFIED, null, missingOf(a, requiredVocab));
        }
        for (int i = 0; i < BE_MISSING.length; i++) {
            String bad = BE_MISSING[i];
            if (a.equals(bad) || a.startsWith(bad + " ")) {
                return new Verdict(INCORRECT, null, null, "be_missing:" + bad);
            }
        }
        // 非精确匹配：可能完全正确（同义表达 / 多说了状语），也可能有语法问题。
        // 缺词只做记录，不作为判错依据（GPT 明确要求删除该规则）。
        return new Verdict(UNVERIFIED, null, missingOf(a, requiredVocab));
    }

    private static String missingOf(String canonAnswer, String[] requiredVocab) {
        if (requiredVocab == null || requiredVocab.length == 0) return null;
        StringBuilder miss = new StringBuilder();
        for (int i = 0; i < requiredVocab.length; i++) {
            String v = canon(requiredVocab[i]);
            if (v.length() == 0) continue;
            if (!canonAnswer.contains(v)) {
                if (miss.length() > 0) miss.append(",");
                miss.append(requiredVocab[i]);
            }
        }
        return miss.length() == 0 ? null : miss.toString();
    }

    public static final class Next {
        public final int stage;
        /** 0 表示「保持原 due 不变」（待审核时不再人为改调度时间）。 */
        public final int dueDays;
        public final int unverifiedStreak;
        /** true = 进待审核队列：暂停该句型的正式测验，等人工确认后恢复调度。 */
        public final boolean pendingReview;
        public Next(int s, int d, int streak) { this(s, d, streak, false); }
        public Next(int s, int d, int streak, boolean review) {
            stage = s; dueDays = d; unverifiedStreak = streak; pendingReview = review;
        }
        public String toString() {
            return "stage=" + stage + " dueDays=" + dueDays + " streak=" + unverifiedStreak
                    + (pendingReview ? " PENDING_REVIEW" : "");
        }
    }

    /** GPT 给的间隔规则 + 两条修正：
     *  - 首次独立成功 D+3；失败或看提示 D+1；之后 D+7、D+14、+7 封顶 30（启发式，不是验证过的最优间隔）。
     *  - UNVERIFIED 连续两次后进人工审核队列：不再次日循环、不推进阶段，due 保持原值（返回 dueDays=0）。
     *  - NO_RESPONSE（空答案）按未通过处理，可在次日重来，但不写成语义错误。
     */
    public static Next next(int stage, int verdict, boolean hintUsed, int unverifiedStreak) {
        return next(stage, verdict, hintUsed, unverifiedStreak, "pattern");
    }

    /** 功能词单元（kind=function，be/have/do）的间隔与普通句型不同（GPT 第 10 轮裁决）：
     *  必须「连续两次跨天独立成功」才算会用 —— D0 答对只推到 D+1，D+1 再独立答对才进 D+3，
     *  之后交回普通句型间隔。当天重复答题不能替代跨天复现。
     */
    public static Next next(int stage, int verdict, boolean hintUsed, int unverifiedStreak, String kind) {
        boolean func = "function".equals(kind);
        if (verdict == UNVERIFIED) {
            int streak = unverifiedStreak + 1;
            // 连续两次无法判定 —— 不是「用户要 14 天后再学」，而是「系统判不了」。
            // 因此不改 due、不推进阶段，只标记待审核，等人工确认后恢复。
            if (streak >= 2) return new Next(stage <= 0 ? 1 : stage, 0, streak, true);
            return new Next(stage <= 0 ? 1 : stage, 1, streak, false);
        }
        if (verdict == CORRECT && !hintUsed) {
            if (func) {
                // D0 首次独立正确 → 只是「先确认会用」，隔天必须再现一次
                if (stage <= 0) return new Next(1, 1, 0);
                // D+1 再独立正确 → 从这次成功起 3 天
                if (stage == 1) return new Next(2, 3, 0);
                // 之后不能跳级：D+3 → D+7 → D+14，逐级拉长（曾错成直接 D+14）
                if (stage == 2) return new Next(3, 7, 0);
                if (stage == 3) return new Next(4, 14, 0);
                int df = 14 + (stage - 3) * 7;
                if (df > 30) df = 30;
                return new Next(stage + 1, df, 0);
            }
            if (stage <= 0) return new Next(1, 3, 0);
            if (stage == 1) return new Next(2, 7, 0);
            if (stage == 2) return new Next(3, 14, 0);
            int d = 14 + (stage - 2) * 7;
            if (d > 30) d = 30;
            return new Next(stage + 1, d, 0);
        }
        return new Next(stage <= 0 ? 1 : stage, 1, 0);
    }

    public static Next next(int stage, int verdict, boolean hintUsed) {
        return next(stage, verdict, hintUsed, 0);
    }
}
