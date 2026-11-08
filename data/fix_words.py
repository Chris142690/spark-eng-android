# -*- coding: utf-8 -*-
"""对 words.json 做后处理：繁转简、IPA 修正、去重、排序，并输出统计"""
import json, os, re, sys
import zhconv

HERE = os.path.dirname(os.path.abspath(__file__))
FP = os.path.join(os.path.dirname(HERE), 'words.json')


def fix_ipa(s):
    s = (s or '').strip()
    s = s.replace('ɒː', 'ɔː').replace('є', 'eə').replace('ә', 'ə').replace('ɡ', 'g')
    s = re.sub(r'^/\.', '/', s)
    s = re.sub(r'^/([^/]*)\.', r'/\1', s)   # 去掉音标内部开头的点
    s = re.sub(r'\./', '/', s)
    return s


def main():
    words = json.load(open(FP, encoding='utf-8'))
    seen, out = set(), []
    for w in words:
        k = w['w']
        if k in seen:
            continue
        seen.add(k)
        w['ipa'] = fix_ipa(w['ipa'])
        w['excn'] = zhconv.convert(w.get('excn', ''), 'zh-cn')
        w['cn'] = zhconv.convert(w.get('cn', ''), 'zh-cn')
        w['ex'] = (w.get('ex') or '').strip()
        w['excn'] = (w.get('excn') or '').strip()
        if not w['ex']:
            continue
        out.append(w)
    out.sort(key=lambda x: x.get('frq', 999999))
    for i, w in enumerate(out):
        w['n'] = i + 1
    json.dump(out, open(FP, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)

    trad = '們個這來時說麼為國學開過對無車東馬鳥魚島點電聽讀寫話語詞還讓嗎沒關係覺得應該裡齊湯姆'
    bad = [w for w in out if any(c in trad for c in w['excn'])]
    print('条目', len(out))
    print('仍是繁体', len(bad), [w['excn'] for w in bad[:5]])
    m = [w['w'] + ' ' + w['ipa'] for w in out if '"' in w['ipa'] or len(w['ipa']) < 4]
    print('IPA 可疑', len(m), m[:5])
    print('平均释义字数 %.1f' % (sum(len(w['cn']) for w in out) / len(out)))


if __name__ == '__main__':
    main()
