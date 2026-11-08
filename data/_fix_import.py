# -*- coding: utf-8 -*-
import io, re

p = r'D:\手机\re\ccg\data\build_lexicon.py'
s = io.open(p, encoding='utf-8').read()

start = s.index('BAD_EN = re.compile')
end = s.index('def colloquial(ex):')
end = s.index('\n\n\n', end) if '\n\n\n' in s[end:] else len(s)
block = s[start:end]
print('--- removing', len(block), 'chars ---')
print(repr(block[:120]))
print(repr(block[-120:]))

s = s[:start] + 'from colloquial import BAD_EN, FORMAL, CONTRACT, colloquial  # noqa: E402' + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print('ok, has BAD_EN define left:', 'BAD_EN = re.compile' in s)
