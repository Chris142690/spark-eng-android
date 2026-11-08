# -*- coding: utf-8 -*-
"""为 words.json 生成音节切点字段 syl。

pyphen 用 TeX 断词规则，不完全是语言学音节，但对「按音节拼读」足够。
输出用 `·` 连接，例如 beautiful -> beau·ti·ful。
"""
import io, json, os
import pyphen

BASE = r'D:\手机\re\ccg'
WORDS = os.path.join(BASE, 'words.json')


def main():
    dic = pyphen.Pyphen(lang='en_US')
    ws = json.load(io.open(WORDS, encoding='utf-8'))
    n_ok = 0
    for it in ws:
        w = it['w']
        if not w.isalpha():
            it['syl'] = w
            continue
        parts = dic.inserted(w, hyphen='\u00b7')
        it['syl'] = parts
        if '\u00b7' in parts:
            n_ok += 1
    io.open(WORDS, 'w', encoding='utf-8').write(
        json.dumps(ws, ensure_ascii=False, separators=(',', ':')))
    print(f'写入 {len(ws)} 词，其中有切点 {n_ok} 词')
    print('大小', os.path.getsize(WORDS), 'B')
    for w in ['beautiful', 'method', 'holiday', 'dictionary', 'important']:
        for x in ws:
            if x['w'] == w:
                print(' ', w, '->', x['syl'])
                break


if __name__ == '__main__':
    main()
