# 测试证据（只写做过的，不写推测的）

测试时间：2026-11-05 18:00–22:25（Asia/Shanghai）
测试对象：`aurora.apk` 360,997 B / `classes.dex` 33,160 B / `assets/words.json` 1,208 条

## 一、有运行证据的（真的跑过）

| # | 项目 | 方法 | 结果 |
|---|---|---|---|
| 1 | 编译链 | `bash build.sh` 六步（aapt2 compile → link → javac → d8 → pack.py → apksigner） | 通过；javac 有一次失败（正则 `\-` 非法转义），已修 |
| 2 | APK 结构 | `zipfile` 逐条目读 header_offset | 7 条目；`AndroidManifest.xml`(56)、`resources.arsc`(1876)、`assets/words.json`(2952)、`classes.dex`(280916) 全部 `mod4==0` |
| 3 | 词表装入 | 解包读 `assets/words.json` | 1,208 条，`frq` 19–18387 |
| 4 | 中文判定（离线全量） | Python 复刻 `matchCn/normCn`，对 1,208 词用各自首义项自匹配 | 失败 **0** |
| 5 | 中文判定反例 | say 输「说话」/空串 | 均为 `False`（正确拒绝） |
| 6 | 中文判定正例 | say 输「说」 | `True` |
| 7 | 挖空生成（离线全量） | Python 复刻 `blank()` | 1,207/1,208 命中；唯一失败 `approve / "The manager approved the plan."` → 已加屈折形规则修复后重编 |
| 8 | 例句含目标词 | 全量检查 `w.lower() in ex.lower()` | 1,208/1,208 |
| 9 | 例句残留书面形式 | 正则 `\b(do not\|is not\|I am\|cannot\|it is\|that is\|you are)\b` | 0 条 |
| 10 | 词表重复 | 按 `w` 计数 | 0 |
| 11 | FSRS 落库（历史真机） | 真机新授 say → 评分「记得」→ 长按导出 | `{"w":"say","rating":3,"ms":19551,"prev_ivl":0,"new_ivl":3.7145}`；card `state=2, s=3.7145, d=5.1618, reps=1` |
| 12 | 首页 / 新授三屏 / 三档评分（历史真机） | adb tap + uiautomator dump 逐屏确认 | 通过（详见 `docs/GPT-协商-v2.md` 真机段） |

## 二、没有运行证据的（本轮新增，一次都没跑过）

以下全部是今晚 21:30 之后写的代码，**手机已断开，从未执行**：

- 懒筛第一步（中文输入框、IME 是否可用、键盘是否遮挡提交按钮）
- 懒筛第二步（挖空例句渲染、屈折形判定在真机上的行为）
- `state=3` 建卡 → 24h 后出现在复习队列
- 次日确认通过后三档评分的 UI 切换（`curAns.removeAllViews()` + `fillAnswer()` 的视觉结果）
- `screen` 表写入

判定：**这些是设计，不是已验证功能。**

## 三、编译期发现并修复的问题（保留记录）

| 问题 | 现象 | 修法 |
|---|---|---|
| d8 静默失败 | javac 报错时 build.sh 仍打印 `BUILD OK`，产出无 `classes.dex` 的包 → 安装报 `INSTALL_FAILED_INVALID_APK: ... code is missing` | 装包前必须确认日志出现 `classes.dex ... OK` |
| 匿名内部类崩溃 | R8 8.2.2 在"外部类 + 匿名内部类"同批进 dex 抛 `NullPointerException: Cannot invoke "String.length()"`，dex 静默不产出 | 全部控件改用 `setTag()` + `onClick` 集中分发 |
| dex 未对齐 | `classes.dex` data_off mod4=2 | `pack.py` 全部条目改 STORED + 手工 extra 字段对齐 |
| 词表未同步 | APK 内仍是旧的 1,486 条 | `build.sh` 读的是 `$PROJ/assets`，需先 `cp words.json assets/` |

## 四、可复现命令

```bash
# 编译（D:\aw 是 D:\手机\re\ccg\android 的 ASCII junction，aapt2 不吃中文路径）
cd /d/aw && bash build.sh

# 校验 APK
python -c "import zipfile,json;z=zipfile.ZipFile('D:/手机/re/ccg/aurora-1208.apk');print([(i.filename,i.compress_type) for i in z.infolist()]);print(len(json.loads(z.read('assets/words.json'))))"

# 离线全量判定自检
cd "D:/手机/re/ccg" && python data/selfcheck.py   # 见包内 data/selfcheck.py
```
## 五、审查后修复记录（2026-11-05 22:36）

