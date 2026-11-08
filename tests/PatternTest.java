import com.aurora.word.Pattern;

public class PatternTest {
    static int pass = 0, fail = 0;
    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }
    static String[] r(String... v) { return v; }
    static int v(String ans, String[] refs) { return Pattern.judge(ans, refs, null).verdict; }

    public static void main(String[] a) {
        // --- 标准化与缩写展开 ---
        ok("norm 去标点压空格", Pattern.norm(" I'd  LIKE a coffee, please. ").equals("i'd like a coffee please"));
        ok("canon 展开 I'm", Pattern.canon("I'm new.").equals("i am new"));
        ok("canon 展开 don't", Pattern.canon("I don't know.").equals("i do not know"));
        ok("canon 展开 I'd like", Pattern.canon("I'd like a coffee").equals("i would like a coffee"));

        // --- GPT 裁决表逐条验证 ---
        ok("缩写等价：I'm new. vs I am new. → CORRECT",
                v("I'm new.", r("I am new.")) == Pattern.CORRECT);
        ok("缩写等价：I don't know. vs I do not know. → CORRECT",
                v("I don't know.", r("I do not know.")) == Pattern.CORRECT);
        ok("多说了 here：I'm new here. → UNVERIFIED",
                v("I'm new here.", r("I am new.")) == Pattern.UNVERIFIED);
        ok("结构错：I new. → INCORRECT",
                v("I new.", r("I am new.")) == Pattern.INCORRECT);
        ok("更具体但正确：I am a new student. → UNVERIFIED",
                v("I am a new student.", r("I am new.")) == Pattern.UNVERIFIED);
        ok("同义表达：I'd like to buy a ticket. vs I want to buy a ticket. → UNVERIFIED",
                v("I'd like to buy a ticket.", r("I want to buy a ticket.")) == Pattern.UNVERIFIED);

        // --- 'd 的歧义（GPT：I'd 既可能是 I would 也可能是 I had） ---
        ok("I'd like 确定是 would → CORRECT",
                v("I'd like a coffee", r("I would like a coffee")) == Pattern.CORRECT);
        ok("I'd finished 判不了 would/had → UNVERIFIED",
                v("I'd finished it", r("I have finished it")) == Pattern.UNVERIFIED);
        ok("歧义不会掉进 INCORRECT 规则", v("I'd finished it", r("I finished it.")) != Pattern.INCORRECT);
        ok("hasAmbiguity 认得未展开的 'd", Pattern.hasAmbiguity(Pattern.canon("I'd finished it")));

        // --- 空答案单独记 NO_RESPONSE ---
        ok("空答案 → NO_RESPONSE", Pattern.judge("   ", r("I am new."), r("new")).verdict == Pattern.NO_RESPONSE);
        ok("空答案不等于答错", Pattern.judge(null, r("I am new."), r("new")).verdict != Pattern.INCORRECT);

        // --- 缺词不再判错 ---
        Pattern.Verdict miss = Pattern.judge("I need a receipt", r("I need the receipt."), r("receipt", "the"));
        ok("缺词只记录不改判", miss.verdict == Pattern.UNVERIFIED && miss.missing != null);

        // --- 单词答案不构成表达 ---
        ok("单个单词不再判错：new → UNVERIFIED", v("new", r("I am new.")) == Pattern.UNVERIFIED);
        ok("Help! 这种在情境里可能就是对的 → UNVERIFIED", v("Help!", r("Can you help me?")) == Pattern.UNVERIFIED);
        ok("Yes. → UNVERIFIED 而不是判错", v("Yes.", r("Can I sit here?")) == Pattern.UNVERIFIED);

        // --- 调度 ---
        ok("首次成功 → D+3 / stage1", Pattern.next(0, Pattern.CORRECT, false).dueDays == 3
                && Pattern.next(0, Pattern.CORRECT, false).stage == 1);
        ok("失败 → D+1 且不推进", Pattern.next(1, Pattern.INCORRECT, false).dueDays == 1
                && Pattern.next(1, Pattern.INCORRECT, false).stage == 1);
        ok("看提示答对 → 不算成功", Pattern.next(2, Pattern.CORRECT, true).dueDays == 1
                && Pattern.next(2, Pattern.CORRECT, true).stage == 2);
        ok("第二次成功 → D+7", Pattern.next(1, Pattern.CORRECT, false).dueDays == 7);
        ok("第三次成功 → D+14", Pattern.next(2, Pattern.CORRECT, false).dueDays == 14);
        ok("其后 +7 且封顶 30", Pattern.next(3, Pattern.CORRECT, false).dueDays == 21
                && Pattern.next(9, Pattern.CORRECT, false).dueDays == 30);

        // --- UNVERIFIED 循环保护（GPT 额外发现） ---
        ok("首次待核实 → 次日", Pattern.next(1, Pattern.UNVERIFIED, false, 0).dueDays == 1);
        ok("连续两次待核实 → 待审核、不改 due、不推进",
                Pattern.next(1, Pattern.UNVERIFIED, false, 1).pendingReview
                        && Pattern.next(1, Pattern.UNVERIFIED, false, 1).dueDays == 0
                        && Pattern.next(1, Pattern.UNVERIFIED, false, 1).stage == 1);
        ok("首次待核实不进待审核", !Pattern.next(1, Pattern.UNVERIFIED, false, 0).pendingReview);
        ok("成功一次后 streak 归零", Pattern.next(1, Pattern.CORRECT, false, 5).unverifiedStreak == 0);
        ok("NO_RESPONSE → 次日且不推进", Pattern.next(0, Pattern.NO_RESPONSE, false).dueDays == 1);

        // ---- 功能词单元（kind=function）：必须连续两次跨天独立成功（GPT 第 10 轮裁决） ----
        Pattern.Next f0 = Pattern.next(0, Pattern.CORRECT, false, 0, "function");
        ok("功能词 D0 独立正确 → stage=1", f0.stage == 1);
        ok("功能词 D0 独立正确 → 只推到 D+1，不直接进 3 天", f0.dueDays == 1);

        Pattern.Next f1 = Pattern.next(1, Pattern.CORRECT, false, 0, "function");
        ok("功能词 D+1 再独立正确 → stage=2", f1.stage == 2);
        ok("功能词第二次成功起算 3 天", f1.dueDays == 3);

        Pattern.Next f1bad = Pattern.next(1, Pattern.INCORRECT, false, 0, "function");
        ok("功能词 D+1 答错 → 次日再练", f1bad.dueDays == 1 && f1bad.stage == 1);

        Pattern.Next f1hint = Pattern.next(1, Pattern.CORRECT, true, 0, "function");
        ok("功能词 D+1 看提示才答对 → 次日再练，不推进", f1hint.dueDays == 1 && f1hint.stage == 1);

        Pattern.Next f1none = Pattern.next(1, Pattern.NO_RESPONSE, false, 0, "function");
        ok("功能词 D+1 未作答 → 次日再练", f1none.dueDays == 1 && f1none.stage == 1);

        Pattern.Next f1u = Pattern.next(1, Pattern.UNVERIFIED, false, 0, "function");
        ok("功能词 UNVERIFIED → 不推进阶段", f1u.stage == 1 && !f1u.pendingReview);

        Pattern.Next f1u2 = Pattern.next(1, Pattern.UNVERIFIED, false, 1, "function");
        ok("功能词连续两次 UNVERIFIED → 进待审核且不改 due", f1u2.pendingReview && f1u2.dueDays == 0);

        Pattern.Next f2 = Pattern.next(2, Pattern.CORRECT, false, 0, "function");
        ok("功能词 stage=2 不得跳级，应为 D+7", f2.dueDays == 7 && f2.stage == 3);
        Pattern.Next f3 = Pattern.next(3, Pattern.CORRECT, false, 0, "function");
        ok("功能词 stage=3 → D+14", f3.dueDays == 14 && f3.stage == 4);
        Pattern.Next f6 = Pattern.next(6, Pattern.CORRECT, false, 0, "function");
        ok("功能词封顶仍为 30 天", f6.dueDays == 30);

        // 普通句型不受影响
        Pattern.Next p0 = Pattern.next(0, Pattern.CORRECT, false, 0, "pattern");
        ok("普通句型首次正确仍是 D+3", p0.dueDays == 3 && p0.stage == 1);

        System.out.println("PatternTest: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
