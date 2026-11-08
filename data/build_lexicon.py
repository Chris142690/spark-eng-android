# -*- coding: utf-8 -*-
"""从 ECDICT 筛 A2 核心词表（zk 中考词为主，gk 高频补足），配 Tatoeba 中英例句，输出 words.json"""
import csv, json, os, re, sys, collections

HERE = os.path.dirname(os.path.abspath(__file__))
csv.field_size_limit(10 ** 7)

TARGET = 1500

STOP = set('''a an the be am is are was were been being do does did done have has had having
and or but if so because although though while whereas
of in on at to for with from by about into onto over under above below between among during
through throughout within without against toward towards upon across along around behind beyond
it its this that these those there here he she they we you i me him her them us my your his their our mine yours
who whom whose which what when where why how
not no yes nor neither either both each every all any some few many much more most less least
other another such own same very too also only just even still yet already ever never always
can could may might must shall should will would
than then as such via per
s t re ve ll d m o clock
'''.split())

# 繁体专有字，命中即拒（避免给简体用户配繁体中文）
TRAD = set('們個這來時說麼為國學開過對無車東馬鳥魚島點電聽讀寫話語詞認識記練習題經濟業員風飛機聲價錢買賣歸還給與從會後髮發鬥幾廣廠產業條務動勞勢區醫藥歷際標準確據處辦聯繫號碼傳單資訊網絡軟體硬體資料庫程式設計師還讓嗎沒關係覺得應該現颱灣萬歲鄉親愛麗絲')


def norm_ipa(s):
    s = (s or '').strip().strip('/[]')
    for a, b in (('ә', 'ə'), ("'", 'ˈ'), (':', 'ː'), ('ɡ', 'g'), (' ', '')):
        s = s.replace(a, b)
    return '/' + s + '/'


def norm_cn(t):
    t = (t or '').replace('\\n', '\n').replace('\r', '')
    out = []
    for line in t.split('\n'):
        line = re.sub(r'\[[^\]]*\]', '', line).strip()
        if not line:
            continue
        line = re.sub(r'^\s*(a|n|v|vt|vi|ad|adv|prep|conj|pron|num|art|int|aux|abbr)\.\s*', '', line, flags=re.I)
        # 去掉计算机/经济类义项：出现「总线」「计算机」「[经]」等线索的整行跳过
        if re.search(r'总线|计算机|数据库|编程|经济|会计|法律|化学|物理|生物|医学|数学|统计', line):
            continue
        for part in re.split(r'[,;，；]', line):
            part = part.strip(' .。')
            if part and len(part) <= 12 and part not in out:
                out.append(part)
        if len(out) >= 3:
            break
    return '；'.join(out[:3])[:40]


def get_pos(t):
    m = re.match(r'^\s*(a|n|v|vt|vi|ad|adv|prep|conj|pron|num|art|aux)\.', (t or '').replace('\\n', '\n').strip(), re.I)
    if not m:
        return ''
    p = m.group(1).lower()
    return {'a': 'adj.', 'ad': 'adv.', 'adv': 'adv.', 'n': 'n.', 'v': 'v.', 'vt': 'vt.', 'vi': 'vi.',
            'prep': 'prep.', 'conj': 'conj.', 'pron': 'pron.', 'num': 'num.', 'art': 'art.', 'aux': 'aux.'}.get(p, p + '.')


def load_ecdict():
    rows = {}
    with open(os.path.join(HERE, 'ecdict.csv'), encoding='utf-8', newline='') as f:
        for r in csv.DictReader(f):
            w = r['word']
            if not re.fullmatch(r'[a-z]{2,16}', w):
                continue
            tag = r['tag'] or ''
            if 'zk' not in tag and 'gk' not in tag:
                continue
            if w in STOP:
                continue
            if not r['phonetic'] or not r['translation']:
                continue
            try:
                frq = int(r['frq'])
            except Exception:
                continue
            if frq <= 0:
                continue
            cn = norm_cn(r['translation'])
            if not cn:
                continue
            cand = {'w': w, 'frq': frq, 'ipa': norm_ipa(r['phonetic']), 'cn': cn,
                    'pos': get_pos(r['translation']), 'pri': 0 if 'zk' in tag else 1}
            if w not in rows or cand['pri'] < rows[w]['pri']:
                rows[w] = cand
    # 中考词全收，高考词按优先级+词频补足
    zk = sorted([v for v in rows.values() if v['pri'] == 0], key=lambda x: x['frq'])
    gk = sorted([v for v in rows.values() if v['pri'] == 1], key=lambda x: x['frq'])
    return zk, gk


