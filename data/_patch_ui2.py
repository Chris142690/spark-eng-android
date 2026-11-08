# -*- coding: utf-8 -*-
import io

p = r'D:\手机\re\ccg\android\src\com\aurora\word\MainActivity.java'
s = io.open(p, encoding='utf-8').read()
orig = s


def rep(old, new, tag):
    global s
    n = s.count(old)
    if n != 1:
        print('!! %s matched %d times' % (tag, n))
        return False
    s = s.replace(old, new)
    print('ok %s' % tag)
    return True


# 1) 调用点
rep('        addIpa(body, w);', '        addIpaLine(body, w);', 'call')

# 2) 音标行：去胶囊框，词性同行
old_ipa = '''    void addIpa(LinearLayout parent, Db.Word w) {
        if (w.ipa == null || w.ipa.length() == 0) return;
        TextView ipa = text(w.ipa, 15, SUB, false);
        ipa.setGravity(Gravity.CENTER);
        ipa.setBackground(borderBox(BORDER, 99));
        ipa.setPadding(dp(12), dp(5), dp(12), dp(5));
        ipa.setTag("speak|" + w.w);
        ipa.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(ipa, lp);
    }'''
new_ipa = '''    void addIpaLine(LinearLayout parent, Db.Word w) {
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
        if (hasIpa) {
            TextView ipa = text(w.ipa, 16, SUB, false);
            ipa.setGravity(Gravity.CENTER);
            ipa.setTag("speak|" + w.w);
            ipa.setOnClickListener(this);
            line.addView(ipa);
        }
        if (hasIpa && hasPos) {
            TextView gap = text("\\u00b7", 14, BORDER, false);
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
    }'''
rep(old_ipa, new_ipa, 'addIpaLine')

# 3) fillAnswer 不再单独画词性
rep('''        addPos(ans, w);
        addCn(ans, w);''', '''        addCn(ans, w);''', 'fillAnswer')

# 4) 删掉 addPos 方法（保留空实现的调用安全）
old_pos = '''    void addPos(LinearLayout ans, Db.Word w) {
        if (w.pos == null || w.pos.length() == 0) return;
        TextView pos = text(w.pos, 13, SUB, false);
        pos.setGravity(Gravity.CENTER);
        pos.setPadding(0, dp(8), 0, 0);
        ans.addView(pos);
    }'''
rep(old_pos, '''    @SuppressWarnings("unused")
    void addPos(LinearLayout ans, Db.Word w) {
    }''', 'addPos')

# 5) 例句：左侧细线 + 左对齐
old_ex = '''    void addExample(LinearLayout ans, Db.Word w) {
        if (w.ex != null && w.ex.length() > 0) {
            TextView ex = text(w.ex, 17, TEXT, false);
            ex.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            ex.setGravity(Gravity.CENTER);
            ex.setPadding(0, dp(18), 0, 0);
            ex.setTag("say|" + w.ex);
            ex.setOnClickListener(this);
            ans.addView(ex);
        }
        if (w.excn != null && w.excn.length() > 0) {
            TextView tr = text(w.excn, 14, SUB, false);
            tr.setGravity(Gravity.CENTER);
            tr.setPadding(0, dp(6), 0, 0);
            ans.addView(tr);
        }
    }'''
new_ex = '''    void addExample(LinearLayout ans, Db.Word w) {
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
    }'''
rep(old_ex, new_ex, 'addExample')

# 6) 内容整体下移
rep('        body.setGravity(Gravity.CENTER);',
    '        body.setGravity(Gravity.CENTER);\n        body.setPadding(0, dp(46), 0, 0);', 'body-pad')

# 7) 释义上间距 12 -> 16
rep('        cn.setPadding(0, dp(12), 0, 0);', '        cn.setPadding(0, dp(16), 0, 0);', 'cn-pad')

io.open(p, 'w', encoding='utf-8').write(s)
print('bytes %d -> %d' % (len(orig.encode()), len(s.encode())))
