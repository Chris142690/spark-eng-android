# -*- coding: utf-8 -*-
"""生成给 GPT 评审的自包含协商包 v2。"""
import io, os, json, time

ROOT = r'D:\手机\re\ccg'
AND = os.path.join(ROOT, 'android')
OUT = os.path.join(ROOT, 'GPT-协商-v2.md')


def read(p):
    return io.open(p, encoding='utf-8').read()


def block(title, path, lang='java'):
    try:
        s = read(path)
    except Exception as e:
        s = '/* 读取失败: %s */' % e
    return '\n### %s\n\n文件：`%s`\n\n```%s\n%s\n```\n' % (title, os.path.basename(path), lang, s)


words = json.load(io.open(os.path.join(ROOT, 'words.json'), encoding='utf-8'))
import random, re
random.seed(11)
samples = random.sample(words, 10)
nsample = '\n'.join('- `%s` — %s / %s' % (w['w'], w['ex'], w['excn']) for w in samples)
ncon = sum(1 for w in words if "'" in (w.get('ex') or ''))
lens = [len(re.findall(r"[A-Za-z']+", w['ex'])) for w in words if w.get('ex')]

body = '''# Aurora 单词 App — 第二轮协商材料（v2）

> 用途：把这份文件整份贴给 GPT，让它评审并回答第 6 节的决策问题。
> 上一版（v1）已贴过一轮；本版只写**这一轮改了什麼**，源码给的是**当前全部源码**。

## 1. 目标（未变）

- 我（用户）现在英语是 A1 完整、A2 为零（实测：A1 5/5，A2 0/5）。一年内要到 A2，会用在国内日常 + 出国旅行。
- 不背单词用了很久但失败，原因是复习压力：一天不背，第二天就要复习几百个，"像欠了高利贷"。所以这个 App 的第一原则是**日常复习量必须可承受**，宁可慢。
- 单人自用，不上架、不联网、不社交、不排名。数据全在本机 SQLite。
- 界面气质：暖纸白、安静、不要仪表盘（不要进度环/连续天数/还剩几张）。

## 2. 上一轮 GPT 的裁决（已执行）

1. 首页是启动器不是成绩单 → 已删进度环与"还剩 X 张"。
2. 只有两个入口：预览卡=复习，按钮=学新词。已做到。
3. 新授不评分、不初始化 FSRS（首次编码 ≠ 首次评分）。已做到：新授结束写 `state=1, due=+20min, reps=0`。
4. 听辨用预生成音频、播前隐藏词形、每题限播 2 次、加载失败记"未测试"不计 0 分。网页测站已实现（原生 App 尚未接听辨）。

## 3. 这一轮改了什么（请重点评这两条）

### 3.1 例句全面口语化

问题：抓来的例句（Tatoeba 语料）书面腔太重，出现 `I do not know.` 这种教科书句子。

做法：
- 在选句阶段加两个打分维度：含缩写（`don't / I'm / it's`）加分；含书面词（`shall / whom / thus / therefore / moreover / whilst / ought`）降级；词数靠近 7 加分。
- 选中后再做一次**缩写归一**：`I am→I'm`、`do not→don't`、`cannot→can't`、`it is→it's`、`you are→you're`、`let us→let's` 等 30 条规则，保留句首大小写，并对 `Here I am` / `There you are` 这类固定倒装做保护（不能变成 `Here I'm`）。
- 结果：1,486 词，例句含缩写 @@NCON@@ 条（本轮新增 172 条），词数中位数 @@MED@@，例句里出现目标词的比例 100%。

抽样（当前正式数据）：
@@SAMPLES@@

### 3.2 卡片排版改版

上一轮视觉评审给了三条意见，本轮已改：
1. 音标原来是白色胶囊框，在米色底上像贴纸 → 去掉框，音标与词性并成一行：`/nəʊ/  ·  v.`
2. 内容块整体偏上 → 正文容器加 46dp 上内边距，视觉重心下移。
3. 例句居中导致长句视线跳跃 → 例句与译文改为左对齐，并加一条 2dp 淡绿竖线（`#33536F69`）做视觉锚，文字缩进 14dp。

## 4. 当前数据事实

- 词表 `words.json`：**@@NW@@** 条（中考 1,399 为主，高考补足），字段 `w/n/ipa/pos/cn/ex/excn`。
- 音频：1,494 个 wav（Windows SAPI 预生成），APK 内不打包，走设备 TTS。
- FSRS：4.5 版 21 参数，`TARGET_R=0.9`。真机实测一条记录：`say` 评分 3 → `s=3.7145, d=5.1618, ivl=3.7d`。
- 日上限：`CAP=35` 张（含新授/复习/重试）；新词数读 SharedPreferences，默认 3，范围 2–8。
- APK：119 KB（含 1,486 词 assets），minSdk 24 / targetSdk 34，纯 Java 程序化 UI，无第三方依赖。

## 5. 我自己的判断（可以反驳）

- 我现在最怕的不是功能不够，而是**例句质量**：1,486 条里仍有一批"语法正确但没人这么说"的句子。缩写能修掉一部分书面腔，修不掉"句子本身不自然"。
- 第二个隐忧是**新授节奏**：20 分钟后的首测如果集中在同一时间点，会在某几个小时里堆出 3 张卡；一天只有一个 20 分钟窗口的人会错过首测。

## 6. 需要你决策的 10 个问题

1. **例句质量**：在不能联网、不能调用大模型的前提下，怎么自动把"不自然的句子"筛掉？我目前只有缩写+书面词黑名单。有没有更硬的机械判据（例如 collocation 频率、目标词位置、句长分布）？
2. 是否值得为 1,486 词**人工过一遍**例句？按你的估计，这会花多久、能提升多少留存？
3. **首测时间点**：固定 20 分钟（当天第二次打开才能测到）还是改成"下次打开 App 时立即测"？后者会不会破坏 FSRS 的间隔语义？
4. 新授三屏（词+音标 → 释义/例句 → 遮挡回想）够不够？是否应在第一屏就要求**拼写**（我拼写很弱）？
5. 例句可点朗读（TTS 读整句）该不该保留？会不会让人依赖"看句子"而不回忆？
6. 首页现在只有两行文字和两个按钮，会不会太空？要不要放一张极淡的风景图（55–65% 暖白蒙层）？
7. 我打算把"新词数自动调节"（近 7 天完成率 ≥80% 且均时 <12min → +1；<60% 或 >20min → −1；2–8 之间；第 14 天前不调）做进 App。这个判据有问题吗？
8. 数据导出目前是长按首页底部条写一个 JSON 到外部目录。够不够？要不要做"导出到电脑"的二维码/局域网通道？
9. **我最该先补哪一块**：例句质量、拼写线、还是听辨接进原生 App？请排序并说明理由。
10. 如果只允许我再改三个地方就冻结这一版开始真正学习，你会选哪三个？

## 7. 请按这个格式回答

- 对每个问题：结论（一句话）→ 理由（≤3 句）→ 如果要改，给出**具体到参数/文案/交互**的改法。
- 最后给一个"冻结清单"：这一版必须改完才能开始学的 3 项，和可以以后再说的 5 项。

## 8. 当前全部源码

'''
body = (body.replace('@@NCON@@', str(ncon))
            .replace('@@MED@@', str(sorted(lens)[len(lens) // 2]))
            .replace('@@SAMPLES@@', nsample)
            .replace('@@NW@@', str(len(words))))

body += block('MainActivity.java（全部 UI 与流程）', os.path.join(AND, 'src', 'com', 'aurora', 'word', 'MainActivity.java'))
body += block('Fsrs.java（FSRS-4.5）', os.path.join(AND, 'src', 'com', 'aurora', 'word', 'Fsrs.java'))
body += block('Db.java（SQLite 三表）', os.path.join(AND, 'src', 'com', 'aurora', 'word', 'Db.java'))
body += block('colloquial.py（例句口语化规则）', os.path.join(ROOT, 'data', 'colloquial.py'), 'python')
body += block('AndroidManifest.xml', os.path.join(AND, 'AndroidManifest.xml'), 'xml')
body += block('res/values/styles.xml', os.path.join(AND, 'res', 'values', 'styles.xml'), 'xml')
body += block('build.sh（构建链）', os.path.join(AND, 'build.sh'), 'bash')

body += '\n---\n\n生成时间：%s ｜ 词表 %d 条 ｜ APK 119 KB\n' % (
    time.strftime('%Y-%m-%d %H:%M'), len(words))

io.open(OUT, 'w', encoding='utf-8').write(body)
print('written', OUT, len(body.encode('utf-8')), 'bytes,', body.count('\n'), 'lines')
