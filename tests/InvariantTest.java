import com.aurora.word.CardRules;

/** 不变量规则单测：直接调用生产代码 CardRules，不复制规则。 */
public class InvariantTest {
    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("PASS " + name + (detail.isEmpty() ? "" : " " + detail)); }
        else { fail++; System.out.println("FAIL " + name + " " + detail); }
    }

    static void bad(String name, String got, String wantContains) {
        ok(name, got != null && got.contains(wantContains), "got=" + got);
    }

    static void good(String name, String got) {
        ok(name, got == null, "got=" + got);
    }

    public static void main(String[] a) {
        System.out.println("== 一、FIX-03 lapses 与 reps 无大小关系 ==");
        good("损坏卡重置：reps=0 但 lapses=3 -> 合法", CardRules.violation(1, 0, 3, 0, 0, 0, 2000L, 1000L));
        good("正式卡：reps=2 但 lapses=5（历史累计） -> 合法", CardRules.violation(2, 2, 5, 3.7, 5.1, 4, 2000L, 1000L));

        System.out.println("== 二、NaN / Infinite 必须拦住 ==");
        bad("s=NaN", CardRules.violation(2, 1, 0, Double.NaN, 5, 4, 2000L, 1000L), "invalid stability");
        bad("d=Inf", CardRules.violation(2, 1, 0, 3.7, Double.POSITIVE_INFINITY, 4, 2000L, 1000L), "invalid difficulty");
        bad("ivl=NaN", CardRules.violation(2, 1, 0, 3.7, 5, Double.NaN, 2000L, 1000L), "invalid interval");

        System.out.println("== 三、state=2 必须四项齐全且落在 FSRS 定义域内 ==");
        bad("state=2 reps=0", CardRules.violation(2, 0, 0, 3.7, 5, 4, 2000L, 1000L), "reps=0");
        bad("state=2 s=0", CardRules.violation(2, 1, 0, 0, 5, 4, 2000L, 1000L), "s<0.1");
        bad("state=2 d=0", CardRules.violation(2, 1, 0, 3.7, 0, 4, 2000L, 1000L), "outside [1,10]");
        bad("state=2 ivl=0", CardRules.violation(2, 1, 0, 3.7, 5, 0, 2000L, 1000L), "ivl<1");
        bad("state=2 s=0.05 (<0.1)", CardRules.violation(2, 1, 0, 0.05, 5, 4, 2000L, 1000L), "s<0.1");
        bad("state=2 d=12 (>10)", CardRules.violation(2, 1, 0, 3.7, 12, 4, 2000L, 1000L), "outside [1,10]");
        bad("state=2 ivl=5000 (>上界)", CardRules.violation(2, 1, 0, 3.7, 5, 5000, 2000L, 1000L), "MAX_INTERVAL");
        good("state=2 全正 -> 合法", CardRules.violation(2, 1, 0, 3.7145, 5.1618, 3.7145, 2000L, 1000L));

        System.out.println("== 四、state=1/3 必须是干净新卡 ==");
        good("state=1 全新", CardRules.violation(1, 0, 0, 0, 0, 0, 2000L, 1000L));
        good("state=3 待确认", CardRules.violation(3, 0, 0, 0, 0, 0, 2000L, 1000L));
        bad("state=1 但 reps=1", CardRules.violation(1, 1, 0, 0, 0, 0, 2000L, 1000L), "reps=1");
        bad("state=3 但 s!=0", CardRules.violation(3, 0, 0, 0.5, 0, 0, 2000L, 1000L), "s/d nonzero");
        good("state=4 待重学", CardRules.violation(4, 0, 0, 0, 0, 0, 2000L, 0L));
        bad("state=1 但 ivl!=0", CardRules.violation(1, 0, 0, 0, 0, 3, 2000L, 1000L), "ivl nonzero");
        bad("state=3 但 ivl!=0", CardRules.violation(3, 0, 0, 0, 0, 1, 2000L, 1000L), "ivl nonzero");
        bad("state=4 但 reps=1", CardRules.violation(4, 1, 0, 0, 0, 0, 2000L, 0L), "reps=1");
        bad("state=4 但 s!=0", CardRules.violation(4, 0, 0, 1.2, 0, 0, 2000L, 0L), "s/d nonzero");

        System.out.println("== 五、非法取值 ==");
        bad("state=9", CardRules.violation(9, 0, 0, 0, 0, 0, 2000L, 1000L), "illegal state");
        bad("reps=-1", CardRules.violation(1, -1, 0, 0, 0, 0, 2000L, 1000L), "reps<0");
        bad("lapses=-1", CardRules.violation(1, 0, -1, 0, 0, 0, 2000L, 1000L), "lapses<0");

        System.out.println("== 六、P1-4 due/last 语义 ==");
        // 这几条必须真的被新规则挡住，否则新加的校验是死代码（前面的检查先返回了）
        bad("state=1 但 last=0（没留学习痕迹）",
                CardRules.violation(1, 0, 0, 0, 0, 0, 2000L, 0L), "but last=0");
        bad("state=1 但 due<=last（排期早于学习时间）",
                CardRules.violation(1, 0, 0, 0, 0, 0, 900L, 1000L), "due<=last");
        bad("state=2 但 last=0",
                CardRules.violation(2, 1, 0, 3.7, 5, 4, 2000L, 0L), "but last=0");
        bad("state=2 但 due=0（没有排期）",
                CardRules.violation(2, 1, 0, 3.7, 5, 4, 0L, 1000L), "but due=0");
        bad("state=4 但 last!=0（会被当成刚复习过）",
                CardRules.violation(4, 0, 0, 0, 0, 0, 2000L, 1500L), "but last=1500");
        good("state=2 逾期合法（due < now 不属于损坏）",
                CardRules.violation(2, 1, 0, 3.7, 5, 4, 1L, 1000L));

        System.out.println();
        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
