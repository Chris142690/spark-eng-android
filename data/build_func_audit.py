# -*- coding: utf-8 -*-
"""功能词体检表：把 GPT 第 9 轮要的「不擅自补词」变成可核查清单。"""
import io, json, os

ROOT = r'D:\手机\re\ccg'
ASSETS = os.path.join(ROOT, r'android\assets')
words = json.load(io.open(os.path.join(ASSETS, 'words.json'), encoding='utf-8'))
words = words['words'] if isinstance(words, dict) else words
by_w = {w['w'].lower(): (i, w) for i, w in enumerate(words)}

FUNC = [
    ('be', 'f-be'), ('am', 'f-be'), ('is', 'f-be'), ('are', 'f-be'),
    ('was', 'f-be'), ('were', 'f-be'),
    ('have', 'f-have'), ('has', 'f-have'), ('had', 'f-have'),
    ('do', 'f-do'), ('does', 'f-do'), ('did', 'f-do'),
    ('not', 'f-do'), ('meet', '场景词'), ('some', '场景词'), ('here', '场景词'),
]

lines = [u'# 功能词体检表',
         u'',
         u'来源：`android/assets/words.json`（1,208 词，tag 全为 core，无 723/484 分层）',
         u'',
         u'| 词 | 在 1208 词库 | 词频序号 | 归属处理 | 说明 |',
         u'|---|---|---|---|---|']
missing = 0
for w, owner in FUNC:
    hit = by_w.get(w)
    if hit:
        idx, rec = hit
        lines.append(u'| %s | Y | %d | %s | %s / %s |' % (w, idx, owner, rec.get('pos', ''), rec.get('cn', '')))
    else:
        missing += 1
        lines.append(u'| %s | N | - | %s | 词库缺失，走句型线专项教学，不新建词卡 |' % (w, owner))

lines += [u'',
          u'## 结论',
          u'- 缺失 %d / %d 个功能词，全部不入 1208 词库、不建 FSRS 卡。' % (missing, len(FUNC)),
          u'- be/am/is/are/was/were → `f-be` 单元；have/has/had → `f-have`；do/does/did/not → `f-do`。',
          u'- meet/some/here 归场景表达，出现在搭配里时可看可用，不强制背。',
          u'- 1208 词库定位为「筛选词库」，不因其缺功能词而拒用，也不擅自补齐（GPT 第 9 轮裁定）。']

p = os.path.join(ROOT, r'data\function-words-audit.md')
io.open(p, 'w', encoding='utf-8').write(u'\n'.join(lines) + u'\n')
print('missing', missing, 'of', len(FUNC), '->', p)
