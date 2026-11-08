# -*- coding: utf-8 -*-
"""L2 场景表达提取 v3：加繁简转换 + 难度过滤。

生词判据：不在 1208 词表 且 frq > 3000
难度判据：句中所有实词 frq <= 2500（除目标词本身），且句长 4-8 词
繁简：zhconv.convert(cn, 'zh-cn')
"""
import io, json, re, os, csv
from collections import defaultdict
import zhconv

BASE = r'D:\手机\re\ccg'
EN_TSV = os.path.join(BASE, 'data', 'eng_sentences.tsv')
CN_TSV = os.path.join(BASE, 'data', 'cmn_sentences.tsv')
LINK_TSV = os.path.join(BASE, 'data', 'cmn-eng_links.tsv')
ECDICT = os.path.join(BASE, 'data', 'ecdict.csv')
WORDS = os.path.join(BASE, 'words.json')
OUT = os.path.join(BASE, 'data', 'l2.json')

SCENE_KW = {
    'travel': {'travel','trip','hotel','airport','ticket','passport','luggage','flight','train','bus',
               'station','abroad','visa','tourist','journey','map','taxi','room'},
    'business': {'price','order','customer','payment','pay','company','market','sale','sell','buy',
                 'product','service','contract','cost','money','business','client','deal','offer','bill'},
    'work': {'work','job','office','meeting','boss','colleague','project','team','task','report',
             'email','schedule','deadline','overtime','staff','manager'},
    'daily': {'home','house','family','friend','eat','food','sleep','morning','night','water',
              'school','child','mother','father','brother','sister','dinner','breakfast','happy',
              'tired','busy','help','talk','walk','run','sit','open','close'},
}
SCENE_ORDER = ['daily', 'travel', 'work', 'business', 'other']
STOP = set('the a an is am are was were be been being do does did have has had will would can could '
           'should may might must shall i you he she it we they me him her us them my your his its our '
           'their this that these those and or but so if because when where how what who why not no yes '
           'very too also just only from to of in on at by for with about into over under up down out '
           'off there here now then some any many much more most one two three'.split())


def main():
    ws = json.load(io.open(WORDS, encoding='utf-8'))
    words = [x['w'].lower() for x in ws]
    wset = set(words)
    print('目标词', len(words))

    frq = {}
    with io.open(ECDICT, encoding='utf-8', newline='') as f:
        for row in csv.DictReader(f):
            w = row['word'].lower()
            if w in frq:
                continue
            try:
                frq[w] = int(row['frq'] or 0)
            except ValueError:
                frq[w] = 0
    print('词频表', len(frq))

    def fq(tok):
        return frq.get(tok, 0)

    def is_new(tok):
        if tok in wset:
            return False
        f = fq(tok)
        return f > 3000 if f else False

    def too_hard(tok):
        """实词但词频 > 2500 -> 太难"""
        if tok in STOP or len(tok) <= 2:
            return False
        f = fq(tok)
        return bool(f) and f > 2500

    def load_sent(path):
        d = {}
        with io.open(path, encoding='utf-8') as f:
            for line in f:
                p = line.rstrip('\n').split('\t')
                if len(p) >= 3:
                    d[p[0]] = p[2]
        return d

    eng = load_sent(EN_TSV)
    cmn = load_sent(CN_TSV)
    pairs = []
    with io.open(LINK_TSV, encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) == 2 and p[0] in cmn and p[1] in eng:
                pairs.append((eng[p[1]], cmn[p[0]]))
    print('中英句对', len(pairs))

    cand = defaultdict(list)
    dropped = defaultdict(int)
    for en, cn in pairs:
        toks = re.findall(r"[A-Za-z']+", en)
        n = len(toks)
        if n < 4 or n > 8:
            dropped['len'] += 1
            continue
        low = [t.lower() for t in toks]
        hitw = set(low) & wset
        if not hitw:
            dropped['noword'] += 1
            continue
        if sum(1 for t in low if is_new(t)) > 0:
            dropped['new'] += 1
            continue
        hard = [t for t in low if too_hard(t)]
        if hard:
            dropped['hard'] += 1
            continue
        cn = zhconv.convert(cn, 'zh-cn')
        joined = ' ' + en.lower() + ' '
        scene = 'other'
        for sc in ['travel', 'business', 'work', 'daily']:
            if any(k in low or (' ' + k + ' ') in joined for k in SCENE_KW[sc]):
                scene = sc
                break
        for w in hitw:
            if len(cand[w]) < 20:
                cand[w].append({'en': en, 'cn': cn, 'scene': scene, 'n': n})

    out = {}
    stats = defaultdict(int)
    for w, lst in cand.items():
        lst.sort(key=lambda x: (SCENE_ORDER.index(x['scene']), x['n']))
        seen, picked = set(), []
        for x in lst:
            if x['en'] in seen:
                continue
            seen.add(x['en'])
            picked.append({'en': x['en'], 'cn': x['cn']})
            if len(picked) >= 4:
                break
        out[w] = {'scene': lst[0]['scene'], 'ex': picked}
        stats[lst[0]['scene']] += 1

    io.open(OUT, 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=1))
    total = sum(len(v['ex']) for v in out.values())
    print(f'覆盖 {len(out)}/{len(words)} 词，共 {total} 条，均 {total/max(1,len(out)):.2f} 条/词')
    print('场景', dict(stats), '丢弃', dict(dropped))
    print('->', OUT)


if __name__ == '__main__':
    main()
