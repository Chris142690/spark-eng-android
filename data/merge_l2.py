# -*- coding: utf-8 -*-
"""把 L2 真实语料例句合并进 words.json，替换 ECDICT 拼接的例句。

- 优先取 L2 中该词最短（词数最少）的一条
- 原例句备份到 ex0 / excn0，便于回滚与对比
- 没有 L2 例句的词保持原样
"""
import io, json, os, shutil

BASE = r'D:\手机\re\ccg'
WORDS = os.path.join(BASE, 'words.json')
L2 = os.path.join(BASE, 'data', 'l2.json')
BAK = os.path.join(BASE, 'words.json.bak_l2')


def main():
    if not os.path.exists(BAK):
        shutil.copy2(WORDS, BAK)
        print('备份 ->', BAK)

    ws = json.load(io.open(WORDS, encoding='utf-8'))
    l2 = json.load(io.open(L2, encoding='utf-8'))

    replaced = 0
    kept = 0
    for it in ws:
        w = it['w'].lower()
        exs = l2.get(w, {}).get('ex') or []
        if not exs:
            kept += 1
            continue
        best = min(exs, key=lambda x: len(x['en'].split()))
        if not it.get('ex0'):
            it['ex0'] = it.get('ex', '')
            it['excn0'] = it.get('excn', '')
        it['ex'] = best['en']
        it['excn'] = best['cn']
        replaced += 1

    io.open(WORDS, 'w', encoding='utf-8').write(
        json.dumps(ws, ensure_ascii=False, separators=(',', ':')))
    print(f'替换 {replaced} 词，保留 {kept} 词，共 {len(ws)}')
    print('新文件大小', os.path.getsize(WORDS), 'B')


if __name__ == '__main__':
    main()
