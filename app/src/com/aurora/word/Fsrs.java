package com.aurora.word;

/** FSRS-4.5 调度器：三档评分（1=Again 2=Hard 3=Good） */
public final class Fsrs {
    // P1-14：final 只锁引用不锁元素，public 数组等于把 FSRS 参数暴露成全局可写变量。
    private static final double[] W = {
            0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031,
            1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755
    };
    public static final double DECAY = -0.5;
    public static final double FACTOR = 19.0 / 81.0;
    public static final double TARGET_R = 0.90;
    /** A10：间隔硬上界。防止异常输入把 due 推到天文数字（3650 天 = 10 年）。 */
    public static final double MAX_INTERVAL_DAYS = 3650.0;

    /** 账本用：算法标识，写进 review_event.algo。跨版本对比时不可混淆。 */
    public static final String ALGORITHM_VERSION = "fsrs-4.5";
    /** 账本用：目标保留率，与 TARGET_R 同值，导出字段名叫 desired_retention。 */
    public static final double DESIRED_RETENTION = 0.90;

    private Fsrs() {}

    /** 当前可提取性 R(t,S) */
    public static double retrievability(double elapsedDays, double s) {
        // A10：非有限输入一律不参与运算，返回 0 而不是把 NaN 传进 next()
        if (!(s > 0) || Double.isNaN(s) || Double.isInfinite(s)) return 0;
        // P1-15：原来把 ±Inf 和负数一律压成 0 天，等于「刚刚复习过」，R 会被高估成 1。
        // 语义上：时间无穷远 = 早已忘光（R→0）；NaN / 负数是调用方错误，必须抛而不是吞。
        if (Double.isNaN(elapsedDays)) throw new IllegalArgumentException("retrievability: NaN elapsedDays");
        if (elapsedDays == Double.POSITIVE_INFINITY) return 0.0;
        if (elapsedDays < 0) throw new IllegalArgumentException("retrievability: negative elapsedDays " + elapsedDays);
        if (elapsedDays == Double.NEGATIVE_INFINITY) throw new IllegalArgumentException("retrievability: -Inf elapsedDays");
        double base = 1 + FACTOR * elapsedDays / s;
        if (!(base > 0)) return 0;
        double v = Math.pow(base, DECAY);
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 由目标保留率反推间隔天数 */
    public static double interval(double s, double r) {
        // A10：s 必须有限正数，r 必须落在 (0,1) 开区间——r=0 会算出负间隔，r=1 会算出 0。
        if (!(s > 0) || Double.isNaN(s) || Double.isInfinite(s))
            throw new IllegalArgumentException("interval: bad stability " + s);
        if (!(r > 0.0) || !(r < 1.0))
            throw new IllegalArgumentException("interval: bad target retention " + r);
        double v = s * (Math.pow(r, 1.0 / DECAY) - 1.0) / FACTOR;
        if (Double.isNaN(v) || Double.isInfinite(v))
            throw new IllegalArgumentException("interval: overflow s=" + s + " r=" + r);
        return Math.max(1.0, Math.min(MAX_INTERVAL_DAYS, v));
    }

    public static double initStability(int rating) {
        // A09：非法 rating 必须显式拒绝。原来 clamp 到 0..2 会把 rating=9 静默当成 Good，
        // 让调用方的参数错误变成一次看不见的错误调度。
        if (rating < 1 || rating > 3)
            throw new IllegalArgumentException("initStability: rating must be 1..3, got " + rating);
        return Math.max(0.1, W[rating - 1]);
    }

    public static double initDifficulty(int rating) {
        if (rating < 1 || rating > 3)
            throw new IllegalArgumentException("initDifficulty: rating must be 1..3, got " + rating);
        double d = W[4] - (rating - 3) * W[5];
        return clampD(d);
    }

    private static double clampD(double d) {
        return Math.max(1.0, Math.min(10.0, d));
    }

    /**
     * 计算下一次状态。
     * @return {stability, difficulty, intervalDays}
     */
    /** 计算下一次复习参数。
     *  返回数组语义固定为 { stability, difficulty, interval(days) } —— 顺序不可改：
     *    out[0]=s stability, out[1]=d difficulty, out[2]=interval 天
     *  历史上曾出现调用方按 {s, interval, d} 取值导致 due 用难度当间隔的事故，
     *  故此处锁定语义，并由 FsrsTest 的 nextOrderIsStableAndInterval 断言看守。 */
    public static double[] next(double s, double d, double r, int rating) {
        // A09：底层拒绝无效状态，而不是自动初始化。
        // 历史事故（177.5292 天）：调用方把 s=0 的新卡送进来，这里悄悄用 initStability 顶上，
        // 于是「首次评分」被当成「第 n 次复习」，稳定性放大 47 倍。自动兜底会掩盖调用方错误，
        // 因此改为抛异常，由调用方显式处理（rate() 捕获后走软重置）。
        if (rating < 1 || rating > 3)
            throw new IllegalArgumentException("next: rating must be 1..3, got " + rating);
        if (!(s > 0) || Double.isNaN(s) || Double.isInfinite(s))
            throw new IllegalArgumentException("next: stability must be finite >0, got " + s);
        if (!(d >= 1.0 && d <= 10.0) || Double.isNaN(d))
            throw new IllegalArgumentException("next: difficulty must be in [1,10], got " + d);
        if (!(r >= 0.0 && r <= 1.0) || Double.isNaN(r))
            throw new IllegalArgumentException("next: retrievability must be in [0,1], got " + r);

        // difficulty
        double d2 = d - W[6] * (rating - 3);
        double d0 = initDifficulty(3);
        d2 = W[7] * d0 + (1 - W[7]) * d2;
        d2 = clampD(d2);

        double s2;
        if (rating == 1) {
            // 遗忘后重学
            double lapse = W[11] * Math.pow(d2, -W[12]) * (Math.pow(s + 1, W[13]) - 1)
                    * Math.exp(W[14] * (1 - r));
            s2 = Math.max(0.1, Math.min(s, lapse));
        } else {
            double hardPenalty = (rating == 2) ? W[15] : 1.0;
            double easyBonus = 1.0;
            s2 = s * (1 + Math.exp(W[8]) * (11 - d2) * Math.pow(s, -W[9])
                    * (Math.exp(W[10] * (1 - r)) - 1) * hardPenalty * easyBonus);
            s2 = Math.max(0.1, s2);
        }
        double ivl = interval(s2, TARGET_R);
        return new double[]{s2, d2, ivl};
    }
}