| 缺陷 | 发现方 | 证据 | 修法 | 复验 |
|---|---|---|---|---|
| 首次评分把 S 放大到 177.5 天 | GPT 独立审查 | 静态读 `rate()`：`isNewCard` 只判 `k==null \|\| k.state==3`，漏 state=1；s=0 进 `next()` | 改 `k.reps == 0` | **未复验**（无设备）。可用一条新词评分后导出，检查 `new_s` 是否等于 3.7145 而非 177 |

判定：此修复只经过静态推理与重新编译，**没有运行证据**。
## 六、第二轮修复的验证情况（2026-11-05 23:15）

| 项目 | 验证方式 | 结果 |
|---|---|---|
| APK 五条目齐全 + 4 字节对齐 | `build.sh` 末尾内嵌 zipfile 校验 | 通过（本次正是它拦下一次缺 `classes.dex` 的假包） |
| javac 失败即中止 | `PIPESTATUS[0]` 检查 | 已加入；修复前 build.sh 会在 javac 报错后仍打印 BUILD OK |
| forms.json 生成正确性 | 抽样人工核对：go→[goes,going,gone,went]、say→[said,saying,says]、study→[studied,studies,studying]、stop→[stopped,stopping,stops]、child→[children] | 正确 |
| 白名单能否拦住错误拼写 | **未跑**，需真机或 JVM 单测 | 无证据 |
| 失败回退是否真的回三屏 | **未跑** | 无证据 |
| 35 张跨会话封顶 | **未跑** | 无证据 |
| DB 升级是否保数据 | **未跑** | 无证据 |

判定：第二轮全部修复都只有"编译通过 + 静态推理"，没有一条有运行证据。

## 七、FSRS 单元测试（本轮首个真实运行证据）

`tests/FsrsTest.java` + `tests/run_tests.sh`，纯 JDK，不依赖 Android SDK 与真机：

```
bash tests/run_tests.sh
```

断言内容：

| 组 | 断言 | 期望 | 实测 |
|---|---|---|---|
| 首次稳定性 | initStability(1/2/3) | 0.4872 / 1.4003 / 3.7145 | 一致 |
| 首次难度 | initDifficulty(1/2/3) | 7.6214 / 6.3916 / 5.1618，D(Again)>D(Good) | 一致 |
| 首次间隔 | interval(3.7145, 0.9) | 3.7145 | 3.7145 |
| P0 回归 | next(0, 0, retrievability(20min, 0), 3) | 应爆到 ~177（证明旧路径有害） | 177.52922861244141 |
| 修复路径 | 走 initStability(3) | 3.7145 | 3.7145 |
| 正常复习 | 20 天后 Good 的 S | 2–8 倍增长 | 3.7145 → 14.058 |
| 单调性 | R(1) > R(7) > R(30) | 成立 | 0.9698 > 0.8327 > 0.5878 |

完整输出：

```
== һ���״������ȶ��ԣ�P0 �ع飩 ==
PASS initStability(1) Again = 0.4872
PASS initStability(2) Hard = 1.4003
PASS initStability(3) Good = 3.7145
== �����״��Ѷȣ�Again Ӧ�� Good ���ѣ�D ���� ==
PASS initDifficulty(1) = 7.6214
PASS initDifficulty(2) = 6.3916
PASS initDifficulty(3) = 5.1618
PASS D(Again) > D(Good) 7.6214 > 5.1618
== �����״μ����R=0.9�� ==
PASS interval(3.7145, 0.9) = 3.7144999999999992
== �ġ����� P0��s=0 �Ŀ����� next() �ᱬ���ȶ��� ==
PASS retrievability(0, s) == 1 got 1.0
PASS ��·��ȷʵ��Ŵ�>30 �죩 r(s=0)=0.0 next �� s=177.52922861244141���Ա���ȷֵ 3.7145��
PASS �޸�·�� s ���� 3.71 s=3.7145
== �塢������ϰ·����20 ��� Good �������� ==
PASS 20 ��ǰ�� S ������ 2-8 �� s 3.7145 -> 14.058481880640956 r=0.9003339453647258
== ��������Խ���ȶ���Խ���ƫ�� ==
PASS Again < Hard < Good 0.4872 < 1.4003 < 3.7145
== �ߡ�����������Ϊ�� ==
PASS interval Ϊ���� < 365 ivl=3.7144999999999992
== �ˡ�retrievability �����������½� ==
PASS R(1) > R(7) > R(30) 0.9698462273634281 > 0.8327423038946435 > 0.5877801341833996
RESULT: pass=15 fail=0
```

**这条是运行出来的，不是推理出来的**：`next(0,0,r,3)` 确实返回 177.5292，与审查方静态推断的数字完全一致，说明旧代码「新授卡带 s=0 进 next()」会把首次稳定性放大 47 倍。修复后该路径不再进入 `next()`。

仍未验证：白名单拦截、次日确认失败回退、35 张跨会话封顶、DB 升级保数据——这四处需要真机。