def load_pairs():
    eng, cmn = {}, {}
    with open(os.path.join(HERE, 'eng_sentences.tsv'), encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 3 and p[1] == 'eng':
                eng[p[0]] = p[2]
    with open(os.path.join(HERE, 'cmn_sentences.tsv'), encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 3 and p[1] == 'cmn':
                cmn[p[0]] = p[2]
    out = []
    with open(os.path.join(HERE, 'cmn-eng_links.tsv'), encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 2:
                continue
            a, b = p[0], p[1]
            if a in cmn and b in eng:
                out.append((eng[b], cmn[a]))
            elif b in cmn and a in eng:
                out.append((eng[a], cmn[b]))
    print('句对', len(out), file=sys.stderr)
    return out


import os as _os, sys as _sys
_sys.path.insert(0, _os.path.dirname(_os.path.abspath(__file__)))
from colloquial import BAD_EN, FORMAL, CONTRACT, colloquial  # noqa: E402


def t2s(s):
    try:
        import zhconv
        return zhconv.convert(s, 'zh-cn')
    except Exception:
        return s


def ok_cn(s):
    if any(ch in TRAD for ch in s):
        return False
    if re.search(r'俺|嘞|啥|咋|甭|嘛呢|呗', s):
        return False
    return len(s) >= 3


def build_examples(words, pairs):
    index = collections.defaultdict(list)
    for en, cn in pairs:
        n = len(en.split())
        if n < 4 or n > 11 or not en[0].isupper() or BAD_EN.search(en) or not ok_cn(cn):
            continue
        for tok in set(re.findall(r"[a-zA-Z']+", en.lower())):
            index[tok].append((n, en, cn))
    hit = 0
    for w in words:
        cand = index.get(w['w'])
        if not cand:
            w['ex'], w['excn'] = '', ''
            continue
        def score(t):
            n, en, cn = t
            return (1 if FORMAL.search(en) else 0,
                    0 if CONTRACT.search(en) else 1,
                    abs(n - 7), len(en))
        cand.sort(key=score)
        w['ex'], w['excn'] = colloquial(cand[0][1]), t2s(cand[0][2])
        hit += 1
    return hit


def main():
    zk, gk = load_ecdict()
    print('中考可用', len(zk), '高考可用', len(gk))
    words = zk[:]
    need = TARGET - len(words)
    if need > 0:
        words += gk[:need]
    pairs = load_pairs()
    hit = build_examples(words, pairs)
    print('有例句', hit, '/', len(words), '%.1f%%' % (100.0 * hit / len(words)))
    words = [w for w in words if w['ex']]
    print('最终（去掉无例句）', len(words))

    fp = os.path.join(os.path.dirname(HERE), 'words.json')
    old = []
    if os.path.exists(fp):
        old = json.load(open(fp, encoding='utf-8'))
    have = {w['w'] for w in words}
    extra = [{'id': o['w'], 'w': o['w'], 'ipa': o.get('ipa', ''), 'pos': o.get('pos', ''),
              'cn': o.get('cn', ''), 'ex': o.get('ex', ''), 'excn': t2s(o.get('excn', '')),
              'tag': 'core', 'frq': 999999}
             for o in old if o.get('ex') and o['w'] not in have and o['w'] not in STOP
             and not any(ch in TRAD for ch in t2s(o.get('excn', '')))]
    out = []
    for w in words:
        out.append({'id': w['w'], 'w': w['w'], 'ipa': w['ipa'], 'pos': w['pos'],
                    'cn': w['cn'], 'ex': w['ex'], 'excn': w['excn'], 'tag': 'core', 'frq': w['frq']})
    out = out[:TARGET] + extra
    json.dump(out, open(fp, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print('写出', len(out), '条 ->', fp)


if __name__ == '__main__':
    main()
