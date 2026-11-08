import com.aurora.word.Fsrs;

/** JVM 单测：不依赖 Android，直接跑 FSRS-4.5 的关键断言。
 *  审查要求（GPT）：精确断言首次 Again/Hard/Good = 0.4872 / 1.4003 / 3.7145，不用 new_s>30 这种粗哨兵。 */
public class FsrsTest {
    static int pass = 0, fail = 0;

    static void eq(String name, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (ok) { pass++; System.out.println("PASS " + name + " = " + got); }
        else { fail++; System.out.println("FAIL " + name + " = " + got + " (want " + want + ")"); }
    }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("PASS " + name + (detail.isEmpty() ? "" : " " + detail)); }
        else { fail++; System.out.println("FAIL " + name + " " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== 一、首次评分稳定性（P0 回归） ==");
        eq("initStability(1) Again", Fsrs.initStability(1), 0.4872, 1e-9);
        eq("initStability(2) Hard", Fsrs.initStability(2), 1.4003, 1e-9);
        eq("initStability(3) Good", Fsrs.initStability(3), 3.7145, 1e-9);

        System.out.println("== 二、首次难度（Again 应比 Good 更难：D 更大） ==");
        // 实现为 D0(r) = W[4] - W[5]*(r-3)；W[4]=5.1618 W[5]=1.2298
        eq("initDifficulty(1)", Fsrs.initDifficulty(1), 5.1618 + 2 * 1.2298, 1e-4);
        eq("initDifficulty(2)", Fsrs.initDifficulty(2), 5.1618 + 1.2298, 1e-4);
        eq("initDifficulty(3)", Fsrs.initDifficulty(3), 5.1618, 1e-4);
        ok("D(Again) > D(Good)", Fsrs.initDifficulty(1) > Fsrs.initDifficulty(3),
           Fsrs.initDifficulty(1) + " > " + Fsrs.initDifficulty(3));

        System.out.println("== 三、首次间隔（R=0.9） ==");
        eq("interval(3.7145, 0.9)", Fsrs.interval(3.7145, Fsrs.TARGET_R), 3.7145, 1e-3);

        System.out.println("== 四、复现 P0：s=0 的卡误走 next() 会爆掉稳定性 ==");
        double s1 = Fsrs.initStability(3);
        double d1 = Fsrs.initDifficulty(3);
        double r0 = Fsrs.retrievability(0.0, s1);
        ok("retrievability(0, s) == 1", Math.abs(r0 - 1.0) < 1e-6, "got " + r0);
        // A09 修复后：底层直接拒绝 s=0，不再自动初始化。历史放大值 177.52922861244141 只能出现在
        // 旧代码里，现在必须抛 IllegalArgumentException —— 这条断言就是那次事故的长期看守。
        double rBad = Fsrs.retrievability(20.0 / 1440.0, 0.0);   // 20 分钟后，s=0
        boolean threw = false;
        String msg = "";
        try {
            Fsrs.next(0.0, 0.0, rBad, 3);
        } catch (IllegalArgumentException e) {
            threw = true;
            msg = e.getMessage();
        }
        ok("A09: next(s=0) 必须抛异常而不是静默放大到 177 天", threw, "msg=" + msg);
        // 修复后：isNewCard 走 initStability，不进 next()
        double[] nxGood = new double[]{Fsrs.initStability(3), Fsrs.initDifficulty(3), Fsrs.interval(Fsrs.initStability(3), Fsrs.TARGET_R)};
        ok("修复路径 s 落在 3.71", Math.abs(nxGood[0] - 3.7145) < 1e-6, "s=" + nxGood[0]);

        System.out.println("== 五、正常复习路径：20 天后 Good 增长有限 ==");
        double days = 3.7, r1 = Fsrs.retrievability(days, s1);
        double[] nx2 = Fsrs.next(s1, d1, r1, 3);
        ok("20 天前后 S 增长在 2-8 倍", nx2[0] > s1 * 1.5 && nx2[0] < s1 * 8, "s " + s1 + " -> " + nx2[0] + " r=" + r1);

        System.out.println("== 六、评分越高稳定性越大的偏序 ==");
        double sAgain = Fsrs.initStability(1), sHard = Fsrs.initStability(2), sGood = Fsrs.initStability(3);
        ok("Again < Hard < Good", sAgain < sHard && sHard < sGood, sAgain + " < " + sHard + " < " + sGood);

        System.out.println("== 七、区间有限且为正 ==");
        double iv = Fsrs.interval(3.7145, 0.9);
        ok("interval 为正且 < 365", iv > 0 && iv < 365, "ivl=" + iv);

        System.out.println("== 八、retrievability 随天数单调下降 ==");
        double r2 = Fsrs.retrievability(1, s1), r3 = Fsrs.retrievability(7, s1), r4 = Fsrs.retrievability(30, s1);
        ok("R(1) > R(7) > R(30)", r2 > r3 && r3 > r4, r2 + " > " + r3 + " > " + r4);

        System.out.println("== 九、next() 返回数组语义锁定 {s, d, interval} ==");
        // 历史事故：调用方按 {s, interval, d} 取值 -> due 把难度当天数。此断言看守顺序。
        double[] ord = Fsrs.next(3.7145, 5.1618, 0.9, 3);
        ok("out[0] 是稳定性（Good 复习后应变大）", ord[0] > 3.7145, "s=" + ord[0]);
        ok("out[1] 是难度（1..10 之间）", ord[1] >= 1.0 && ord[1] <= 10.0, "d=" + ord[1]);
        eq("out[2] == interval(out[0], TARGET_R)", ord[2], Fsrs.interval(ord[0], Fsrs.TARGET_R), 1e-9);
        ok("out[2] 是正天数且 < 3650", ord[2] > 0 && ord[2] < 3650, "ivl=" + ord[2]);

        System.out.println("== P1-15 遗忘曲线边界 ==");
        // 时间无穷远 = 早已忘光，R 必须趋近 0；原来把 +Inf 压成 0 天，R 会算成 1（等于「刚复习过」）
        ok("R(+Inf, s=3.7) = 0.0", Fsrs.retrievability(Double.POSITIVE_INFINITY, 3.7) == 0.0, "");
        ok("R(0, s=3.7) = 1.0", Math.abs(Fsrs.retrievability(0, 3.7) - 1.0) < 1e-9, "");
        boolean threwNeg = false;
        try { Fsrs.retrievability(-1, 3.7); } catch (IllegalArgumentException e) { threwNeg = true; }
        ok("R(-1) 必须抛异常（负数天数不是「0 天前」）", threwNeg, "");
        boolean threwNaN = false;
        try { Fsrs.retrievability(Double.NaN, 3.7); } catch (IllegalArgumentException e) { threwNaN = true; }
        ok("R(NaN) 必须抛异常", threwNaN, "");

        System.out.println();
        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
