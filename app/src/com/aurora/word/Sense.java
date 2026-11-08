package com.aurora.word;

import java.util.ArrayList;

/**
 * 中文义项判定（纯 Java，无 Android 依赖，可在 JVM 单测里跑）。
 *
 * 之所以单独抽出来：GPT 指出「测试不应该依赖设备是否插线」。判定规则属于业务逻辑，
 * 必须能在纯 Java 层验证；真机只负责验证 Android 集成、数据库迁移与真实交互。
 *
 * 三条原则（来自 GPT 的两轮裁决）：
 *  1) 只做「规范化后完全相等」，不做子串包含，否则「某物」能骗过「获得（某物）」。
 *  2) 命中任一正确义项即算正确，但只记录命中的义项，不得宣称掌握全部义项。
 *  3) 无法可靠判定时必须给 UNVERIFIED，不能为了自动判分把合理同义表达判成错误，
 *     因为错误的 FSRS 成绩比「未判定」危害更大。
 */
public final class Sense {

    public static final int INCORRECT = 0;
    public static final int CORRECT = 1;
    public static final int UNVERIFIED = 2;

    /** 判定结果：三态 + 命中的义项原文（可能多个，按词表顺序）+ 规范化后的答案 */
    public static final class Result {
        public final int verdict;
        public final String answer;      // normCn(raw)
        public final String sense;       // 首个命中义项原文；未命中为 null
        public final String[] matched;   // 全部命中义项（去重，按词表顺序）

        Result(int verdict, String answer, String[] matched) {
            this.verdict = verdict;
            this.answer = answer;
            this.matched = matched == null ? new String[0] : matched;
            this.sense = this.matched.length > 0 ? this.matched[0] : null;
        }

        public boolean correct() { return verdict == CORRECT; }
    }

    private Sense() {}

    /** 词表里义项的分隔符：中英文分号、逗号、顿号、斜杠、换行 */
    static final String SEP = "[；;，,、/\n]";

    /** 规范化：只保留汉字（含扩展 A 区与兼容区），去掉空格标点；含 ASCII 字母数字视为非法输入 */
    public static String normCn(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return "";
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 拆义项。括号段只作补充：主干（去掉括号内容）与整段都算有效，括号里的字不单独立项 */
    public static String[] splitSenses(String cn) {
        if (cn == null || cn.length() == 0) return new String[0];
        String[] parts = cn.split(SEP);
        ArrayList<String> out = new ArrayList<String>();
        for (String part : parts) {
            String t = part.trim();
            if (t.length() > 0) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /** 主干：去掉（…）/(…) 注释后的部分，用于「获得（某物）」这类条目 */
    static String stem(String part) {
        return part.replaceAll("[（(][^）)]*[）)]", "");
    }

    /**
     * 判定一次中文作答。
     *  - 空答 / 含 ASCII 字母数字 → UNVERIFIED（无法自动判，不算对也不算错）
     *  - 与任一条目的整段或主干完全相等 → CORRECT，并记录命中的义项
     *  - 与某条目部分重叠（答案包含该义项或该义项包含答案，长度≥2）→ UNVERIFIED
     *    纯词表匹配识别不了所有合理同义表达，这种情况留给人工/后续核验，不能判错
     *  - 其余 → INCORRECT
     */
    public static Result judge(String raw, String cn) {
        String a = normCn(raw);
        if (a.length() == 0) return new Result(UNVERIFIED, a, null);

        String[] senses = splitSenses(cn);
        ArrayList<String> hits = new ArrayList<String>();
        for (String part : senses) {
            String full = normCn(part);
            String st = normCn(stem(part));
            if ((full.length() > 0 && a.equals(full)) || (st.length() > 0 && a.equals(st))) {
                if (!hits.contains(part)) hits.add(part);
            }
        }
        if (hits.size() > 0) return new Result(CORRECT, a, hits.toArray(new String[0]));

        // 部分重叠：可能是词表未收录的同义表达，或用户写了更长的短语。无法可靠判定 → 待核实
        for (String part : senses) {
            String full = normCn(part);
            String st = normCn(stem(part));
            String[] cand = (st.length() >= 2) ? new String[]{st} : new String[]{full};
            for (String t : cand) {
                if (t.length() < 2) continue;
                if (a.contains(t) || t.contains(a)) return new Result(UNVERIFIED, a, null);
            }
        }
        return new Result(INCORRECT, a, null);
    }

    /** 兼容旧调用点：只要布尔 */
    public static boolean match(String raw, String cn) {
        return judge(raw, cn).verdict == CORRECT;
    }
}
