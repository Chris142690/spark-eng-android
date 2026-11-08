package com.aurora.word;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity
        implements TextToSpeech.OnInitListener, View.OnClickListener, View.OnLongClickListener {

    // ---- 设计令牌 Warm Paper v2.0 ----
    static final int CANVAS = 0xFFF7F3EC;
    static final int CARD = 0xFFFFFFFF;
    static final int TEXT = 0xFF2C2824;
    static final int SUB = 0xFF6E665E;
    static final int ACCENT = 0xFF536F69;
    static final int BORDER = 0xFFE7DFD4;

    static final int CAP = 50;
    static final int PAT_PER_DAY = 3;   // GPT：每天最多 3 个不同句型（任务上限，不是点击上限）
    static final int NEW_MIN = 2, NEW_MAX = 4;   // GPT 裁决：0–4 是硬上限，5 词/天不批准

    // 懒筛状态：0=不筛 1=中文输入 2=例句挖空
    int screenStep = 0;
    boolean screenIsPending = false;
    long screenAt = 0;
    android.widget.EditText screenInput;

    Db db;
    TextToSpeech tts;
    boolean ttsReady = false;
    int newPerDay = 3;

    /** 局域网中继地址。中继是本机 sync_server.py，不联网也能改成本机 IP。 */
    static final String DEFAULT_SYNC_URL = "http://192.168.118.203:8904";

    List<String> queue = new ArrayList<String>();
    int idx = 0;
    int doneThisSession = 0;   // 今天已完成的卡数（跨会话持久化，用于 35 张封顶）
    int newToday = 0;          // 今天已新授的词数（跨会话持久化）
    long cardShownAt = 0;
    android.widget.EditText reviewInput = null;
    boolean revealed = false;
    // 认义复习的首次独立作答（GPT 授权 A 改良版）：提交后不可修改，单独入账。
    int firstVerdict = -1;
    String firstAnswer = "";
    String firstSense = null;   // 命中的义项原文（多义词过渡方案：留证据，但不等于掌握全部义项）
    boolean curIsNew = false;
    /** P0-1（R1 复审）：复习队列里的 state=3 卡确认失败后要走完整重学，
     *  但重学完必须回到复习队列，且下一张 state=2 的卡绝不能再被当成新词筛查
     *  （否则 submitScreen 会 new 一张 state=3 覆盖原卡，FSRS 的 s/d/reps/ivl 全部丢失）。 */
    boolean returnToReview = false;
    // 拼写线（第二条独立 FSRS，写 spell 表）。队列里用 @ 前缀区分。
    boolean spellMode = false;
    boolean spellRetried = false;
    /** 次日确认通过后交回的评分不再单独记账。绑定到具体词，防止标志遗留波及下一张卡。 */
    String alreadyCountedWord = null;
    /** 账本：当前学习会话 id（UUIDv4）。每次 startReview/startNew 生成一个，写入 session 表
     *  与 review_event.sid。为空表示当前没有打开的会话（例如只在首页浏览）。 */
    String curSid = null;
    /** 账本：当前会话开始时刻，用于算 active_ms。 */
    long sessionStartedAt = 0L;
    String lastActionDay = null;      // 最近一次成功记账的日期键（补偿凭证）
    boolean lastActionIsNew = false;
    int newStep = 1;
    LinearLayout curAns, curBottom;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        tts = new TextToSpeech(this, this);
        newPerDay = clamp(getSharedPreferences("aurora", 0).getInt("new", 3), NEW_MIN, NEW_MAX);
        adjustNewPerDay();
        loadForms();
        doneThisSession = dayDone();
        newToday = dayNew();
        Log.i("AURORA", "day done=" + doneThisSession + " new=" + newToday);
        Log.i("AURORA", "onCreate -> showHome");
        showHome();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(Locale.US);
            ttsReady = (r != TextToSpeech.LANG_MISSING_DATA) && (r != TextToSpeech.LANG_NOT_SUPPORTED);
        }
    }

    // ---- 每日额度：跨会话、跨进程持久化（GPT 审查 P0：原实现每次会话重置） ----
    /** 7 天实测保持率的三级文案。n<5 只报积累中；5–19 标注样本少；>=20 正常展示。 */
    String retText(String label, int n, int ok) {
        if (n <= 0) return "";
        if (n < 5) return "　·　" + label + " 7天实测：数据积累中 (" + n + "/5)";
        int pct = Math.round(ok * 100f / n);
        if (n < 20) return "　·　" + label + " " + pct + "% (" + ok + "/" + n + ") · 样本少，仅供参考";
        return "　·　" + label + " " + pct + "% (" + ok + "/" + n + ")";
    }

    /** 设备本地学习日的零点（毫秒）。封口、D0 过滤都按这个边界算，不按 UTC。 */
    long dayStartMs() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    String dayKey() {
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
    }

    String dayKey(long ts) {
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date(ts));
    }

    /** 新词数自动调节（GPT 认可的方向：由实际表现决定，不由预设）。
     *  规则刻意保守——只在「连续 3 天新词额度都用满」时 +1，只在「连续 3 天连一半都没做到」时 −1，
     *  范围 NEW_MIN..NEW_MAX，前 14 天完全不动（样本太少，动一次就是噪音）。
     *  不用「完成率」而用「新词额度是否用满」，因为完成率会被复习量干扰：
     *  某天复习积压把 50 张吃满，并不代表他能承受更多新词。 */
    void adjustNewPerDay() {
        android.content.SharedPreferences sp = getSharedPreferences("aurora", 0);
        long now = System.currentTimeMillis();
        long first = sp.getLong("first_day", 0);
        if (first == 0) { sp.edit().putLong("first_day", now).commit(); return; }
        if (now - first < 14L * 86400000L) return;

        int full = 0, half = 0;
        for (int i = 1; i <= 3; i++) {
            int n = sp.getInt("new_" + dayKey(now - i * 86400000L), 0);
            if (n >= newPerDay) full++;
            if (n * 2 <= newPerDay) half++;
        }
        int next = newPerDay;
        if (full >= 3) next = Math.min(NEW_MAX, newPerDay + 1);
        else if (half >= 3) next = Math.max(NEW_MIN, newPerDay - 1);
        if (next != newPerDay) {
            newPerDay = next;
            sp.edit().putInt("new", next).commit();
            Log.i("AURORA", "newPerDay -> " + next);
        }
    }

    int dayDone() {
        return getSharedPreferences("aurora", 0).getInt("done_" + dayKey(), 0);
    }

    int dayNew() {
        return getSharedPreferences("aurora", 0).getInt("new_" + dayKey(), 0);
    }

    /** 唯一记账入口（GPT 审查 P0）。规则：
     *  - 每次「提交一次判定」记 1 个学习动作；PASS / FAIL / UNVERIFIED 都算，失败不能免费。
     *  - newToday 只在某词第一次真正进入系统时 +1（懒筛两步全过建卡 / 新授完成建卡），重试不再加。
     *  - 每次记账现读一次自然日（不冻结 sessionDay）；跨午夜自然获得新一天额度，但历史日期账本按
     *    done_yyyyMMdd / new_yyyyMMdd 分键保存，不会被下次切换覆盖。
     *  - 用 commit() 而非 apply()：额度是账本，刚记完就被杀进程也必须落盘。
     * @return false 表示额度不足或落盘失败，调用方必须中止本次动作。 */
    boolean tryRecordStudyAction(boolean isNewWord) {
        String today = dayKey();
        android.content.SharedPreferences sp = getSharedPreferences("aurora", 0);
        int d = sp.getInt("done_" + today, 0);
        int n = sp.getInt("new_" + today, 0);
        if (d >= CAP) { toast("今天的学习额度已用完"); return false; }
        if (isNewWord && n >= newPerDay) { toast("今天的新词额度已用完"); return false; }
        android.content.SharedPreferences.Editor e = sp.edit().putInt("done_" + today, d + 1);
        if (isNewWord) e.putInt("new_" + today, n + 1);
        if (!e.commit()) {
            Log.e("AURORA", "counter commit failed day=" + today);
            diag("counter-fail", "day=" + today + " done=" + d);
            toast("学习记录保存失败，请重试");
            return false;
        }
        doneThisSession = d + 1;
        if (isNewWord) newToday = n + 1;
        // 动作凭证：补偿时必须用原动作发生的日期，不能重新读 dayKey()（否则 23:59 记账、00:01 补偿会扣错日期）
        lastActionDay = today;
        lastActionIsNew = isNewWord;
        return true;
    }

    /** 额度补偿回滚（审查 FIX-02）：把当天已持久化的计数真正减回去。
     *  只用于「已经记了额度，但卡片/筛查结果没写进库」的动作，避免账本与实际学习结果脱节。
     *  这是 SharedPreferences 账本的临时方案；最终形态应迁入 SQLite 与 card/log 同事务。 */
    boolean compensateStudyAction(boolean isNewWord, String why) {
        // A08：凭证一次性消费。原来没有凭证也能补偿（today 回退成 dayKey），
        // 于是同一次动作被补偿两次就会把 done 减两次，凭空多出额度。
        if (lastActionDay == null) {
            diag("compensate-denied", "why=" + why + " no outstanding action");
            return false;
        }
        if (lastActionIsNew != isNewWord) {
            diag("compensate-denied", "why=" + why + " type mismatch last=" + lastActionIsNew + " req=" + isNewWord);
            return false;
        }
        // 用原动作的日期凭证，而不是当前的 dayKey()：跨午夜时不会扣到新的一天
        String today = lastActionDay;
        // P1-8：凭证必须等 commit 成功才消费。原来在 commit 之前就清空，
        // 一旦 commit 失败这一次补偿就永久丢失（额度已扣、动作已失败、凭证也没了）。
        android.content.SharedPreferences sp = getSharedPreferences("aurora", 0);
        int d = sp.getInt("done_" + today, 0);
        int n = sp.getInt("new_" + today, 0);
        int d2 = Math.max(0, d - 1);
        int n2 = Math.max(0, n - 1);
        android.content.SharedPreferences.Editor e = sp.edit().putInt("done_" + today, d2);
        if (isNewWord) e.putInt("new_" + today, n2);
        boolean ok = e.commit();
        if (ok) {
            doneThisSession = d2;
            if (isNewWord) newToday = n2;
            lastActionDay = null;          // 只有真的退回去了才作废凭证
            lastActionIsNew = false;
        } else {
            // commit 失败时绝不能动内存值，否则页面显示已恢复、重启后又变回未补偿
            Log.e("AURORA", "compensation commit failed day=" + today);
        }
        diag("compensate", "why=" + why + " day=" + today + " done " + d + "->" + d2
                + (isNewWord ? (" new " + n + "->" + n2) : "") + " ok=" + ok);
        return ok;
    }

    void bumpDay() { tryRecordStudyAction(false); }

    /** 新授算一次动作：总量 + 新词预算同时 +1（洞2：两个闸门必须一起记）。 */
    void bumpNew() { tryRecordStudyAction(true); }

    void speak(String s) {
        if (!ttsReady || s == null) return;
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "w");
    }

    // ================= 首页 =================
    void showHome() {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(28), dp(24), dp(14));

        col.addView(text(fmtDate(), 16, SUB, false));

        TextView title = text("今天，先学一点", 22, TEXT, true);
        title.setPadding(0, dp(8), 0, 0);
        col.addView(title);

        // ---- 正向反馈三件套（GPT 裁决）----
        // ①今日完成数与进度 ②累计掌握量（单调递增，不因答错清零）③额度完成明确提示休息
        final int doneToday = dayDone();
        final int started = db.countStarted();
        // GPT 核验结论：s>=7 只是模型预测（R(t)=(1+19/81·t/S)^-0.5，t=S=7 时 R=90%），
        // 不等于 7 天实测保持率，所以首页只能写「预计已掌握」。按不同词去重（双通道同词只算一个）。
        final int stable = db.countStableDistinct();
        final boolean capDone = doneToday >= CAP;

        final int spellDone = db.spellStarted();
        int[] ret = db.retention7();   // {认义有效数, 认义答对数, 拼写有效数, 拼写答对数}
        // GPT 裁决的三级显示：样本 <5 只报「积累中」，5–19 必须标注样本少，>=20 才正常展示。
        // 达到 20 也不代表统计稳定，所以任何一级都不写「已掌握 X%」这种保证性文案。
        String retTxt = retText("认义", ret[0], ret[1]) + retText("拼写", ret[2], ret[3]);
        TextView progress = text("今天 " + doneToday + " / " + CAP + "　·　累计 " + started + " 词　·　预计掌握 " + stable
                + (spellDone > 0 ? "　·　会拼 " + spellDone : "") + retTxt,
                14, SUB, false);
        progress.setPadding(0, dp(6), 0, 0);
        col.addView(progress);

        if (capDone) {
            title.setText("今天可以休息了");
        }

        col.addView(flex());

        // 数字必须和 startReview() 的队列口径一致（封口 + 当天新授都扣掉），
        // 否则会出现「显示待复习 3 张，点进去说没有到期复习」这种自相矛盾的首页。
        final long dayStart = dayStartMs();
        final int due = db.dueCount(dayKey(), dayStart);
        final int spellDueN = db.spellDueCount(dayKey(), dayStart);
        final int dueAll = due + spellDueN;

        // 首页改版（用户要求「参考不背单词首页 UI」）：
        // 不背单词的首页是「一个巨大数字 + 一个全宽实心主按钮」，主行动只有一个，一眼可见。
        // 原来复习入口藏在卡片里、靠「点击开始复习 →」这行小字提示，用户明确反馈找不到。
        // 现在：待复习数字放到 56sp，主按钮是卡内全宽实心「开始今日复习」，
        // 新授降级为卡外描边次按钮——层级关系不再靠颜色深浅去猜。
        LinearLayout card = box(CARD, 24, BORDER);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(20));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        col.addView(card, cardLp);

        card.addView(text(capDone ? "今天的额度用完了" : "今日待复习", 13, SUB, false));
        TextView num = text(capDone ? (doneToday + "/" + CAP) : String.valueOf(dueAll), 56, TEXT, true);
        num.setTypeface(Typeface.create("serif", Typeface.BOLD));
        num.setPadding(0, dp(6), 0, 0);
        card.addView(num);
        card.addView(text(dueAll > 0 ? "认义 " + due + " 　·　拼写 " + spellDueN : "没有到期的卡", 13, SUB, false));

        if (dueAll > 0 && !capDone) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            blp.topMargin = dp(18);
            TextView go = text(spellDueN > 0 ? "开始今日复习" : "开始今日复习", 17, 0xFFFFFFFF, true);
            go.setGravity(Gravity.CENTER);
            go.setBackground(fill(ACCENT, 16));
            go.setTag("review");
            go.setOnClickListener(this);
            card.addView(go, blp);
        } else if (!capDone) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            blp.topMargin = dp(18);
            TextView go = text("现在没有要复习的", 15, BORDER, true);
            go.setGravity(Gravity.CENTER);
            go.setBackground(fill(0xFFF1ECE4, 16));
            card.addView(go, blp);
        }

        // 句型线入口（GPT 批准的最小闭环）。它和单词账本分开计时、分开计数，
        // 不占 50 次单词动作额度——45–60 分钟是英语总预算，不是单词动作必须做满。
        if (patternsLoaded()) {
            int pDue = db.patternDueCount(System.currentTimeMillis());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            plp.topMargin = dp(12);
            TextView pbtn = text(pDue > 0 ? "练句型 · 待复习 " + pDue + " 句"
                            : "学句型 · " + pats.size() + " 个句型",
                    15, ACCENT, true);
            pbtn.setGravity(Gravity.CENTER);
            pbtn.setBackground(borderBox(BORDER, 16));
            pbtn.setTag("pat");
            pbtn.setOnClickListener(this);
            col.addView(pbtn, plp);

            // 待确认入口（GPT 第 15 轮 P0 修复）：连续两次判不了的句子原先永久退出队列，
            // 用户没有任何办法把它放回来，等于静默丢内容。这里给一条可见、可达的恢复路径。
            // 只在 N>0 时出现——没有问题时不制造额外视觉噪音。
            final int pPend = db.patternPendingCount();
            if (pPend > 0) {
                LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
                qlp.topMargin = dp(8);
                TextView pnd = text("待确认 " + pPend + " 句 ›", 14, 0xFF8A6A4A, true);
                pnd.setGravity(Gravity.CENTER);
                pnd.setBackground(borderBox(0xFFE3D3BE, 16));
                pnd.setTag("pending");
                pnd.setOnClickListener(this);
                col.addView(pnd, qlp);
            }
        }

        // 新授：次按钮（描边），永远在复习下面——不抢主行动的视觉位
        if (!capDone) {
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            nlp.topMargin = dp(12);
            TextView btn = text("学新词 · 今天 " + newPerDay + " 个", 15, TEXT, true);
            btn.setGravity(Gravity.CENTER);
            btn.setBackground(borderBox(BORDER, 16));
            btn.setTag("new");
            btn.setOnClickListener(this);
            col.addView(btn, nlp);
        } else {
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            nlp.topMargin = dp(12);
            TextView rest = text("今天到此为止", 15, 0xFF8A8078, true);
            rest.setGravity(Gravity.CENTER);
            rest.setBackground(fill(0xFFEFE9DF, 16));
            col.addView(rest, nlp);
        }

        col.addView(flex());

        TextView tip = text("今天的英语小知识 ▸   （长按导出学习数据）", 14, SUB, false);
        tip.setPadding(dp(4), dp(14), 0, dp(4));
        tip.setTag("export");
        tip.setOnLongClickListener(this);
        col.addView(tip);

        Log.i("AURORA", "showHome done due=" + due);
        setContentView(col);
    }

    // ================= 复习 =================
    void startReview() {
        doneThisSession = dayDone();
        newToday = dayNew();
        int remaining = CAP - doneThisSession;
        if (remaining <= 0) { toast("今天的 " + CAP + " 张已经做完了，明天继续"); showHome(); return; }
        queue = new ArrayList<String>();
        // 复习队列 = 认义线到期卡 + 拼写线到期卡，按 due 混排（GPT 裁决：不设两套 UI 入口）。
        // 拼写卡用 @ 前缀标记；同日双到期时优先拼写，另一个保留原 due 不动。
        // 两条线的到期卡各自已按 due 升序（db.due / spellDue 都 ORDER BY due）。
        // 拼写线整体排在前面：同日双到期时优先拼写，认义那张保留原 due 等下次排。
        // 不用 Comparator 匿名内部类——d8 8.2.2 遇到「外部类 + 内部类」同批会抛
        // NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null，
        // dex 静默不产出、打包照旧成功，得到一个装上去就闪退的假包。
        int picked = 0;
        boolean hasSpell = false;
        // 同日封口在 SQL 层排除（GPT 复裁纠正：`remaining + sealed.size()` 补偿不是可靠分页，
        // 封口词与非今日候选混在一起会把真正的任务截断）。查询已经按 due 升序，取满即止。
        int d0skip = 0;
        for (Db.Card k : db.spellDue(remaining, dayKey())) {
            if (picked >= remaining) break;
            if (isTaughtToday(k)) { d0skip++; continue; }
            queue.add("@" + k.w);
            picked++;
            hasSpell = true;
        }
        for (Db.Card k : db.due(remaining + d0skip, dayKey())) {
            if (picked >= remaining) break;
            if (isTaughtToday(k)) { d0skip++; continue; }
            if (db.spellCard(k.w) != null && db.spellCard(k.w).due <= System.currentTimeMillis()) {
                continue;   // 两个都到期：拼写已入队，认义保留原 due，下次再排
            }
            queue.add(k.w);
            picked++;
        }
        if (d0skip > 0) diag("d0-skip", "n=" + d0skip + " date=" + dayKey());
        if (queue.isEmpty()) { toast("今天没有到期复习了"); showHome(); return; }
        idx = 0; newStep = 1;
        // 入口必须清空旧页面状态，否则上次停在 screenStep=2 会让 showCard() 的 state=3 恢复条件失效
        curIsNew = false;
        returnToReview = false;
        screenStep = 0;
        screenIsPending = false;
        screenAt = 0;
        alreadyCountedWord = null;
        // 会话 track 要如实反映内容：混了拼写卡就记 mixed，否则统计里 recognition 会话里会冒出 spelling 事件
        openSession(hasSpell ? "mixed" : "recognition");
        showCard();
    }

    // ================= 新授 =================
    void startNew() {
        doneThisSession = dayDone();
        newToday = dayNew();
        int remaining = CAP - doneThisSession;      // 洞2：总量闸门
        int newRemaining = newPerDay - newToday;    //       新词闸门
        if (remaining <= 0) { toast("今天的 " + CAP + " 张已经做完了，明天继续"); showHome(); return; }
        // P1-11：新词预算用完 ≠ 不能学。待重新学习（state=4）的词不占新词预算，必须还能进来。
        int relearnWait = db.relearnCount();
        if (newRemaining <= 0 && relearnWait <= 0) { toast("今天的 " + newPerDay + " 个新词已经学过，明天继续"); showHome(); return; }
        if (remaining < 3) {
            // P1：首次筛查 2 次 + 新授完成 1 次，额度不足以完整走完一次就不要开始
            toast("今日剩余额度不足以完整学一个新词");
            showHome();
            return;
        }
        // A05：队列长度按动作数折算。一个完整新词 = 首次筛查 2 + 新授完成 1 = 3 个动作，
        // 按词数取 limit 会让队列总成本超出当日剩余额度，走到一半撞额度上限留下半截流程。
        int limit = Math.min(remaining / 3, Math.max(newRemaining, relearnWait));
        if (limit <= 0) { toast("今日剩余额度不足以完整学一个新词"); showHome(); return; }
        List<String> fresh = db.fresh(limit);
        if (fresh.isEmpty()) { toast("词表已经学完了"); showHome(); return; }
        queue = fresh; idx = 0; newStep = 1;
        curIsNew = true;
        returnToReview = false;
        screenStep = 1; screenIsPending = false; screenAt = 0;
        alreadyCountedWord = null;
        openSession("mixed");
        showCard();
    }

    void showCard() {
        if (idx >= queue.size()) { endSession(); return; }
        String rawKey = queue.get(idx);
        spellMode = rawKey != null && rawKey.startsWith("@");
        final Db.Word w = db.word(spellMode ? rawKey.substring(1) : rawKey);
        if (w == null) { idx++; showCard(); return; }

        if (spellMode) {
            // 拼写复习：直接进「看中文写英文」通道，不走懒筛那套 state 判定
            spellRetried = false;
            screenStep = 2;
            screenIsPending = false;
            curIsNew = false;
        }

        if (!curIsNew && !spellMode && screenStep == 0) {
            Db.Card kc = db.card(w.w);
            if (kc != null && kc.state == 3) { screenStep = 1; screenIsPending = true; }
        }
        if (curIsNew && screenStep >= 1) {
            // A03：state=4（待重新学习）绝不能走懒筛——必须先走完整新授。
            // 关键：screenStep 必须归 0，否则 buildScreen 仍会渲染筛查界面（= 免费跳过新授）。
            Db.Card kc = db.card(w.w);
            if (kc != null && kc.state == 4) { screenStep = 0; screenIsPending = false; newStep = 1; }
        }

        LinearLayout col = column();
        col.setPadding(dp(24), dp(14), dp(24), dp(16));

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, SUB, false);
        back.setPadding(dp(6), 0, dp(16), dp(4));
        back.setTag("home");
        back.setOnClickListener(this);
        bar.addView(back);
        bar.addView(flex());
        // 四种任务来源必须在界面上可辨，否则用户不知道自己在做什么
        String modeTag;
        if (spellMode) modeTag = "拼写复习";
        else if (curIsNew) modeTag = "新词";
        else if (screenStep == 1 && screenIsPending) modeTag = "次日确认";
        else modeTag = "认义复习";
        TextView modeView = text(modeTag, 12, ACCENT, false);
        modeView.setPadding(0, 0, dp(10), 0);
        bar.addView(modeView);
        bar.addView(text((idx + 1) + " / " + queue.size(), 13, SUB, false));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        if (curIsNew) col.addView(stepDots());

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(46), 0, 0);
        sc.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // A02：通道二（看中文写英文）绝不能显示英文目标词——那就是答案本身。
        // 只有 screenStep==0（新授/复习）和 screenStep==1（看英文写中文）可以显示词形。
        if (screenStep != 2) {
            TextView word = text(w.w, 44, TEXT, true);
            word.setTypeface(Typeface.create("serif", Typeface.BOLD));
            word.setGravity(Gravity.CENTER);
            word.setTag("speak|" + w.w);
            word.setOnClickListener(this);
            body.addView(word);
        } else {
            TextView q = text("？", 44, BORDER, true);
            q.setTypeface(Typeface.create("serif", Typeface.BOLD));
            q.setGravity(Gravity.CENTER);
            body.addView(q);
        }
        if (screenStep == 0) addIpaLine(body, w);

        LinearLayout ans = new LinearLayout(this);
        ans.setOrientation(LinearLayout.VERTICAL);
        ans.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(ans);
        curAns = ans;

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        col.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));
        curBottom = bottom;

        cardShownAt = SystemClock.elapsedRealtime();
        revealed = false;
        firstVerdict = -1;
        firstAnswer = "";
        firstSense = null;
        reviewInput = null;

        if (screenStep > 0) {
            buildScreen(w, ans, bottom);
        } else if (curIsNew) {
            if (newStep <= 2) speak(w.w);
            buildNew(w, ans, bottom);
        } else {
            buildReview(w, ans, bottom);
        }

        setContentView(col);
    }

    View stepDots() {
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, 0, 0, dp(12));
        for (int i = 1; i <= 3; i++) {
            View d = new View(this);
            int sz = (i == newStep) ? dp(8) : dp(6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMargins(dp(3), 0, dp(3), 0);
            d.setBackground(fill(i <= newStep ? ACCENT : BORDER, 99));
            dots.addView(d, lp);
        }
        return dots;
    }

    /** 认义复习（GPT 授权 A 改良版）：先输入中文意思 → 提交 → 再看答案 → 自评。
     *  原流程「看词 → 显示答案 → 自评」在口径上每次都是 hinted=1，7 天实测保持率的分母
     *  永远为 0；改成先作答之后，首次独立作答才有客观判定可入账。 */
    void buildReview(final Db.Word w, final LinearLayout ans, final LinearLayout bottom) {
        TextView hint = text("先写出它的中文意思，再对答案", 14, SUB, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(24), 0, 0);
        ans.addView(hint);

        reviewInput = new android.widget.EditText(this);
        reviewInput.setHint("中文意思");
        reviewInput.setTextSize(18);
        reviewInput.setTextColor(TEXT);
        reviewInput.setHintTextColor(0xFFA79E93);
        reviewInput.setBackground(fill(0xFFFFFFFF, 14));
        reviewInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        reviewInput.setSingleLine(true);
        reviewInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        reviewInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        ans.addView(reviewInput, lp);

        TextView ok = text("提交答案", 16, 0xFFFFFFFF, true);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(fill(ACCENT, 16));
        ok.setTag("ans|" + w.w);
        ok.setOnClickListener(this);
        bottom.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView skip = text("不会，直接看答案", 13, SUB, false);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(10), 0, 0);
        skip.setTag("show|" + w.w);
        skip.setOnClickListener(this);
        bottom.addView(skip);
    }

    /** 认义复习的首次作答：判对/判错/无法判定，判完才揭示答案。 */
    void submitReview(Db.Word w, String raw) {
        String a = (raw == null) ? "" : raw.trim();
        int v;
        Sense.Result sr = Sense.judge(a, w.cn);
        String sense = sr.sense;
        v = sr.verdict;                  // CORRECT / INCORRECT / UNVERIFIED 三态同源
        firstAnswer = a;
        firstSense = sense;
        firstVerdict = v;
        revealed = false;                // 先作答再看答案：这一次不是提示下的作答
        fillAnswer(curAns, w);
        if (v == ANS_PASS) {
            TextView good = text("对　" + a, 14, ACCENT, true);
            good.setGravity(Gravity.CENTER);
            good.setPadding(0, dp(10), 0, 0);
            curAns.addView(good);
        } else if (v == ANS_FAIL) {
            TextView bad = text("你写的是「" + a + "」", 14, 0xFFB4544A, true);
            bad.setGravity(Gravity.CENTER);
            bad.setPadding(0, dp(10), 0, 0);
            curAns.addView(bad);
        } else {
            TextView un = text("没作答，这次不计入保持率", 13, SUB, false);
            un.setGravity(Gravity.CENTER);
            un.setPadding(0, dp(10), 0, 0);
            curAns.addView(un);
        }
        curBottom.removeAllViews();
        if (v == ANS_PASS) {
            addRateButtons(curBottom, w);
        } else {
            // GPT「Sense 判定边界最终裁决」：自动未匹配时展示答案并允许一次申诉，
            // 申诉必须发生在 FSRS 评分提交之前——先判 Again 再撤销是不允许的。
            // 因此这里不给三档自评，只给「接受错误」与「我答的是对的（待核实）」。
            addWrongChoices(curBottom, w, v == ANS_FAIL);
        }
    }

    /** 自动判定未通过时的两条出路（GPT 流程：选择接受错误，或提交待核实）。 */
    void addWrongChoices(LinearLayout bottom, final Db.Word w, boolean allowAppeal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout acc = new LinearLayout(this);
        acc.setOrientation(LinearLayout.VERTICAL);
        acc.setGravity(Gravity.CENTER);
        acc.setBackground(fill(ACCENT, 16));
        acc.addView(text(allowAppeal ? "接受，记为忘记了" : "不会，记为忘记了", 13, 0xFFFFFFFF, true));
        acc.addView(text("1", 10, 0xFFD9E4E1, false));
        acc.setTag("acc|" + w.w);
        acc.setOnClickListener(this);
        row.addView(acc, new LinearLayout.LayoutParams(0, dp(76), allowAppeal ? 1f : 2f));
        if (allowAppeal) {
            LinearLayout ap = new LinearLayout(this);
            ap.setOrientation(LinearLayout.VERTICAL);
            ap.setGravity(Gravity.CENTER);
            ap.setBackground(borderBox(BORDER, 16));
            ap.addView(text("我答的是对的", 13, TEXT, true));
            ap.addView(text("待核实，不更新进度", 10, SUB, false));
            ap.setTag("ap|" + w.w);
            ap.setOnClickListener(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1f);
            lp.leftMargin = dp(8);
            row.addView(ap, lp);
        }
        bottom.addView(row);
    }

    /** 认义申诉：只改指标口径与待核实状态，不更新 S/D/reps/lapses，原始 due 不变。
     *  GPT 底线：「宁可暂时不知道答得对不对，也不能把可能正确的答案写成遗忘，
     *  更不能让用户靠申诉把错误刷成掌握。」 */
    void appealReview(Db.Word w) {
        if (!tryRecordStudyAction(false)) { showHome(); return; }
        long ms = SystemClock.elapsedRealtime() - cardShownAt;
        Db.Card k = db.card(w.w);
        boolean tx = db.begin();
        boolean okEv = curSid == null || db.recordReview(curSid, w.w, "recognition",
                0, System.currentTimeMillis(), ms, 0, 0,
                k == null ? 0 : k.s, k == null ? 0 : k.s,
                k == null ? 0 : k.d, k == null ? 0 : k.d,
                k == null ? 0 : k.ivl, k == null ? 0 : k.ivl,
                k == null ? 0 : k.state, k == null ? 0 : k.state,
                false, 0, ANS_UNVERIFIED, firstAnswer, null);
        // UNVERIFIED 也要占用当天的封口额度，否则可以靠无限重测绕过
        boolean sealed = okEv && db.sealRetrieval(w.w, dayKey(), "recognition",
                System.currentTimeMillis(), curSid);
        if (!okEv || !sealed) {
            // GPT 复裁：事务回滚后封口也不存在，所以不能声称「今天已经封过这个词」。
            // 正确做法是退回原提交状态，允许重提同一次申诉，且不能因此生成第二次评分。
            db.end(tx, false);
            compensateStudyAction(false, "appeal-rollback");
            diag("appeal-rollback", w.w + " ev=" + okEv + " seal=" + sealed);
            toast("保存失败，可以再点一次申诉");
            String keep = firstAnswer;      // 原始答案不许改：重放同一个答案，只重试落库
            showCard();
            submitReview(w, keep);
            return;
        }
        db.log(w.w, "appeal", 0, ms, 0, 0, 0, 0);
        db.end(tx, true);
        diag("appeal", w.w + " ans=" + firstAnswer);
        toast("已记下，下次见到它时再确认");
        if (doneThisSession >= CAP) { endSession(); return; }
        idx++;
        newStep = 1;
        showCard();
    }

    void addIpaLine(LinearLayout parent, Db.Word w) {
        boolean hasIpa = w.ipa != null && w.ipa.length() > 0;
        boolean hasPos = w.pos != null && w.pos.length() > 0;
        if (!hasIpa && !hasPos) return;
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(line, lp);
        // 音节切点（GPT 裁决「必须借鉴」）：con·ceal / ef·fec·tive。
        // 只对本就有多个音节的词显示；单音节词 pyphen 不给切点，不显示也不占位。
        boolean hasSyl = w.syl != null && w.syl.indexOf('\u00b7') >= 0;
        if (hasSyl) {
            TextView syl = text(w.syl, 16, ACCENT, false);
            syl.setGravity(Gravity.CENTER);
            syl.setTag("speak|" + w.w);
            syl.setOnClickListener(this);
            line.addView(syl);
            TextView gap0 = text("\u00b7", 14, BORDER, false);
            LinearLayout.LayoutParams g0 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            g0.setMargins(dp(10), 0, dp(10), 0);
            line.addView(gap0, g0);
        }
        if (hasIpa) {
            TextView ipa = text(w.ipa, 16, SUB, false);
            ipa.setGravity(Gravity.CENTER);
            ipa.setTag("speak|" + w.w);
            ipa.setOnClickListener(this);
            line.addView(ipa);
        }
        if (hasIpa && hasPos) {
            TextView gap = text("\u00b7", 14, BORDER, false);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.setMargins(dp(10), 0, dp(10), 0);
            line.addView(gap, gp);
        }
        if (hasPos) {
            TextView pos = text(w.pos, 14, SUB, false);
            pos.setGravity(Gravity.CENTER);
            line.addView(pos);
        }
    }

    void fillAnswer(LinearLayout ans, Db.Word w) {
        ans.removeAllViews();
        addCn(ans, w);
        addExample(ans, w);
    }

    @SuppressWarnings("unused")
    void addPos(LinearLayout ans, Db.Word w) {
    }

    void addCn(LinearLayout ans, Db.Word w) {
        TextView cn = text(w.cn, 22, TEXT, false);
        cn.setGravity(Gravity.CENTER);
        cn.setPadding(0, dp(16), 0, 0);
        cn.setLineSpacing(dp(7), 1f);
        ans.addView(cn);
    }

    void addExample(LinearLayout ans, Db.Word w) {
        boolean hasEx = w.ex != null && w.ex.length() > 0;
        boolean hasTr = w.excn != null && w.excn.length() > 0;
        if (!hasEx && !hasTr) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(22);
        ans.addView(box, blp);

        View rule = new View(this);
        rule.setBackground(fill(0x33536F69, 99));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(2),
                ViewGroup.LayoutParams.MATCH_PARENT);
        box.addView(rule, rlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.addView(col, clp);

        if (hasEx) {
            TextView ex = text(w.ex, 17, TEXT, false);
            ex.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            ex.setGravity(Gravity.START);
            ex.setLineSpacing(dp(6), 1f);
            ex.setTag("say|" + w.ex);
            ex.setOnClickListener(this);
            col.addView(ex);
        }
        if (hasTr) {
            TextView tr = text(w.excn, 14, SUB, false);
            tr.setGravity(Gravity.START);
            tr.setLineSpacing(dp(5), 1f);
            tr.setPadding(0, dp(8), 0, 0);
            col.addView(tr);
        }
    }

    void addRateButtons(LinearLayout bottom, final Db.Word w) {
        String[] names = {"忘记了", "有点难", "记得"};
        String[] subs = {"1", "2", "3"};
        int[] ratings = {1, 2, 3};
        for (int i = 0; i < 3; i++) {
            // GPT：独立答案与自评分别记录，「禁止矛盾评分」——首次独立答对却又自评「忘记了」
            // 是自相矛盾的输入：保留按钮会让 FSRS 收到与事实相反的信号，直接不给这个选项。
            if (i == 0 && firstVerdict == ANS_PASS) continue;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(i == 2 ? fill(ACCENT, 16) : borderBox(BORDER, 16));
            b.addView(text(names[i], 13, i == 2 ? 0xFFFFFFFF : TEXT, true));
            b.addView(text(subs[i], 10, i == 2 ? 0xFFD9E4E1 : SUB, false));
            b.setTag("rate|" + w.w + "|" + ratings[i]);
            b.setOnClickListener(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == 2 ? 0 : dp(4), 0);
            bottom.addView(b, lp);
        }
    }

    // ================= 懒筛 =================
    void buildScreen(final Db.Word w, LinearLayout ans, LinearLayout bottom) {
        // 双通道：通道一 英文→中文；通道二 中文→英文。
        // GPT 审查：原通道二用例句挖空，固定句型（I don't ___ if I can help.）靠语境就能猜中，
        // 测的是推断不是回忆；且会连带测拼写。故改为只给中文释义，例句只在教学屏展示。
        String hintText = spellMode
                ? "拼写复习 · 看中文写出英文"
                : (screenStep == 1 ? "还记得它的意思吗？写一个中文意思" : "看到意思，把英文单词写出来");
        TextView hint = text(hintText, 14, SUB, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(24), 0, dp(18));
        ans.addView(hint);

        if (screenStep == 2) {
            // 拼写模式给完整释义：order 有「命令/订购/顺序」三义，只给第一个会让用户猜错方向；
            // 义项多不算作弊——目标词本身仍要自己拼出来。
            TextView cn = text(spellMode ? w.cn : firstCn(w.cn), spellMode ? 18 : 22, TEXT, false);
            cn.setGravity(Gravity.CENTER);
            cn.setLineSpacing(dp(6), 1f);
            cn.setPadding(dp(20), 0, dp(20), 0);
            ans.addView(cn);
            // P1-A：第二步只给中文释义（题目本身），隐藏词性——词性是额外提示，会缩小答案范围
            if (spellMode) {
                // 给字母数不算作弊：Wordle 式提示减少「拼对但少个 s」这种纯挫败，
                // 答案本身仍要靠自己提取；同时也让 nearMiss（差一字母重试）有意义。
                TextView len = text(w.w.length() + " 个字母", 12, BORDER, false);
                len.setGravity(Gravity.CENTER);
                len.setPadding(0, dp(8), 0, 0);
                ans.addView(len);
            }
        }

        screenInput = new android.widget.EditText(this);
        screenInput.setHint(screenStep == 1 ? "中文意思" : "英文单词");
        screenInput.setTextSize(18);
        screenInput.setTextColor(TEXT);
        screenInput.setHintTextColor(0xFFA79E93);
        screenInput.setBackground(fill(0xFFFFFFFF, 14));
        screenInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        screenInput.setSingleLine(true);
        screenInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        screenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        ans.addView(screenInput, lp);

        TextView sub = text("想不起来就直接交白卷", 13, SUB, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(18), 0, 0);
        ans.addView(sub);

        TextView btn = text("提交", 16, 0xFFFFFFFF, true);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(fill(ACCENT, 16));
        btn.setTag("screen|" + w.w);
        btn.setOnClickListener(this);
        bottom.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        screenAt = SystemClock.elapsedRealtime();
    }

    /** 取第一个义项作为提示，避免把半个词库的释义都摆出来 */
    String firstCn(String cn) {
        if (cn == null) return "";
        String[] parts = cn.split("[；;，,\n、/]");
        for (String p : parts) {
            String t = p.trim().replaceAll("[（(].*?[)）]", "").trim();
            if (t.length() > 0) return t;
        }
        return cn.trim();
    }

    /** 把例句里的目标词挖空（词边界匹配，避免误伤同形词） */
    String blank(String ex, String w) {
        if (ex == null || w == null) return "";
        try {
            String q = java.util.regex.Pattern.quote(w) + "(?:s|es|ed|d|ing|'s)?";
            return ex.replaceAll("(?i)(?<![A-Za-z])" + q + "(?![A-Za-z])", "______");
        } catch (Exception e) {
            return ex;
        }
    }

    /** 中文判定已抽到纯 Java 的 Sense（可在 JVM 单测里跑，不依赖真机）。
     *  这里保留同名薄封装，避免 Activity 里散落判定逻辑。 */
    String matchCnSense(String raw, String cn) {
        return Sense.judge(raw, cn).sense;
    }

    boolean matchCn(String raw, String cn) {
        return Sense.match(raw, cn);
    }

    String normCn(String s) {
        return Sense.normCn(s);
    }

    /** 英文判定：原形或白名单里的真实词形（来自 ECDICT exchange，禁止机械后缀拼接）
     *  旧实现会接受 goed/sayed/studyed 这类错误拼写，属 P0 漏洞 */
    int matchEn(String raw, String w) {
        String a = normEn(raw), b = normEn(w);
        if (b.length() == 0) return ANS_FAIL;
        if (a.equals(b)) return ANS_PASS;
        if (!gFormsLoaded) return ANS_UNVERIFIED;   // P1：词形数据没加载成功，无法可靠判定
        java.util.Set<String> set = gForms.get(b);
        // P1-7：查不到词条不能直接判错。当前 forms.json 只收「有屈折形式」的词，
        // 「这个词本来没有屈折形」和「表单里漏了它」在数据结构上无法区分，
        // 一律判 FAIL 会把「拼对了但表里没有」冤枉成错误答案。按 UNVERIFIED 处理（不计分）。
        if (set == null) return ANS_UNVERIFIED;
        if (!set.contains(a)) return ANS_FAIL;
        // 歧义处理（复审结论）：调用方始终知道当前在考的词条 w，因此「某形式还属于别的词条」
        // 不构成无法判定的歧义——真正的歧义判定需要「当前在考哪个词条」之外的信息
        // （多词条同屏、无提示自由回忆、按词形聚合的掌握率统计）。本系统三者都不成立，
        // 故命中当前词条的合法词形即 PASS，不做形式级歧义拦截。
        // UNVERIFIED 仅保留给词形数据本身缺失/损坏/无法解析的情形（当前实现未触发）。
        // 若将来引入多词条同屏或按形式聚合统计，此函数必须重新审查。
        return ANS_PASS;
    }

    static final java.util.Map<String, java.util.Set<String>> gForms = new java.util.HashMap<String, java.util.Set<String>>();
    static boolean gFormsLoaded = false;
    /** 三档判定：0=FAIL 1=PASS 2=UNVERIFIED（跨词条同形，不计分） */
    static final int ANS_FAIL = Sense.INCORRECT, ANS_PASS = Sense.CORRECT, ANS_UNVERIFIED = Sense.UNVERIFIED;

    void loadForms() {
        if (gFormsLoaded) return;
        // 局部 Map 暂存：解析中途异常时不留半份数据，下次仍会重试
        java.util.Map<String, java.util.Set<String>> tmp = new java.util.HashMap<String, java.util.Set<String>>();
        try {
            java.io.InputStream in = getAssets().open("forms.json");
            byte[] buf = new byte[in.available()];
            int off = 0;
            while (off < buf.length) {
                int k = in.read(buf, off, buf.length - off);
                if (k <= 0) break;
                off += k;
            }
            in.close();
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, "UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                org.json.JSONArray arr = o.optJSONArray(k);
                if (arr == null) continue;
                java.util.Set<String> set = new java.util.HashSet<String>();
                for (int i = 0; i < arr.length(); i++) set.add(normEn(arr.optString(i)));
                tmp.put(normEn(k), set);
            }
            // A15：解析成功不等于数据完整。词形表正常应有 1000+ 词条，若寥寥几条
            // （文件被截断/换了格式/asset 打包漏了），置 loaded 会让 matchEn 对所有词静默判 FAIL。
            if (tmp.size() < 1000) {
                Log.e("AURORA", "forms.json incomplete: " + tmp.size() + " entries, refuse to load");
                diag("forms-incomplete", "entries=" + tmp.size());
                return;
            }
            gForms.putAll(tmp);
            gFormsLoaded = true;
            Log.i("AURORA", "forms loaded " + gForms.size());
        } catch (Exception e) {
            Log.e("AURORA", "loadForms fail " + e);
        }
    }

    /** P1-6：合法英文词形（单词或词组，允许撇号/连字符连接）。 */
    static final java.util.regex.Pattern EN_FORM = java.util.regex.Pattern.compile(
            "[a-z]+(?:['\\-][a-z]+)*(?: [a-z]+(?:['\\-][a-z]+)*)*");

    String normEn(String s) {
        if (s == null) return "";
        // P1-6：先做格式合法性校验，不合法直接返回空（= 判错），
        // 绝不通过删除非法字符来凑相等。原来 g.o / go123 / g@o 都会被清洗成 go 判对。
        String t = s.trim().toLowerCase(java.util.Locale.US);
        t = t.replaceAll("[\\s]+", " ");
        if (t.length() == 0) return "";
        if (!EN_FORM.matcher(t).matches()) return "";
        return t;
    }

    /** 把 state=3 的待确认临时卡彻底回退成新词。state!=3 直接返回。
     *  审查 P0：UNVERIFIED 与判定失败两条分支都必须走这里，否则界面当新词、DB 当待确认卡，
     *  次日会被重复拉进确认流程。 */
    /** 独立诊断日志：Logcat 会随卸载消失，落盘可追溯。 */
    void diag(String tag, String msg) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "diag.log");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(System.currentTimeMillis() + " [" + tag + "] " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            Log.e("AURORA", "diag fail " + e);
        }
    }

    boolean resetPendingCard(String word) {
        Db.Card k = db.card(word);
        if (k == null || k.state != 3) return true;
        // P0：重置为目标 state=4（待重新学习），不是 state=1（首测待评）。
        // 若置 1，因 due(now) 会立刻进入复习队列，用户不走完整新授也能直接评分 → 绕过懒筛。
        k.state = 4;
        k.s = 0;
        k.d = 0;
        k.ivl = 0;
        k.reps = 0;
        k.last = 0;
        k.due = System.currentTimeMillis();
        if (!db.saveCard(k)) {
            diag("reject-resetPending", word);
            return false;
        }
        Log.i("AURORA", "resetPendingCard " + word);
        return true;
    }

    // 判错后展示对比所需的状态（不落库：只是这一次会话的展示）
    Db.Word wrongCard;
    String wrongRaw;
    int wrongFromStep;
    boolean wrongFromIsNew, wrongFromPending;

    /** 判错后并列展示「你写的 / 正确」，等用户点「继续」才重置复学。
     *  认义关比中文，拼写关比英文；拼写关额外逐字符标出差异位置。 */
    void showWrongContrast(Db.Word w, String raw) {
        wrongCard = w;
        wrongRaw = raw == null ? "" : raw.trim();
        wrongFromStep = screenStep;
        wrongFromIsNew = curIsNew;
        wrongFromPending = screenIsPending;
        boolean english = (screenStep == 2);
        String expect = english ? w.w : w.cn;

        curAns.removeAllViews();
        if (english) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(20), 0, 0);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView lab = text("你写的是", 13, SUB, false);
            box.addView(lab);
            TextView got = text(wrongRaw.length() == 0 ? "（空）" : wrongRaw, 24, BORDER, true);
            got.setTypeface(Typeface.MONOSPACE);
            box.addView(got);
            TextView lab2 = text("正确的是", 13, SUB, false);
            LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2.topMargin = dp(18);
            box.addView(lab2, l2);
            TextView want = text(w.w, 24, TEXT, true);
            want.setTypeface(Typeface.MONOSPACE);
            want.setTag("speak|" + w.w);
            want.setOnClickListener(this);
            box.addView(want);
            curAns.addView(box);
            // 逐字符差异：只标「写错的位置」，不做复杂的编辑距离对齐
            String diff = diffMark(wrongRaw.toLowerCase(), w.w.toLowerCase());
            if (diff.length() > 0) {
                TextView d = text(diff, 14, SUB, false);
                d.setTypeface(Typeface.MONOSPACE);
                LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dp2.topMargin = dp(14);
                curAns.addView(d, dp2);
            }
        } else {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(20), 0, 0);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.addView(text("你写的是", 13, SUB, false));
            box.addView(text(wrongRaw.length() == 0 ? "（空）" : wrongRaw, 20, BORDER, false));
            TextView lab2 = text("正确的是", 13, SUB, false);
            LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2.topMargin = dp(18);
            box.addView(lab2, l2);
            box.addView(text(expect, 22, TEXT, false));
            curAns.addView(box);
        }

        curBottom.removeAllViews();
        TextView go = text("继续", 16, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(fill(ACCENT, 14));
        go.setTag("wrongnext");
        go.setOnClickListener(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        curBottom.addView(go, bp);
    }

    /** 标出「你写的」与「正确的」逐位不同的字符，供用户一眼看到错在哪。
     *  只做等长逐位比较——不做编辑距离，因为差一个字母时逐位更直观。 */
    String diffMark(String got, String want) {
        if (got.length() == 0) return "";
        int n = Math.max(got.length(), want.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char a = i < got.length() ? got.charAt(i) : ' ';
            char b = i < want.length() ? want.charAt(i) : ' ';
            sb.append(a == b ? b : '·');
        }
        String marks = sb.toString();
        boolean any = marks.indexOf('·') >= 0;
        return any ? marks + "  ← 这里不一样" : "";
    }

    /** 用户看完对比、点「继续」之后，才真正回到完整新授。 */
    void continueAfterWrong() {
        if (wrongCard == null) { showHome(); return; }
        Db.Word w = wrongCard;
        wrongCard = null;
        // 拼写线答错只记 Again，不进新授——拼写线没有「重学新词」这回事，
        // 认义线那张卡（如果存在）不受影响，两条线各记各的。
        if (spellMode) { rateSpell(w, 1); return; }
        // 判定失败必须真正回到完整新授：清掉临时卡，禁止残留 state=3 让用户继续评分
        if (!resetPendingCard(w.w)) { toast("学习状态重置失败，请重试"); showHome(); return; }
        // P0-1：先记下来源，再改 curIsNew。复习队列（curIsNew=false）里的卡重学完必须回复习队列。
        returnToReview = !wrongFromIsNew;
        screenStep = 0;
        screenIsPending = false;
        curIsNew = true;
        newStep = 1;
        toast("没关系，从头看一遍");
        showCard();
    }

    // 已删除 nearMiss()：GPT 明确禁用 Levenshtein≤1 作为拼写判定（form/from 反例）。
    // 现在唯一容错是 trim + 大小写（见 matchEn），以及答错后的「输入失误」申诉（不评分）。

    /** GPT 复裁 Q5：拼写线**严格匹配目标词形**，不接受的屈折形式。
     *  例：目标 go，输入 goes / went 一律 FAIL；goes 是 go 的变形只作为解释反馈，不算对。
     *  理由：「拼写线检测的是指定词形的主动提取，不是泛泛知道这个单词」。
     *  只做首尾空格与大小写标准化（normEn），其余必须逐字母一致。 */
    int matchSpellStrict(String raw, String w) {
        String a = normEn(raw), b = normEn(w);
        if (b.length() == 0) return ANS_FAIL;
        return a.equals(b) ? ANS_PASS : ANS_FAIL;
    }

    /** 输入的是该词的合法变形时给一句人话解释，但不改判分。 */
    String formHint(String raw, String w) {
        if (!gFormsLoaded) return null;
        String a = normEn(raw), b = normEn(w);
        java.util.Set<String> set = gForms.get(b);
        if (set == null || !set.contains(a)) return null;
        return a + " 是 " + w + " 的变形形式，拼写线只接受原形";
    }

    /** 拼写线提交：严格词形一致才算对；不评分的一次申诉；否则进答错对比。 */
    void submitSpell(Db.Word w, String raw) {
        if (firstVerdict < 0) {          // 只记第一次提交的判定，后续申诉重输不覆盖
            firstAnswer = (raw == null) ? "" : raw.trim();
        }
        int v = matchSpellStrict(raw, w.w);
        if (firstVerdict < 0) firstVerdict = v;   // 首次拼写的客观判定：PASS 才算入保持率
        if (v == ANS_UNVERIFIED) {
            toast("这个词有同形，先跳过");
            idx++;
            showCard();
            return;
        }
        if (v == ANS_PASS) {
            rateSpell(w, 3);
            return;
        }
        // GPT 裁决：判定用「忽略首尾空格与大小写、内部字母必须一致」，**禁用 Levenshtein≤1**
        //（form/from 就是反例——编辑距离 1，但记忆不同）。所以这里不再用 nearMiss 当门槛。
        // 答错时给一次「输入失误」申诉：只记待核实事件，不评分；同词同日只许一次（落库，重启仍生效）。
        if (!spellRetried && db.claimSpell(w.w)) {
            spellRetried = true;
            diag("spell-claim", w.w);
            toast("已记为输入失误，这次不计分。请再输入一次");
            if (screenInput != null) screenInput.setText("");
            return;
        }
        String fh = formHint(raw, w.w);
        if (fh != null) toast(fh);
        showWrongContrast(w, raw);
    }

    /** 拼写线评分：与 rate() 平行，但写 spell 表、账本 track 记 spelling。 */
    void rateSpell(Db.Word w, int rating) {
        if (rating < 1 || rating > 3) { diag("spell-invalid-rating", w.w); return; }
        if (!tryRecordStudyAction(false)) { showHome(); return; }
        long ms = SystemClock.elapsedRealtime() - cardShownAt;
        Db.Card k = db.spellCard(w.w);
        if (k == null) {
            diag("spell-no-card", w.w);
            compensateStudyAction(false, "spell-no-card");
            toast("拼写记录缺失，已跳过");
            showHome();
            return;
        }
        boolean isNewCard = (k.reps == 0);
        double prevS = k.s, prevD = k.d, prevIvl = k.ivl;
        int prevState = k.state;
        double days = 0.0, r = 0.0;
        double[] nx;
        try {
            if (isNewCard) {
                double s0 = Fsrs.initStability(rating);
                double d0 = Fsrs.initDifficulty(rating);
                nx = new double[]{s0, d0, Fsrs.interval(s0, Fsrs.TARGET_R)};
            } else {
                days = Math.max(0, (System.currentTimeMillis() - k.last) / 86400000.0);
                r = Fsrs.retrievability(days, k.s);
                nx = Fsrs.next(k.s, k.d, r, rating);
            }
        } catch (IllegalArgumentException bad) {
            diag("spell-fsrs-reject", w.w + " " + bad.getMessage());
            k.state = 4; k.s = 0; k.d = 0; k.ivl = 0; k.reps = 0;
            k.last = 0; k.due = System.currentTimeMillis();
            db.saveSpell(k);
            compensateStudyAction(false, "spell-fsrs-reject");
            toast("拼写记录异常，已重置");
            showHome();
            return;
        }
        k.s = nx[0];
        k.d = nx[1];
        k.ivl = nx[2];
        k.state = 2;
        k.reps++;
        if (rating == 1 && !isNewCard) k.lapses++;
        k.last = System.currentTimeMillis();
        k.due = k.last + (long) (nx[2] * 86400000L);
        // 与 rate() 同一套事务纪律：拼写卡更新 + 账本事件 + 封口，要么全成要么全回滚。
        boolean tx = db.begin();
        boolean saved = db.saveSpell(k);
        boolean sealed = saved && db.sealRetrieval(w.w, dayKey(), "spelling", k.last, curSid);
        if (!saved) {
            db.end(tx, false);
            diag("spell-save-failed", w.w);
            compensateStudyAction(false, "spell-save-failed");
            toast("保存失败，这张先不算");
            showCard();
            return;
        }
        if (!sealed) {
            // 这个词今天已经被另一条通道正式提取过 → 整笔回滚，不重复计分
            db.end(tx, false);
            diag("seal-duplicate-rollback", w.w + " spelling");
            toast("这个词今天已经测过了，这次不计分");
            idx++;
            showCard();
            return;
        }
        if (curSid != null) {
            boolean okEv = db.recordReview(curSid, w.w, "spelling", rating,
                    System.currentTimeMillis(), ms, days, r, prevS, k.s, prevD, k.d,
                    prevIvl, k.ivl, prevState, k.state, isNewCard, revealed ? 1 : 0,
                    firstVerdict, firstAnswer, firstSense);
            if (!okEv) diag("spell-event-failed", w.w);
        }
        db.log(w.w, "spelling", rating, ms, prevIvl, k.ivl, prevS, k.s);
        db.end(tx, true);
        idx++;
        doneThisSession++;
        showCard();
    }

    void submitScreen(Db.Word w) {
        String raw = (screenInput == null) ? "" : screenInput.getText().toString();
        long ms = SystemClock.elapsedRealtime() - screenAt;
        if (spellMode) { submitSpell(w, raw); return; }
        int verdict = (screenStep == 1) ? (matchCn(raw, w.cn) ? ANS_PASS : ANS_FAIL) : matchEn(raw, w.w);
        // 计数口径（与 GPT 复核确认后的账目一致）：
        //   首次筛查第一步=1、第二步=1（第二步 PASS 时同时把该词计入 newToday）
        //   次日确认第一步=0、第二步=1（成功失败都算一次尝试）
        //   确认通过后交回的评分=0（由 alreadyCountedWord 豁免）
        // 顺序：先申请额度，成功后才写 screen 记录，避免「有记录但没扣额度」。
        // P1-11：重学词（state=4）第二步 PASS 也不能记成新词——它已经占过新词预算了。
        // 判定读数据库而不是内存布尔：进程被杀后重启，state=4 仍然在库里，判定不会失真。
        Db.Card preCard = db.card(w.w);
        boolean relearnWord = (preCard != null && preCard.state == 4);
        boolean isNewSubmit = (screenStep == 2 && verdict == ANS_PASS && !screenIsPending && !relearnWord);
        int cost = screenIsPending ? (screenStep == 2 ? 1 : 0) : 1;
        if (cost > 0 && !tryRecordStudyAction(isNewSubmit)) { showHome(); return; }
        boolean screenSaved = db.saveScreen(w.w, screenStep, raw, verdict == ANS_PASS ? 1 : (verdict == ANS_UNVERIFIED ? 2 : 0), ms);
        if (!screenSaved) {
            // A06：记录没落库就停下；绝不能把写入失败解释成判错继续推进。
            // 无论本次是否扣了额度（次日确认第一步 cost=0）都要停——否则次日确认会「免费且不留痕」。
            diag("saveScreen-failed", w.w + " step=" + screenStep + " cost=" + cost);
            if (cost > 0) compensateStudyAction(isNewSubmit, "saveScreen-failed");
            toast("记录失败，请重试");
            showHome();
            return;
        }
        if (verdict == ANS_UNVERIFIED) {
            // 跨词条同形（如 lives 既是 life 复数又是 live 三单）：不判错也不判过，
            // 不建临时卡、不改 FSRS，直接回完整新授按新词学（审查建议：不扣分不加分不更新 FSRS）
            // GPT 补充检查：UNVERIFIED 和 FAIL 都会 resetPendingCard + curIsNew=true，
            // 因此这里同样必须先记录「我原本站在复习队列还是新授队列」，
            // 否则复习卡被 UNVERIFIED 判成重学后，队列来源照样丢失（P0-1 的同类路径）。
            returnToReview = !curIsNew;
            if (!resetPendingCard(w.w)) { toast("学习状态重置失败，请重试"); showHome(); return; }
            screenStep = 0;
            screenIsPending = false;
            curIsNew = true;
            newStep = 1;
            toast("这个词暂时无法判定，先重新学习");
            showCard();
            return;
        }
        boolean ok = (verdict == ANS_PASS);

        if (!ok) {
            // GPT 裁决「答错并列英文对比」：先把「你写的 / 正确」并排列出来，
            // 用户点「继续」之后才回完整新授。原来的实现直接 toast 一句就重学，
            // 用户根本不知道自己错成了什么——这是挫败感的主要来源之一。
            showWrongContrast(w, raw);
            return;
        }
        if (screenStep == 1) { screenStep = 2; showCard(); return; }

        screenStep = 0;
        if (screenIsPending) {
            // 次日确认通过：不自动给 Good，交回真实三档评分
            screenIsPending = false;
            revealed = true;
            alreadyCountedWord = w.w;   // 只豁免当前这个词的这一次评分
            curAns.removeAllViews();
            fillAnswer(curAns, w);
            curBottom.removeAllViews();
            addRateButtons(curBottom, w);
            return;
        }
        // 首次筛查两步全过 -> 临时待确认卡，24 小时后确认
        // P0-1 双保险：只有「从未进过 FSRS」的卡才允许落 state=3。
        // 若这里读到 state=2，说明队列来源标记出了问题，覆盖它会直接抹掉 s/d/reps/ivl。
        Db.Card exist = db.card(w.w);
        if (exist != null && exist.state == 2) {
            diag("screen-would-clobber-review", w.w + " reps=" + exist.reps + " s=" + exist.s);
            toast("这张卡已经在复习序列里，跳过筛查");
            idx++;
            screenStep = 0;
            showCard();
            return;
        }
        Db.Card k = new Db.Card();
        k.w = w.w;
        k.state = 3;
        k.last = System.currentTimeMillis();
        k.due = k.last + 24L * 3600L * 1000L;
        k.s = 0; k.d = 0; k.ivl = 0; k.reps = 0; k.lapses = 0;
        if (!db.saveCard(k)) {
            // P0-2：临时卡没写进库就不能继续推进，否则额度已扣、词却没进入次日确认
            diag("reject-pending", k.w);
            compensateStudyAction(true, "pending-save-failed");
            toast("确认卡保存失败，请稍后重试");
            showHome();
            return;
        }
        if (newToday >= newPerDay) { endNewSession(); return; }
        idx++;
        screenStep = 1;
        showCard();
    }

    void buildNew(final Db.Word w, final LinearLayout ans, final LinearLayout bottom) {
        if (newStep >= 2) {
            addPos(ans, w);
            addCn(ans, w);
            addExample(ans, w);
        }

        if (newStep >= 3) {
            ans.removeAllViews();
            TextView q = text("遮住释义，回想它是什么意思", 16, SUB, false);
            q.setGravity(Gravity.CENTER);
            ans.addView(q);
            TextView btn = text("显示释义", 16, 0xFFFFFFFF, true);
            btn.setGravity(Gravity.CENTER);
            btn.setBackground(fill(ACCENT, 16));
            bottom.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            btn.setTag("show|" + w.w);
            btn.setOnClickListener(this);
            return;
        }

        TextView btn = text(newStep == 1 ? "看释义" : "遮住释义，回想一下", 16, 0xFFFFFFFF, true);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(fill(ACCENT, 16));
        bottom.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        btn.setTag("next");
        btn.setOnClickListener(this);
    }

    // ================= 句型线（GPT 批准的最小闭环） =================
    // 与单词模块完全解耦：不碰 card/spell，不占 CAP，失败不污染记忆状态。
    // 流程：结构教学（formula + 释义 + 2 例句 + 2 组替换）→ 中文意图→英文独立表达 → 反馈与参考答案。
    /** 场景表达（GPT 命名）：附着在句型上的语块，不建独立卡片、不进复习队列。 */
    static class Coll {
        String id, en, cn, note;
        String[] newVocab;
    }

    static class Pat {
        String id, cefr, scene, formula, meaning, prompt, cv, errFocus;
        String[] refs, vocab;
        String[][] ex;    // {en, cn}
        String kind = "pattern";   // pattern = 句型；function = 功能词专项（GPT 第 9 轮授权）
        String[][] subs;
        java.util.List<Coll> colls = new java.util.ArrayList<Coll>();  // {slot, "a / b / c"}
    }

    List<Pat> pats = null;
    List<Pat> patQueue = null;
    int patIdx = 0, patStep = 0, patDoneSession = 0;
    Pat curPat = null;
    String patAnswer = "";
    Pattern.Verdict patVerdict = null;
    boolean patHint = false;
    boolean patPendingNow = false;   // 本次提交后是否进待审核（决定反馈文案）
    long patStart = 0;
    android.widget.EditText patInput = null;
    int patSubIdx = 0;          // 当前用第几条场景表达做替换练习

    boolean patternsLoaded() {
        if (pats == null) loadPatterns();
        return pats != null && !pats.isEmpty();
    }

    /** 按 pattern_id 归属把这些语块挂到句型上。读不到就静默降级（句型本体仍可用）。 */
    void loadCollocations() {
        try {
            java.io.InputStream in = getAssets().open("collocations.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            org.json.JSONArray arr = new org.json.JSONArray(new String(bo.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Pat host = patById(o.optString("pattern_id"));
                if (host == null) continue;
                Coll c = new Coll();
                c.id = o.optString("collocation_id");
                c.en = o.optString("english");
                c.cn = o.optString("chinese");
                c.note = o.optString("teaching_note");
                org.json.JSONArray nv = o.optJSONArray("new_vocab");
                c.newVocab = nv == null ? new String[0] : new String[nv.length()];
                for (int k = 0; nv != null && k < nv.length(); k++) c.newVocab[k] = nv.getString(k);
                if (host.colls.size() < 3) host.colls.add(c);   // GPT：每句型只挂 2–3 条
            }
        } catch (Exception e) {
            Log.e("AURORA", "collocations load failed: " + e);
        }
    }

    void loadPatterns() {
        List<Pat> out = new ArrayList<Pat>();
        try {
            java.io.InputStream in = getAssets().open("patterns.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            org.json.JSONArray arr = new org.json.JSONArray(new String(bo.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Pat p = new Pat();
                p.id = o.getString("pattern_id");
                p.cefr = o.optString("cefr_target", "A1");
                p.scene = o.optString("scenario", "");
                p.formula = o.optString("formula", "");
                p.meaning = o.optString("meaning_zh", "");
                p.prompt = o.optString("prompt_zh", "");
                p.cv = o.optString("content_version", "v1");
                // 功能词专项（f-be / f-have / f-do）走 D+1→D+3→D+7→D+14 的阶梯，
                // 靠 kind 辨别；这里不读，三条功能词就会落回普通句型的 D+3/D+7/D+14。
                p.kind = o.optString("kind", "pattern");
                org.json.JSONArray ef = o.optJSONArray("error_focus");
                StringBuilder et = new StringBuilder();
                for (int j = 0; ef != null && j < ef.length(); j++) {
                    if (et.length() > 0) et.append("；");
                    et.append(ef.getString(j));
                }
                p.errFocus = et.toString();
                org.json.JSONArray ra = o.optJSONArray("reference_answers");
                p.refs = new String[ra == null ? 0 : ra.length()];
                for (int j = 0; j < p.refs.length; j++) p.refs[j] = ra.getString(j);
                org.json.JSONArray rv = o.optJSONArray("required_vocab");
                p.vocab = new String[rv == null ? 0 : rv.length()];
                for (int j = 0; j < p.vocab.length; j++) p.vocab[j] = rv.getString(j);
                org.json.JSONArray ex = o.optJSONArray("examples");
                p.ex = new String[ex == null ? 0 : ex.length()][];
                for (int j = 0; j < p.ex.length; j++) {
                    org.json.JSONObject e = ex.getJSONObject(j);
                    p.ex[j] = new String[]{e.optString("en"), e.optString("cn")};
                }
                org.json.JSONArray su = o.optJSONArray("substitutions");
                p.subs = new String[su == null ? 0 : su.length()][];
                for (int j = 0; j < p.subs.length; j++) {
                    org.json.JSONObject e = su.getJSONObject(j);
                    org.json.JSONArray it = e.optJSONArray("items");
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; it != null && k < it.length(); k++) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(it.getString(k));
                    }
                    p.subs[j] = new String[]{e.optString("slot"), sb.toString()};
                }
                out.add(p);
            }
        } catch (Throwable e) {
            Log.e("AURORA", "loadPatterns failed", e);
            diag("patterns-load-failed", String.valueOf(e));
        }
        pats = out;
        loadCollocations();
    }

    Pat patById(String id) {
        if (pats == null) loadPatterns();
        if (pats == null) return null;
        for (Pat p : pats) if (p.id.equals(id)) return p;
        return null;
    }

    /** 今日已练过的句型 id（逗号分隔）。GPT 要求「当天做过的句型不再正式测试」，
     *  所以必须持久化，不能只靠内存——重启后仍要知道今天做过哪几句。 */
    String patDoneIds() {
        return getSharedPreferences("aurora", 0).getString("patIds_" + dayKey(), "");
    }

    boolean patDoneToday(String id) {
        String all = patDoneIds();
        if (all.length() == 0) return false;
        String[] arr = all.split(",");
        for (String x : arr) if (x.equals(id)) return true;
        return false;
    }

    int patCountToday() {
        String all = patDoneIds();
        if (all.length() == 0) return 0;
        return all.split(",").length;
    }

    void startPattern() {
        if (!patternsLoaded()) { toast("句型内容缺失，先修好 assets/patterns.json"); return; }
        int used = patCountToday();
        if (used >= PAT_PER_DAY) { toast("今日句型练习已完成 " + used + "/" + PAT_PER_DAY); showHome(); return; }
        patQueue = new ArrayList<Pat>();
        long now = System.currentTimeMillis();
        int room = PAT_PER_DAY - used;
        for (Db.PatState st : db.patternDue(room, now)) {
            Pat p = patById(st.pid);
            if (p != null && !patDoneToday(p.id)) patQueue.add(p);
        }
        if (patQueue.size() < room) {
            java.util.Set<String> seen = db.patternSeenIds();
            for (Pat p : pats) {
                if (patQueue.size() >= room) break;
                if (!seen.contains(p.id) && !patDoneToday(p.id)) patQueue.add(p);
            }
        }
        if (patQueue.isEmpty()) { toast("句型已经全部排好队，明天再来"); showHome(); return; }
        patIdx = 0; patStep = 0; patDoneSession = 0;
        showPattern();
    }

    void showPattern() {
        if (patQueue == null || patIdx >= patQueue.size()) {
            toast("句型练完了：" + patDoneSession + " 句");
            showHome();
            return;
        }
        curPat = patQueue.get(patIdx);
        if (patStep == 0) showPatternTeach();
        else if (patStep == 1) showPatternTest();
        else showPatternFeedback();
    }

    LinearLayout patternFrame(String kicker) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(26), dp(24), dp(14));
        TextView back = text("← 返回", 14, SUB, false);
        back.setTag("home");
        back.setOnClickListener(this);
        col.addView(back);
        TextView k = text(kicker, 13, ACCENT, false);
        k.setPadding(0, dp(10), 0, 0);
        col.addView(k);
        return col;
    }

    void showPatternTeach() {
        LinearLayout col = patternFrame("今日句型 " + (patCountToday() + 1) + "/" + PAT_PER_DAY
                + "　·　" + curPat.cefr + "　·　" + curPat.scene);
        TextView f = text(curPat.formula, 22, TEXT, true);
        f.setTypeface(Typeface.create("serif", Typeface.BOLD));
        f.setPadding(0, dp(8), 0, 0);
        col.addView(f);
        TextView m = text(curPat.meaning, 15, SUB, false);
        m.setPadding(0, dp(10), 0, 0);
        col.addView(m);

        LinearLayout card = box(CARD, 20, BORDER);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        col.addView(card, clp);
        for (int i = 0; i < curPat.ex.length; i++) {
            TextView en = text(curPat.ex[i][0], 17, TEXT, true);
            if (i > 0) en.setPadding(0, dp(12), 0, 0);
            card.addView(en);
            card.addView(text(curPat.ex[i][1], 13, SUB, false));
        }
        if (curPat.subs.length > 0) {
            TextView h = text("可替换", 12, SUB, false);
            h.setPadding(0, dp(16), 0, 0);
            card.addView(h);
            for (String[] su : curPat.subs) card.addView(text(su[0] + "：" + su[1], 13, TEXT, false));
        }

        // 场景表达：看懂 + 会用，不建卡片、不进队列、浏览不算成功（GPT 边界）
        if (!curPat.colls.isEmpty()) {
            TextView h = text("场景表达　·　先看懂，等会儿换你说", 12, SUB, false);
            h.setPadding(0, dp(20), 0, 0);
            card.addView(h);
            for (int i = 0; i < curPat.colls.size(); i++) {
                Coll c = curPat.colls.get(i);
                TextView en = text(c.en, 16, TEXT, true);
                en.setPadding(0, dp(10), 0, 0);
                card.addView(en);
                card.addView(text(c.cn, 13, SUB, false));
                if (c.newVocab.length > 0) {
                    TextView nv = text("新词 " + join(c.newVocab), 11, SUB, false);
                    nv.setPadding(0, dp(4), 0, 0);
                    card.addView(nv);
                }
                if (c.note != null && c.note.length() > 0) {
                    TextView nt = text(c.note, 12, SUB, false);
                    nt.setPadding(0, dp(4), 0, 0);
                    card.addView(nt);
                }
            }
        }

        col.addView(flex());
        boolean hasColl = !curPat.colls.isEmpty();
        TextView go = text(hasColl ? "换你来说一句" : "开始练习", 16, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(fill(ACCENT, 16));
        go.setTag(hasColl ? "subgo" : "patgo");
        go.setOnClickListener(this);
        col.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(col);
    }

    static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) b.append("、");
            b.append(a[i]);
        }
        return b.toString();
    }

    /** 替换练习：给中文情境，让用户自己组织一句英文（GPT：不能只阅读）。
     *  答案跟该语块比对，非精确匹配记 UNVERIFIED，不伪装成掌握了这个句型。 */
    void showPatternSubstitute() {
        if (curPat == null || curPat.colls.isEmpty()) { showPatternTest(); return; }
        int i = patSubIdx % curPat.colls.size();
        Coll c = curPat.colls.get(i);
        LinearLayout col = patternFrame("换你来说　·　" + (i + 1) + "/" + curPat.colls.size());
        TextView hint = text("用英语说出下面这句", 13, SUB, false);
        hint.setPadding(0, dp(6), 0, dp(10));
        col.addView(hint);
        TextView q = text(c.cn, 20, TEXT, true);
        q.setTypeface(Typeface.create("serif", Typeface.BOLD));
        col.addView(q);
        TextView f = text("可以套用：" + curPat.formula, 13, SUB, false);
        f.setPadding(0, dp(10), 0, dp(14));
        col.addView(f);

        patInput = new android.widget.EditText(this);
        patInput.setHint("写一句英文");
        patInput.setTextSize(17);
        patInput.setTextColor(TEXT);
        patInput.setHintTextColor(SUB);
        patInput.setBackground(borderBox(BORDER, 14));
        patInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        patInput.setSingleLine(false);
        patInput.setMinLines(2);
        patInput.setTag("sub2|" + c.id);
        col.addView(patInput);

        col.addView(flex());
        TextView go = text("提交", 16, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(fill(ACCENT, 16));
        go.setTag("sub2go|" + c.id);
        go.setOnClickListener(this);
        col.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(col);
    }

    void showPatternTest() {
        LinearLayout col = patternFrame("用英语说出来　·　" + (patIdx + 1) + "/" + patQueue.size());
        TextView q = text(curPat.prompt, 20, TEXT, true);
        q.setTypeface(Typeface.create("serif", Typeface.BOLD));
        q.setPadding(0, dp(8), 0, 0);
        col.addView(q);

        patInput = new android.widget.EditText(this);
        patInput.setHint(curPat.formula);
        patInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        patInput.setTextColor(TEXT);
        patInput.setHintTextColor(BORDER);
        patInput.setBackground(borderBox(BORDER, 12));
        patInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        patInput.setMinLines(2);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(18);
        col.addView(patInput, ilp);

        col.addView(flex());
        TextView sub = text("提交", 16, 0xFFFFFFFF, true);
        sub.setGravity(Gravity.CENTER);
        sub.setBackground(fill(ACCENT, 16));
        sub.setTag("patsub");
        sub.setOnClickListener(this);
        col.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView skip = text("不会，看参考答案", 14, SUB, true);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(12), 0, dp(4));
        skip.setTag("patskip");
        skip.setOnClickListener(this);
        col.addView(skip);
        patStart = System.currentTimeMillis();
        setContentView(col);
    }

    /** 场景表达的替换练习：记录真实答案，但不推进句型阶段、不改 due。
     *  理由（GPT）：这是「会用一点」的证据，不是「掌握句型」的证据，不能混算。 */
    void submitSubstitute(String cid, String raw) {
        if (curPat == null) { showPatternTest(); return; }
        Coll c = null;
        for (int i = 0; i < curPat.colls.size(); i++) if (curPat.colls.get(i).id.equals(cid)) c = curPat.colls.get(i);
        if (c == null) { showPatternTest(); return; }
        String a = raw == null ? "" : raw.trim();
        Pattern.Verdict vd = Pattern.judge(a, new String[]{c.en}, new String[0]);
        long now = System.currentTimeMillis();
        Db.PatState st = db.patternState(curPat.id);
        long due = st == null ? now : st.due;
        int stage = st == null ? 0 : st.stage;
        // GPT：hint_used=1 只在作答前确实看到答案/实质提示时才成立。这里是独立作答，
        // 不能为了把它排除出掌握率而虚构提示记录；来源用 kind 区分，不冒充错误类型。
        boolean ok = db.recordPatternEvent(
                "coll-" + cid + "-" + now, curPat.id, now, a, vd.verdict, false, 0,
                null, null, stage, stage, due, due, curPat.cv, "collocation");
        if (!ok) { toast("这一句没记上，不影响句型进度"); }
        patSubIdx++;
        showPatternTest();
    }

    void submitPattern(String raw) {
        String a = raw == null ? "" : raw.trim();
        patAnswer = a;
        patVerdict = Pattern.judge(a, curPat.refs, curPat.vocab);
        patStep = 2;
        showPattern();
    }

    void skipPattern() {
        patHint = true;
        patAnswer = "";
        patVerdict = new Pattern.Verdict(Pattern.UNVERIFIED, null, null);
        patStep = 2;
        showPattern();
    }

    /** 把 Pattern 实际命中的规则翻成一句人话。没有命中规则就不要编错误。 */
    String wrongRule(String rule) {
        if (rule == null) return "表达还不完整";
        if (rule.startsWith("be_missing:")) {
            String bad = rule.substring("be_missing:".length()).trim();
            String who = bad.indexOf(' ') > 0 ? bad.substring(0, bad.indexOf(' ')) : bad;
            String be = "i".equals(who) ? "am" : ("he".equals(who) || "she".equals(who) ? "is" : "are");
            return "缺少 be 动词：这里要写 " + who + " " + be + " …";
        }
        return "表达还不完整";
    }

    void showPatternFeedback() {
        LinearLayout col = patternFrame("对照参考答案");
        int c1 = patVerdict == null ? SUB : (patVerdict.correct() ? ACCENT : 0xFFB4532A);
        String head;
        if (patVerdict == null) head = "";
        else if (patVerdict.verdict == Pattern.CORRECT) head = "表达正确，可以继续";
        else if (patVerdict.verdict == Pattern.NO_RESPONSE) head = "没作答 · 明天再来一次";
        else if (patVerdict.verdict == Pattern.UNVERIFIED) {
            // 连续两次判不了 → 进待审核，不再每天消耗额度重测
            head = patPendingNow ? "连续两次判不了 · 已在待审核，暂停这一句的正式测验"
                    : "与参考答案不同 · 系统暂时无法准确判断";
        } else head = wrongRule(patVerdict.rule);
        TextView h = text(head, 20, c1, true);
        h.setPadding(0, dp(8), 0, 0);
        col.addView(h);

        TextView mine = text("你写的：" + (patAnswer.length() == 0 ? "（没写）" : patAnswer), 14, SUB, false);
        mine.setPadding(0, dp(10), 0, 0);
        col.addView(mine);

        LinearLayout card = box(CARD, 20, BORDER);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        col.addView(card, clp);
        card.addView(text("参考答案", 12, SUB, false));
        for (String r : curPat.refs) card.addView(text(r, 16, TEXT, true));
        // GPT：missing 只做开发诊断，不给用户看——它不是「你漏了某个词」的证据。
        // error_focus 是「这个句型的常见错误」，不是「你这次犯的错」，所以措辞必须分开。
        if (patVerdict != null && patVerdict.verdict == Pattern.UNVERIFIED) {
            TextView u = text("你可以自己对照：意思对、结构不同也算过。", 13, SUB, false);
            u.setPadding(0, dp(12), 0, 0);
            card.addView(u);
        }
        if (curPat.errFocus != null && curPat.errFocus.length() > 0) {
            TextView e = text("这个句型的常见错误：" + curPat.errFocus, 12, SUB, false);
            e.setPadding(0, dp(14), 0, 0);
            card.addView(e);
        }

        col.addView(flex());
        TextView nx = text(patIdx + 1 >= patQueue.size() ? "完成" : "下一句", 16, 0xFFFFFFFF, true);
        nx.setGravity(Gravity.CENTER);
        nx.setBackground(fill(ACCENT, 16));
        nx.setTag("patnext");
        nx.setOnClickListener(this);
        col.addView(nx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(col);
    }

    /** 提交一次句型练习：写事件 + 推进调度，两者同一事务，失败整笔回滚。 */
    void commitPattern() {
        if (curPat == null || patVerdict == null) return;
        Db.PatState st = db.patternState(curPat.id);
        int stageBefore = st == null ? 0 : st.stage;
        long dueBefore = st == null ? 0 : st.due;
        int streakBefore = st == null ? 0 : st.unverifiedStreak;
        Pattern.Next nx = Pattern.next(stageBefore, patVerdict.verdict, patHint, streakBefore, curPat.kind);
        patPendingNow = nx.pendingReview;
        long now = System.currentTimeMillis();
        // dueDays==0 表示「保持原 due」：待审核句型不被人为改调度时间（GPT V12 复裁）。
        long dueAfter = nx.dueDays <= 0 ? (st == null ? now + 86400000L : st.due)
                : now + nx.dueDays * 86400000L;
        int attempts = (st == null ? 0 : st.attempts) + 1;
        int ftc = (st != null && st.firstTryCorrect >= 0) ? st.firstTryCorrect
                : (!patHint && patVerdict.verdict == Pattern.CORRECT ? 1 : 0);
        double hours = patStart > 0 ? (now - patStart) / 3600000.0 : 0;
        boolean tx = db.begin();
        boolean saved = db.savePatternState(curPat.id, nx.stage, dueAfter, now, ftc,
                patHint ? 1 : 0, patVerdict.missing, attempts, curPat.cv, nx.unverifiedStreak,
                nx.pendingReview);
        boolean logged = saved && db.recordPatternEvent(java.util.UUID.randomUUID().toString(),
                curPat.id, now, patAnswer, patVerdict.verdict, patHint, hours,
                curPat.errFocus, patVerdict.missing, stageBefore, nx.stage, dueBefore, dueAfter, curPat.cv);
        boolean ok = saved && logged;
        db.end(tx, ok);
        if (!ok) { toast("保存失败，这一句不计分"); diag("pattern-commit-rollback", curPat.id); return; }
        patDoneSession++;
        String ids = patDoneIds();
        if (!patDoneToday(curPat.id)) {
            ids = ids.length() == 0 ? curPat.id : ids + "," + curPat.id;
            getSharedPreferences("aurora", 0).edit().putString("patIds_" + dayKey(), ids).commit();
        }
        int n = getSharedPreferences("aurora", 0).getInt("patDone_" + dayKey(), 0) + 1;
        getSharedPreferences("aurora", 0).edit().putInt("patDone_" + dayKey(), n).commit();
    }

    void nextPattern() {
        commitPattern();
        patIdx++;
        patStep = 0;
        patHint = false;
        patInput = null;
        patAnswer = "";
        patVerdict = null;
        showPattern();
    }

    // ================= 待确认（P0 恢复路径） =================
    /** 待确认列表：中文情境 + 你当时写的 + 参考答案，两个动作都不推进掌握阶段。
     *  GPT 第 15 轮裁决原文：「用户认为自己说对了，不等于系统已经验证正确」，
     *  所以这里没有「我说得对就给我过」这种按钮 —— 只能清掉人工阻塞、重新进队列重练。 */
    void showPending() {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(20), dp(24), dp(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, SUB, false);
        back.setPadding(dp(6), 0, dp(16), dp(4));
        back.setTag("home");
        back.setOnClickListener(this);
        bar.addView(back);
        bar.addView(flex());
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        col.addView(text("待确认的句子", 21, TEXT, true));
        TextView hint = text("系统暂时无法判断这句话是否正确，你可以查看参考答案后重新练习。", 13, SUB, false);
        hint.setPadding(0, dp(8), 0, dp(4));
        col.addView(hint);

        List<Db.PatState> pend = db.patternPending();
        if (pend.isEmpty()) {
            col.addView(flex());
            TextView none = text("没有待确认的句子", 14, SUB, false);
            none.setGravity(Gravity.CENTER);
            col.addView(none);
            col.addView(flex());
            setContentView(col);
            return;
        }

        ScrollView sc = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(10), 0, dp(10));
        sc.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (Db.PatState st : pend) {
            Pat p = patById(st.pid);
            LinearLayout card = box(CARD, 18, BORDER);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(16), dp(18), dp(14));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(12);
            body.addView(card, clp);

            String title = p != null ? p.prompt : st.pid;
            TextView q = text(title, 15, TEXT, true);
            card.addView(q);

            String mine = db.lastPatternAnswer(st.pid);
            TextView you = text(mine.length() == 0 ? "你写的是：（没有记录到输入）" : "你写的是：" + mine,
                    13, SUB, false);
            you.setPadding(0, dp(10), 0, 0);
            card.addView(you);

            String ref = p != null && p.refs != null && p.refs.length > 0 ? p.refs[0] : "";
            if (ref.length() > 0) {
                TextView r = text("参考答案：" + ref, 13, ACCENT, false);
                r.setPadding(0, dp(4), 0, 0);
                card.addView(r);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            rlp.topMargin = dp(12);
            card.addView(row, rlp);

            TextView again = text("重新练习", 14, 0xFFFFFFFF, true);
            again.setGravity(Gravity.CENTER);
            again.setBackground(fill(ACCENT, 14));
            again.setTag("pres|" + st.pid);
            again.setOnClickListener(this);
            row.addView(again, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            TextView later = text("暂时搁置", 14, SUB, true);
            later.setGravity(Gravity.CENTER);
            later.setBackground(borderBox(BORDER, 14));
            later.setTag("pshelve|" + st.pid);
            later.setOnClickListener(this);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            llp.leftMargin = dp(10);
            row.addView(later, llp);
        }

        setContentView(col);
    }

    /** 执行待确认动作。resume=true 只清人工阻塞并放回队列，绝不写掌握证据。 */
    void reviewPatternAction(String pid, boolean resume) {
        String eid = java.util.UUID.randomUUID().toString();
        String ans = db.lastPatternAnswer(pid);
        if (!db.reviewPattern(pid, resume, System.currentTimeMillis(), eid, ans)) {
            toast("操作失败，请重试");
            diag("pattern-review-failed", pid + (resume ? " resume" : " shelve"));
            return;
        }
        toast(resume ? "已放回练习队列，之后会重新排到" : "已搁置，之后可以回来处理");
        showPending();
    }

    // ================= 事件分发 =================
    public void onClick(View v) {
        Object o = v.getTag();
        String t = (o instanceof String) ? (String) o : "";
        if (t.equals("home")) { showHome(); return; }
        if (t.equals("review")) { startReview(); return; }
        if (t.equals("new")) { startNew(); return; }
        if (t.equals("next")) { newStep++; showCard(); return; }
        if (t.equals("pat")) { startPattern(); return; }
        if (t.equals("pending")) { showPending(); return; }
        if (t.equals("patgo")) { patStep = 1; showPattern(); return; }
        if (t.equals("subgo")) { patSubIdx = 0; showPatternSubstitute(); return; }
        if (t.equals("patsub")) { submitPattern(patInput == null ? "" : patInput.getText().toString()); return; }
        if (t.equals("patskip")) { skipPattern(); return; }
        if (t.equals("patnext")) { nextPattern(); return; }
        if (t.equals("wrongnext")) { continueAfterWrong(); return; }
        String[] p = t.split("\\|");
        if (p.length < 2) return;
        if (p[0].equals("sub2go")) {
            submitSubstitute(p[1], patInput == null ? "" : patInput.getText().toString());
            return;
        }
        if (p[0].equals("pres")) { reviewPatternAction(p[1], true); return; }
        if (p[0].equals("pshelve")) { reviewPatternAction(p[1], false); return; }
        Db.Word w = db.word(p[1]);
        if (w == null) return;
        if (p[0].equals("speak")) { speak(w.w); return; }
        if (p[0].equals("show")) { revealAnswer(w); return; }
        if (p[0].equals("ans")) { submitReview(w, reviewInput == null ? "" : reviewInput.getText().toString()); return; }
        if (p[0].equals("acc")) { rate(w, 1); return; }
        if (p[0].equals("ap")) { appealReview(w); return; }
        if (p[0].equals("got")) { finishNew(w); return; }
        if (p[0].equals("screen")) { submitScreen(w); return; }
        if (p[0].equals("rate") && p.length >= 3) {
            try {
                rate(w, Integer.parseInt(p[2]));
            } catch (Exception e) {
                // P1-3：原来 catch (Exception ignored) 把一切异常吞掉——用户不知道操作失败，日志也没有痕迹
                Log.e("AURORA", "rate action failed w=" + p[1], e);
                diag("rate-action-exception", p[1] + " " + e);
                toast("评分失败，请重试");
            }
        }
    }

    public boolean onLongClick(View v) {
        Object o = v.getTag();
        if (o instanceof String && ((String) o).equals("export")) { export(); return true; }
        return false;
    }

    void revealAnswer(Db.Word w) {
        revealed = true;
        fillAnswer(curAns, w);
        curBottom.removeAllViews();
        if (curIsNew) {
            TextView btn = text("记住了 · 20 分钟后再复习", 16, 0xFFFFFFFF, true);
            btn.setGravity(Gravity.CENTER);
            btn.setBackground(fill(ACCENT, 16));
            btn.setTag("got|" + w.w);
            btn.setOnClickListener(this);
            curBottom.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        } else {
            addRateButtons(curBottom, w);
        }
    }

    void finishNew(Db.Word w) {
        // P0：重新学习（state=4）与首次学习要区分——重学仍消耗日额度，但不重复占用新词预算
        Db.Card pre = db.card(w.w);
        boolean relearn = (pre != null && pre.state == 4);
        if (!tryRecordStudyAction(!relearn)) { showHome(); return; }
        Db.Card k = db.card(w.w);
        if (k == null) {
            k = new Db.Card();
            k.w = w.w;
            k.state = 1;
            k.last = System.currentTimeMillis();
            k.due = k.last + firstDelayMin() * 60000L;
            k.reps = 0;
            k.lapses = 0;
            k.s = 0; k.d = 0; k.ivl = 0;
            if (!db.saveCard(k)) {
                // P0-1：额度已扣但卡没写进库，必须补偿并停下，不能推进下一张
                diag("reject-finishNew", k.w);
                compensateStudyAction(true, "finishNew-save-failed");
                toast("保存失败，请重新学习这张卡");
                showHome();
                return;
            }
            // 拼写线跟进（GPT 裁决：D+2 首拼）。只给前 400 高频词建拼写记录，
            // 其余词按用户要求「认得就行」。拼写记录建失败不影响认义线——两条线独立，
            // 这里故意不 return：认义卡已经进库了，因为拼写建不上就把整词判失败是错的。
            if (db.isSpellWord(w.w) && db.spellCard(w.w) == null) {
                Db.Card sk = new Db.Card();
                sk.w = w.w;
                sk.state = 1;
                sk.reps = 0;
                sk.lapses = 0;
                sk.s = 0;
                sk.d = 0;
                sk.ivl = 0;
                sk.last = System.currentTimeMillis();
                sk.due = sk.last + 2L * 24 * 60 * 60 * 1000;
                if (!db.saveSpell(sk)) {
                    diag("spell-init-failed", w.w);
                }
            }
        } else if (k.state == 4) {
            // 重新学习完成：state=4 → state=1（首测待评），due 设为 firstDelay 之后
            k.state = 1;
            // P2-01：lapses 是「历史遗忘次数」，重学不该把它清零——清零等于假装从没忘过，
            // 之后 lapses 永远偏小，FSRS 的 lapse 相关参数会一直吃不到真实信号。
            k.reps = 0;
            k.s = 0; k.d = 0; k.ivl = 0;
            k.last = System.currentTimeMillis();
            k.due = k.last + firstDelayMin() * 60000L;
            if (!db.saveCard(k)) {
                diag("reject-relearn-done", k.w);
                compensateStudyAction(false, "relearn-save-failed");
                toast("保存失败，请重新学习这张卡");
                showHome();
                return;
            }
        } else if (k.state != 1) {
            // P0-1：残留的 state=2/3 不能当成正常新卡跳过，否则会掩盖状态机错误
            diag("finishNew-bad-state", k.w + " state=" + k.state);
            toast("这张卡的状态异常，已跳过");
            showHome();
            return;
        }
        // A04：新授结束必须复位筛查状态。否则重学（screenStep=0）之后的下一个新词
        // 会直接进教学界面，白白跳过懒筛——等于给所有后续词开了免检通道。
        // P0-1：但要按队列来源分叉——复习队列里的重学完成后必须回到「复习模式」，
        // 否则 curIsNew 仍为 true，下一张 state=2 的正式复习卡会被 showCard 当成新词送去筛查，
        // 两步通过后 submitScreen 会 new 一张 state=3 直接覆盖它，原有 s/d/reps/ivl 全丢。
        boolean backToReview = returnToReview;
        returnToReview = false;
        if (backToReview) {
            curIsNew = false;
            screenStep = 0;
            screenIsPending = false;
        } else {
            screenStep = 1;
            screenIsPending = false;
        }
        alreadyCountedWord = null;
        if (doneThisSession >= CAP) { endSession(); return; }
        if (!backToReview && newToday >= newPerDay) { endNewSession(); return; }
        idx++;
        newStep = 1;
        showCard();
    }

    int firstDelayMin() {
        return clamp(getSharedPreferences("aurora", 0).getInt("first_delay", 20), 5, 1440);
    }

    // ================= 评分 =================
    void rate(Db.Word w, int rating) {
        // P1-3：调用方参数错误必须在扣额度之前拦下。原来 rating 非法会先记账、再在 Fsrs 里炸，
        // 用户白掉一次额度；而且调用方 bug 与卡片数据损坏会被同一段 catch 处理成 state=4。
        if (rating < 1 || rating > 3) {
            diag("invalid-rating", w.w + " rating=" + rating);
            toast("评分参数异常，请重新操作");
            return;
        }
        // 审查 FIX-04：豁免令牌一次即焚。原来只有匹配成功才清空，异常路径会把令牌留到后续卡片，
        // 使同一个词意外获得第二次免计数。
        boolean exempt = (alreadyCountedWord != null && alreadyCountedWord.equals(w.w));
        alreadyCountedWord = null;
        boolean counted = !exempt;   // A07：本次评分是否真的占用了一个额度
        if (counted && !tryRecordStudyAction(false)) { showHome(); return; }
        long ms = SystemClock.elapsedRealtime() - cardShownAt;
        Db.Card k = db.card(w.w);
        // P1-1（R1 复审）：rate() 必须有显式状态白名单。原来只凭 reps==0 决定能否初始化 FSRS，
        // 等于把「卡片不存在」「state=4 待重学」「state=3 未确认」全当成合法首测入口。
        // 这是底层业务不变量，不能只靠「UI 上那个按钮没出现」来保证。
        if (k == null) {
            diag("rate-no-card", w.w + " rating=" + rating);
            if (counted) compensateStudyAction(false, "rate-no-card");
            toast("这张卡的数据缺失，已跳过");
            showHome();
            return;
        }
        if (k.state == 4) {
            diag("rate-state4", w.w);
            if (counted) compensateStudyAction(false, "rate-state4");
            toast("这张卡要先重新学习一遍");
            showHome();
            return;
        }
        if (k.state == 3 && !exempt) {
            // state=3 只允许在「次日确认通过」那次评分里初始化：那一刻 UI 会给这个词发豁免令牌
            diag("rate-state3-no-token", w.w + " reps=" + k.reps);
            if (counted) compensateStudyAction(false, "rate-state3-no-token");
            toast("这张卡要先完成次日确认");
            showHome();
            return;
        }
        if (k.state != 1 && k.state != 2 && k.state != 3) {
            diag("rate-bad-state", w.w + " state=" + k.state);
            if (counted) compensateStudyAction(false, "rate-bad-state");
            toast("这张卡的状态异常，已跳过");
            showHome();
            return;
        }
        // 到这里 k 一定非 null：state=1 首测 / state=2 复习 / state=3+令牌 次日确认
        boolean isNewCard = (k.reps == 0);
        // 数据完整性保护：reps>0 但 s<=0 属损坏卡，不能带进 next()（否则首次稳定性又被放大）
        // 采取软重置而非抛异常：单人自用场景宁可自愈，也不要一次脏数据让 App 崩在评分中途
        if (!isNewCard && k.s <= 0) {
            // P1-2：损坏卡只能进 state=4（待重新学习），绝不能借本次评分顺手初始化 FSRS——
            // 那等于拿一张不知道坏在哪的卡，用用户这一次的按键重新编造稳定性。
            Log.e("AURORA", "corrupt card reps=" + k.reps + " s=" + k.s + " w=" + w.w + " -> state=4");
            diag("corrupt-reset", "w=" + w.w + " reps=" + k.reps + " s=" + k.s + " d=" + k.d);
            k.state = 4;
            k.s = 0;
            k.d = 0;
            k.ivl = 0;
            k.reps = 0;
            k.last = 0;
            k.due = System.currentTimeMillis();
            if (!db.saveCard(k)) {   // lapses 保留历史值，不清零
                diag("reject-corrupt", w.w);
                if (counted) compensateStudyAction(false, "corrupt-save-failed");
                toast("数据修复失败，请重试");
                showHome();
                return;
            }
            if (counted) compensateStudyAction(false, "corrupt-card-reset");
            toast("这张卡的数据异常，已退回重新学习");
            showHome();
            return;
        }
        double prevS = k.s, prevIvl = k.ivl, prevD = k.d;
        int prevState = k.state;
        // 账本：days 与 r 必须提到方法级——review_event 要记「评分那一刻的 R」，
        // 否则事后无法回放 FSRS 决策，调参就是盲调。
        double days = 0.0, r = 0.0;
        double[] nx;
        try {
            if (isNewCard) {
                double s0 = Fsrs.initStability(rating);
                double d0 = Fsrs.initDifficulty(rating);
                nx = new double[]{s0, d0, Fsrs.interval(s0, Fsrs.TARGET_R)};
            } else {
                days = Math.max(0, (System.currentTimeMillis() - k.last) / 86400000.0);
                r = Fsrs.retrievability(days, k.s);
                nx = Fsrs.next(k.s, k.d, r, rating);
            }
        } catch (IllegalArgumentException bad) {
            // A09/A10：Fsrs 底层拒绝非法状态后，这里必须显式接住，绝不能崩在评分中途。
            // 单人自用场景宁可自愈：记下原始参数 -> 软重置为待首测 -> 让用户重学一次。
            diag("fsrs-reject", w.w + " " + bad.getMessage());
            Log.e("AURORA", "fsrs reject w=" + w.w + " msg=" + bad.getMessage());
            Db.Card fix = db.card(w.w);
            if (fix == null) { fix = new Db.Card(); fix.w = w.w; }
            fix.state = 4;            // 回完整新授，不留在可评分的 state=1/2
            fix.s = 0; fix.d = 0; fix.ivl = 0; fix.reps = 0;
            fix.last = 0; fix.due = System.currentTimeMillis();
            if (!db.saveCard(fix)) diag("reject-fsrs-reset", w.w);
            if (counted) compensateStudyAction(false, "fsrs-reject");
            toast("这张卡的数据异常，已退回重新学习");
            showHome();
            return;
        }
        k.s = nx[0];
        k.d = nx[1];
        k.ivl = nx[2];
        k.state = 2;
        k.reps++;
        if (rating == 1 && !isNewCard) k.lapses++;
        k.last = System.currentTimeMillis();
        k.due = k.last + (long) (nx[2] * 86400000L);
        // 账本：一次评分一条原始事件。先算出 cardSaved，只有真正落库的评分才进账本，
        // 否则会出现「账本里有、卡片没变」的假记录，调参时会污染样本。
        // GPT 复裁：卡片更新 + 账本事件 + 同日封口必须在同一个 SQLite 事务里。
        // 任何一步失败都整体回滚——「卡片变了但没有账本/封口」比不评分危险得多。
        boolean tx = db.begin();
        boolean cardSaved = db.saveCard(k);
        boolean sealed = false;
        if (cardSaved) {
            if (curSid != null) {
                boolean okEv = db.recordReview(curSid, w.w, "recognition",
                        rating, System.currentTimeMillis(), ms, days, r, prevS, k.s, prevD, k.d,
                        prevIvl, k.ivl, prevState, k.state, isNewCard, revealed ? 1 : 0,
                        firstVerdict, firstAnswer, firstSense);
                if (!okEv) diag("event-failed", w.w);
            }
            sealed = db.sealRetrieval(w.w, dayKey(), "recognition", k.last, curSid);
        }
        // 跨存储一致性（审查要求）：卡片没写进库，就不能记日志、不能推进、不能留下已扣额度的错觉
        if (!cardSaved) {
            db.end(tx, false);
            diag("reject-rate", w.w + " rating=" + rating + " s=" + k.s + " ivl=" + k.ivl);
            // 审查 FIX-02：原来只减内存变量，持久化的 done_yyyyMMdd 仍被占用 -> 重启后额度白丢。
            // A07：豁免评分根本没扣额度，退款会凭空多出一次可用次数 -> 只在 counted 时补偿。
            if (counted) compensateStudyAction(false, "rate-save-failed");
            else diag("skip-compensate", w.w + " exempt rate failed, no quota consumed");
            toast("保存失败，这张卡先不算");
            showCard();
            return;
        }
        // 封口失败 = 这个 (词, 日) 已经被另一条通道封过，本次是重复评分 → 整笔回滚。
        // GPT 原话：「CONFLICT_IGNORE 不能默默容忍同词同日第二次评分……应回滚」。
        if (cardSaved && !sealed) {
            db.end(tx, false);
            diag("seal-duplicate-rollback", w.w + " recognition");
            toast("这个词今天已经测过了，这次不计分");
            idx++;
            newStep = 1;
            showCard();
            return;
        }
        boolean logged = db.log(w.w, "recognition", rating, ms, prevIvl, k.ivl, prevS, k.s);
        db.end(tx, true);
        if (!logged) {
            // A13：卡片已落库，不回滚（回滚会让用户丢掉刚完成的复习）；
            // 但必须在 diag 里留下断档痕迹，否则 revlog 缺口会被误当成「没学」。
            diag("log-failed", w.w + " rating=" + rating + " ivl=" + k.ivl);
        }

        if (doneThisSession >= CAP) { endSession(); return; }
        idx++;
        newStep = 1;
        showCard();
    }

    /** GPT 复裁 Q6：D0 走完新授（state=1, reps=0）之后，当天不得再进正式复习队列。
     *  理由：「否则新授完成后几小时又被当作到期复习测试」。封口表按自然日跨天，挡不住当天；
     *  所以这里按「新授完成时间是否落在今天」过滤，不改 due、不改 state。 */
    boolean isTaughtToday(Db.Card k) {
        if (k == null) return false;
        if (k.state != 1 || k.reps != 0) return false;
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        a.setTimeInMillis(k.last);
        a.set(java.util.Calendar.HOUR_OF_DAY, 0);
        a.set(java.util.Calendar.MINUTE, 0);
        a.set(java.util.Calendar.SECOND, 0);
        a.set(java.util.Calendar.MILLISECOND, 0);
        b.setTimeInMillis(System.currentTimeMillis());
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    void endNewSession() {
        closeSession();
        toast("今天的 " + newToday + " 个新词过完了，明天再确认一遍");
        showHome();
    }

    void endSession() {
        closeSession();
        toast(doneThisSession > 0 ? "今天已经学过了" : "今天先到这里，明天继续");
        showHome();
    }

    /** 账本：开一个学习会话。重复调用不会丢掉已开的会话（同一会话内切屏不重开）。 */
    void openSession(String track) {
        if (curSid != null) return;
        curSid = java.util.UUID.randomUUID().toString();
        sessionStartedAt = SystemClock.elapsedRealtime();
        db.beginSession(curSid, track, CAP, System.currentTimeMillis());
    }

    /** 账本：收会话写汇总。失败只记诊断，不阻塞界面——数据丢失比学习中断轻。 */
    void closeSession() {
        if (curSid == null) return;
        long active = SystemClock.elapsedRealtime() - sessionStartedAt;
        boolean okEnd = db.endSession(curSid, System.currentTimeMillis(),
                doneThisSession, newToday, 0, active);
        if (!okEnd) diag("session-end-failed", curSid);
        curSid = null;
        sessionStartedAt = 0L;
        // 数据上云：会话一结束就补传。失败留在 pendingSids 队列里，下次结束再试，
        // 绝不在学习中途弹网络错误——丢数据比中断学习轻。
        syncPending();
    }

    void syncPending() {
        String base = getSharedPreferences("aurora", 0).getString("sync_url", DEFAULT_SYNC_URL);
        if (base == null || base.length() < 8) return;
        java.util.List<String> sids = db.pendingSids();
        if (sids.isEmpty()) return;
        new Thread(new SyncTask(db, base, sids)).start();
    }

    void export() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "aurora-export.json");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(db.exportJson().getBytes("UTF-8"));
            fo.close();
            toast("已导出到 " + f.getAbsolutePath());
        } catch (Exception e) {
            toast("导出失败：" + e);
        }
    }

    // ================= 小工具 =================
    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(CANVAS);
        return l;
    }

    View flex() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(t.getTypeface(), Typeface.BOLD));
        t.setIncludeFontPadding(false);
        return t;
    }

    LinearLayout box(int color, int radius, int stroke) {
        LinearLayout l = new LinearLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(1, stroke);
        l.setBackground(g);
        return l;
    }

    GradientDrawable fill(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius >= 99 ? dp(50) : dp(radius));
        return g;
    }

    GradientDrawable borderBox(int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(radius));
        g.setStroke(1, stroke);
        return g;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    String fmtDate() {
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date());
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void onBackPressed() {
        showHome();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
