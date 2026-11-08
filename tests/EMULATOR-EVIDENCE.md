# 模拟器实跑证据（Android 11 / API 30 / x86_64）

> 定位：**模拟器 ≠ 真机**。以下是行为证据（真实 UI 操作 + 前后 DB 对照），
> 但设备类型必须注明，不能当作「真机 P0/P1 通过」。真机项在本文档里仍是「未执行」。

## 环境
- SDK：`D:\asdk`（platform-tools、emulator 37.2.13.0、`system-images;android-30;google_apis;x86_64`）
- AVD：`a2`（`D:\asdk\avd\a2.avd`），端口 5556，WHPX 加速
- 启动命令（可复用）：
  ```
  export ANDROID_EMULATOR_HOME=/d/asdk/emu-home
  export ANDROID_AVD_HOME=/d/asdk/avd
  export TEMP='D:\asdk\tmpc' TMP='D:\asdk\tmpc'      # 否则 adb 打不开 %TEMP%\adb.log
  D:/asdk/emulator/emulator.exe -avd a2 -port 5556 \
     -no-audio -no-boot-anim -no-snapshot -memory 2048 -no-metrics -wipe-data
  ```
- 三个环境坑（都实测过）：
  1. `-no-window` 会让本机 emulator 卡在启动（qemu 只有 1 个线程、CPU 恒为 0.328s）；**带窗口启动可正常引导**。
  2. `C:\Users\<user>\.android\emu-last-feature-flags.protobuf.lock (error: 5)` → 设 `ANDROID_EMULATOR_HOME` 到可写目录。
  3. arm64 系统镜像在 x86_64 宿主上直接 FATAL，必须用 `google_apis;x86_64` 镜像。
- APK：`D:\手机\re\ccg\aurora.apk`，525,044 B，`classes.dex` 108,520 B（`adb install -r` 成功）
- 设备时间：模拟器用 UTC（`11月6日`/`22:4x`），与宿主 `11-07 06:4x` 相差 8 小时 → 日期键是 `20261106`。

## 已获得的证据

### smoke_adb.py（装机 + 首页 + 句型入口 + DB 结构）
`PASS=39 FAIL=0 MANUAL=1 SKIP=0`（日志 `tests/shots/smoke-run2.log`）
- 安装、冷启动、无崩溃日志、进程存活
- 首页口径行 `今天 0 / 50　·　累计 0 词　·　预计掌握 0`、复习大卡、`学句型 · 12 个句型`、`学新词 · 今天 3 个`
- 句型教学页可达（`今日句型 1/3 · A1 · daily`）
- DB：8 张表、word=1208、`user_version=14`、`idx_review_eid` 存在、`event_type` 无 NULL、无 rating=0 冒充正式评分、`hinted/verdict/answer/matched_sense/event_type` 五列齐全
- `kind` 合法、`collocation` 不改调度、今日 formal ≤3、功能词阶梯吻合
- 唯一 MANUAL：`新授·懒筛第一步`（`adb input text` 不支持中文）

### pattern_probe.py（T1–T5b）
- **T5 待确认链路 6/6 PASS**（本轮最重要的实测结果）：
  - `T5 待确认可见`：`needs_review=1` → 首页出现 `待确认 1 句 ›`
  - `T5 详情页要素`：提示语「系统暂时无法判断这句话是否正确…」+ 参考答案 + 原始输入 三要素齐全
  - `T5 搁置后仍可见`：`needs_review=1` 保持、`due` 变动 0ms、**审计事件恰好 +1**、无虚假评分
  - `T5 重启后保持`：force-stop 重启后入口仍在
  - `T5 重新练习恢复队列`：`needs_review=0`、`unverified_streak=0`、`due≈now`、**review_clear +1（必须恰好 1）**、`review_event` +0、`card` +0
  - `T5 审核事件不算作答`：`review_clear` 中 `verdict<>-1` 行数 = 0
  - `T5 恢复不绕过每日上限`：额度满时恢复直接进入教学页 = False，今日不同句型仍 = 3
- **T4 场景表达隔离 2/2 PASS**：`collocation` 事件 +1，而 `stage 2→2`、`due` 未变；该 pid 的 `kind='formal'` 事件数不变（不进正式掌握率）
- `T2 反向·答错不推进` PASS、`T1 被拒点击不消耗额度` PASS

### 仍然 FAIL（自动化流程未打通，不是产品判定）
- `T1 每日3句·前3个可开始`：UI 走完 3 个，但 `kind='formal'` 计数仍 0 —— 正式测试屏的作答没有落库
- `T1 第4个被拒` / `重启后仍受限`：因为前 3 个没有真正评分，额度没有被消耗，第 4 个自然不被拒
- `T2 功能词阶梯`：`stage` 全部 0 → 同上，正式作答未提交
- `T3 待审核暂停`：`needs_review` 仍 0 → 同上，两次 UNVERIFIED 没被记录
- `T1 次日重置` SKIP：prefs 里没有 `patIds_20261106`（同样因为正式作答没发生）

**结论**：`pattern_probe` 的场景表达（collocation）提交链路已打通（T4/T5 证明），
正式测试屏（`用英语说出来 · 1/3`）的「输入 → 收起键盘 → 点提交」仍有一步不稳，
导致 T1/T2/T3 的正式评分没有产生。下一轮先把 `submit_answer()` 在正式屏上做成
「写成功 → 提交 → 断言 pattern_event 新增」的原子步骤（失败重试一次），再跑 T1–T3。

## 会话内已修的脚本缺陷（都在本次实测中发现）
1. `smoke_adb.py`：句型页返回按钮只认 `‹`，实际是 `← 返回` → 补 `find(pnodes, "← 返回")`，否则后续新授步骤在句型页上点空。
2. `pattern_probe.py`：缺 `import time` → `NameError`。
3. `pattern_probe.py`：`find()` 子串匹配把提示语「…查看参考答案后**重新练习**。」当成按钮 → 新增 `find_btn()` 只认整串相等/短前缀（带句号逗号的说明文字排除）。这是 T5 从 FAIL 转 PASS 的直接原因。
4. `pattern_probe.py`：`open_pattern()` / `goto_home()` 点完不等待就 dump → 拿到上一屏控件，整轮空转（表现为「UI 完成 0」）。
5. `pattern_probe.py`：`run_one_pattern()` 重写为状态机（教学页 → 场景表达 → 正式 → 反馈 → 下一句），每一步重新 dump，不依赖上一步坐标。
6. `pattern_probe.py`：`submit_answer()` 先点输入框再打字（页面刚切过来 EditText 未必有焦点）。
