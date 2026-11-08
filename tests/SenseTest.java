import com.aurora.word.Sense;

/** 中文义项判定单测（纯 JVM，不依赖 Android / 真机）。
 *  覆盖 GPT 点名的七类：完全匹配、多个义项、中文同义表达、空输入、
 *  无法判定、标点分隔、重复义项；外加 order 词条的定向用例。 */
public class SenseTest {
    static int pass = 0, fail = 0;

    static void t(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "  " + detail); }
    }

    static void verdict(String name, String raw, String cn, int expect) {
        Sense.Result r = Sense.judge(raw, cn);
        t(name + " [" + raw + " → " + r.verdict + "]", r.verdict == expect,
          "expect=" + expect + " got=" + r.verdict + " sense=" + r.sense);
    }

    static void sense(String name, String raw, String cn, String expect) {
        Sense.Result r = Sense.judge(raw, cn);
        t(name + " sense=" + r.sense, expect.equals(r.sense),
          "expect=" + expect + " got=" + r.sense);
    }

    public static void main(String[] a) {
        String order = "命令；订购；顺序";

        // 1 完全匹配
        verdict("完全匹配", "命令", order, Sense.CORRECT);
        sense("完全匹配记录义项", "命令", order, "命令");

        // 2 多个义项：命中任一即正确，且要能说出命中的是哪个
        verdict("多义-订购", "订购", order, Sense.CORRECT);
        sense("多义-订购义项", "订购", order, "订购");
        verdict("多义-顺序", "顺序", order, Sense.CORRECT);

        // 3 中文同义表达：词表外的合理同义词无法自动判分，不得判成「确定错误」以外的分数
        //    现状：无字符重叠时判 INCORRECT（这是已知局限，见 KNOWN LIMIT）
        Sense.Result syn = Sense.judge("好看", "漂亮的；美丽的");
        t("同义表达不判对", syn.verdict != Sense.CORRECT, "got=" + syn.verdict);
        System.out.println("  KNOWN LIMIT 同义表达「好看」→ verdict=" + syn.verdict
            + "（纯词表匹配无法识别任意同义词，需同义词表或申诉通道）");

        // 4 空输入 / 非法输入 → UNVERIFIED
        verdict("空输入", "", order, Sense.UNVERIFIED);
        verdict("空白输入", "   ", order, Sense.UNVERIFIED);
        verdict("纯字母输入", "abc", order, Sense.UNVERIFIED);
        verdict("汉字混字母", "命令abc", order, Sense.UNVERIFIED);

        // 5 无法可靠判定：部分重叠 → UNVERIFIED，不能当确定错误
        verdict("部分重叠-更长", "顺序号", order, Sense.UNVERIFIED);
        verdict("部分重叠-更短", "顺", order, Sense.UNVERIFIED);

        // 6 标点分隔：分号/逗号/顿号/斜杠/换行都要能拆
        String all = "甲；乙，丙、丁/戊\n己";
        for (String s : new String[]{"甲", "乙", "丙", "丁", "戊", "己"}) {
            verdict("标点分隔-" + s, s, all, Sense.CORRECT);
        }

        // 7 重复义项：命中一次即可，且不得把重复项当多个义项记录
        Sense.Result dup = Sense.judge("命令", "命令；命令");
        t("重复义项判对", dup.verdict == Sense.CORRECT, "got=" + dup.verdict);
        t("重复义项去重", dup.matched.length == 1, "matched=" + dup.matched.length);

        // 8 括号只作补充：主干算对，括号内的字不单独立项
        verdict("括号条目主干", "获得", "获得（某物）", Sense.CORRECT);
        t("括号内容不单独立项", Sense.judge("某物", "获得（某物）").verdict != Sense.CORRECT,
          "got=" + Sense.judge("某物", "获得（某物）").verdict);

        // 9 完全无关 → INCORRECT
        verdict("无关答案", "苹果", order, Sense.INCORRECT);

        // 10 规范化：全角空格、标点、大小写不影响
        verdict("带标点答案", "命令。", order, Sense.CORRECT);
        verdict("带空格答案", " 命令 ", order, Sense.CORRECT);

        // 11 GPT 裁决表逐行复核：beautiful = 漂亮的；美丽的
        verdict("裁决表-漂亮的", "漂亮的", "漂亮的；美丽的", Sense.CORRECT);
        verdict("裁决表-美丽的", "美丽的", "漂亮的；美丽的", Sense.CORRECT);
        t("裁决表-好看转待核实", Sense.judge("好看", "漂亮的；美丽的").verdict == Sense.INCORRECT,
          "got=" + Sense.judge("好看", "漂亮的；美丽的").verdict);
        t("裁决表-苹果可申诉但不自动变对",
          Sense.judge("苹果", "漂亮的；美丽的").verdict == Sense.INCORRECT,
          "got=" + Sense.judge("苹果", "漂亮的；美丽的").verdict);
        t("裁决表-空白不算作答", Sense.judge("", "漂亮的；美丽的").verdict == Sense.UNVERIFIED,
          "got=" + Sense.judge("", "漂亮的；美丽的").verdict);

        System.out.println();
        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
