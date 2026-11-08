package com.aurora.word;

/** 卡片状态不变量规则：纯静态、不依赖 Android，便于 JVM 单测直接验证真实代码。
 *  审查要求：lapses 与 reps 无大小关系；NaN/Inf 必须拦住；state=2 必须 s/d/ivl 均为正。
 *  状态语义：1=首测待评（新授已完成）、2=正式复习、3=懒筛待次日确认、4=待重新学习（必须走完整新授）。 */
public class CardRules {

    public static String violation(int state, int reps, int lapses, double s, double d, double ivl,
                                   long due, long last) {
        if (reps < 0) return "reps<0 (" + reps + ")";
        if (lapses < 0) return "lapses<0 (" + lapses + ")";
        if (Double.isNaN(s) || Double.isInfinite(s)) return "invalid stability";
        if (Double.isNaN(d) || Double.isInfinite(d)) return "invalid difficulty";
        if (Double.isNaN(ivl) || Double.isInfinite(ivl)) return "invalid interval";
        if (state == 2) {
            if (reps < 1) return "state=2 but reps=0";
            // A11：只查 >0 不够——d 必须在 FSRS 的 [1,10] 定义域内，s 不能小到 0.01，
            // ivl 不能小于 1 天（interval() 的下界就是 1）。
            if (!(s >= 0.1)) return "state=2 but s<0.1 (" + s + ")";
            if (!(d >= 1.0 && d <= 10.0)) return "state=2 but d outside [1,10] (" + d + ")";
            if (!(ivl >= 1.0)) return "state=2 but ivl<1 (" + ivl + ")";
            if (ivl > 3650.0) return "state=2 but ivl>MAX_INTERVAL_DAYS (" + ivl + ")";
            // P1-4：state=2 必须有 last（真复习过）且 due>0（有排期）；
            // due 允许小于 now —— 逾期卡是正常的，逾期不等于数据损坏。
            if (last <= 0) return "state=2 but last=" + last;
            if (due <= 0) return "state=2 but due=" + due;
        } else if (state == 1 || state == 3 || state == 4) {
            if (reps != 0) return "state=" + state + " but reps=" + reps;
            if (s != 0 || d != 0) return "state=" + state + " but s/d nonzero";
            // A11：这三个状态都还没进过 FSRS，ivl 必须为 0；非 0 说明有字段没被重置
            if (ivl != 0) return "state=" + state + " but ivl nonzero (" + ivl + ")";
            // P1-4：1/3 是「学过但没通过确认」的中间态，必须留下 last 且 due 在其之后；
            // 4 是「必须重新学习」，last 必须清零（否则会被当成刚复习过），due 必须有值。
            if (state == 4) {
                if (last != 0) return "state=4 but last=" + last;
                if (due <= 0) return "state=4 but due=" + due;
            } else {
                if (last <= 0) return "state=" + state + " but last=" + last;
                if (due <= last) return "state=" + state + " but due<=last (" + due + "<=" + last + ")";
            }
        } else {
            return "illegal state=" + state;
        }
        return null;
    }
}
