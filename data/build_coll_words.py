# -*- coding: utf-8 -*-
"""搭配引入词清单（GPT reply-12 要求）。
word / lemma / in1208 / in400 / count / scenarios / priority / spell / reason
"""
import io, json, os, collections

ROOT = r'D:\手机\re\ccg'
ASSETS = os.path.join(ROOT, r'android\assets')

words = json.load(io.open(os.path.join(ASSETS, 'words.json'), encoding='utf-8'))
words = words['words'] if isinstance(words, dict) else words
by_w = {}
for i, w in enumerate(words):
    by_w[w['w'].lower()] = (i, w)

forms = json.load(io.open(os.path.join(ASSETS, 'forms.json'), encoding='utf-8'))
# forms: {"word": ["inflected", ...]} 或反查
rev = {}
for base, lst in forms.items():
    for f in lst:
        rev.setdefault(f.lower(), base.lower())

colls = json.load(io.open(os.path.join(ASSETS, 'collocations.json'), encoding='utf-8'))
colls = colls['collocations'] if isinstance(colls, dict) else colls

FUNC = {'be', 'am', 'is', 'are', 'was', 'were', 'have', 'has', 'had', 'do', 'does', 'did',
        'a', 'an', 'the', 'to', 'of', 'in', 'on', 'at', 'for', 'with', 'as', 'per', 'per night'}

cnt = collections.Counter()
scen = collections.defaultdict(set)
spell_src = {}

for c in colls:
    sc = c.get('scenario', '?')
    for field in ('required_vocab', 'new_vocab'):
        for v in (c.get(field) or []):
            v = v.strip().lower()
            if not v:
                continue
            cnt[v] += 1
            scen[v].add(sc)
            spell_src.setdefault(v, c['collocation_id'])

def lemma_of(v):
    if v in by_w:
        return v, True
    if v in rev and rev[v] in by_w:
        return rev[v], False
    if v.endswith('ing') and v[:-3] in by_w:
        return v[:-3], False
    if v.endswith('s') and v[:-1] in by_w:
        return v[:-1], False
    return v, False

rows = []
for v, n in cnt.most_common():
    lm, exact = lemma_of(v)
    in1208 = lm in by_w
    idx = by_w[lm][0] if in1208 else -1
    in400 = 0 <= idx < 400
    isfunc = lm in FUNC or v in FUNC
    # 识别优先级：功能词/高频场景词优先
    if isfunc:
        prio = 'P0-功能词(专项教学,不进FSRS)'
    elif in400:
        prio = 'P1-已在拼写白名单'
    elif in1208:
        prio = 'P2-词库内,按需引入'
    else:
        prio = 'P3-词库外,先做场景词不建卡'
    rows.append({
        'word': v,
        'lemma': lm,
        'inflected': ('屈折/变体→原形' if lm != v else ''),
        'in1208': 'Y' if in1208 else 'N',
        'in400': 'Y' if in400 else 'N',
        'count': n,
        'scenarios': ','.join(sorted(scen[v])),
        'priority': prio,
        'spell': 'Y' if in400 else 'N',
        'src': spell_src[v],
    })

out_md = [u'# 搭配引入词清单（GPT reply-12 要求）',
          u'',
          u'字段：word / lemma / 是否屈折 / 在1208词库 / 在400拼写白名单 / 出现次数 / 场景 / 识别优先级 / 拼写建议 / 首次出现',
          u'',
          u'| word | lemma | 变体 | 1208 | 400 | 次数 | 场景 | 优先级 | 拼写 | 来源 |',
          u'|---|---|---|---|---|---|---|---|---|---|']
for r in rows:
    out_md.append(u'| %s | %s | %s | %s | %s | %d | %s | %s | %s | %s |' % (
        r['word'], r['lemma'], r['inflected'], r['in1208'], r['in400'], r['count'],
        r['scenarios'], r['priority'], r['spell'], r['src']))

out_md.append(u'')
out_md.append(u'## 结论')
p0 = [r['word'] for r in rows if r['priority'].startswith('P0')]
p3 = [r['word'] for r in rows if r['priority'].startswith('P3')]
out_md.append(u'- 功能词（P0）：%s —— 走句型线专项教学，不进词汇 FSRS、不占 50 动作额度。' % ('、'.join(p0) if p0 else '无'))
out_md.append(u'- 词库外（P3）：%s —— 先做场景表达里可看可用的词，不建独立卡、不强制背。' % ('、'.join(p3) if p3 else '无'))
out_md.append(u'- 屈折形式已归并到原形，不新增词条（working→work、booking→book 等）。')

path = os.path.join(ROOT, r'data\collocation-words.md')
io.open(path, 'w', encoding='utf-8').write(u'\n'.join(out_md) + u'\n')
print('rows', len(rows), 'p0', len(p0), 'p3', len(p3))
print('->', path)
