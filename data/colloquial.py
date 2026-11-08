# -*- coding: utf-8 -*-
"""例句口语化：正式形式 -> 缩写；并给出书面词/口语感判定器。"""
import re

BAD_EN = re.compile(r"""[0-9"“”()\[\]@#$%^&*_=+{}\|<>~`]""")

FORMAL = re.compile(
    r"(\b)(shall|whom|thus|hence|furthermore|moreover|nevertheless|therefore|"
    r"regarding|inasmuch|whilst|amongst|ought|whence|thence|lest|notwithstanding)(\b)",
    re.I,
)

CONTRACT = re.compile(r"[A-Za-z]+'(s|t|re|ll|ve|d|m)\b", re.I)

PROTECT = (
    'Here I am', 'There I am', 'Here you are', 'There you are',
    'Here it is', 'There it is', 'Here we are', 'Here they are',
)

# (原文片段, 缩写形式)；匹配时不区分大小写，替换保留首字母大小写
RULES = [
    ('I am', "I'm"), ('I have', "I've"), ('I will', "I'll"), ('I would', "I'd"),
    ('It is', "It's"), ('That is', "That's"), ('There is', "There's"),
    ('You are', "You're"), ('We are', "We're"), ('They are', "They're"),
    ('He is', "He's"), ('She is', "She's"),
    ('Do not', "Don't"), ('Does not', "Doesn't"), ('Did not', "Didn't"),
    ('is not', "isn't"), ('are not', "aren't"),
    ('was not', "wasn't"), ('were not', "weren't"),
    ('cannot', "can't"), ('can not', "can't"),
    ('will not', "won't"), ('would not', "wouldn't"),
    ('could not', "couldn't"), ('should not', "shouldn't"),
    ('have not', "haven't"), ('has not', "hasn't"), ('had not', "hadn't"),
    ('Let us', "Let's"), ('let us', "let's"),
]

_PATTERNS = [
    (re.compile(r'(?<![A-Za-z])' + re.escape(a) + r'(?![A-Za-z])', re.I), b)
    for a, b in RULES
]


def _case(m, rep):
    src = m.group(0)
    if src[:1].isupper():
        return rep[:1].upper() + rep[1:]
    return rep


def colloquial(ex):
    if not ex:
        return ex
    if any(p in ex for p in PROTECT):
        return ex
    out = ex
    for pat, rep in _PATTERNS:
        out = pat.sub(lambda m, r=rep: _case(m, r), out)
    return out
