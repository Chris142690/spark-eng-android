# Spark·ENG — Android 背单词 App（**源码仓库**）

> ## ⚠️ 这是源码（source code），不是 APK 发布包
>
> 本仓库只包含**可构建的源代码**：Java 源文件、资源文件、词库/句型数据、构建脚本与测试。
> **不包含**已签名的 APK、应用商店发布物、签名密钥、用户数据或任何账号凭据。
> 想装到手机上，请按下面「构建」一节自己编译，或直接向作者索取安装包。
>
> 本仓库**未附加开源许可证**（默认保留所有权利）。如需使用请先联系作者。

---

## 这是什么

一个自用的、**纯 Java 手写**的 Android 背单词应用，用于一年内把英语从 A1 推到 A2。三个练习线并行：

| 线 | 做什么 | 调度 |
|---|---|---|
| **认义（recognition）** | 看英文 → 先自己写中文释义 → 再揭示 → 判对错 → 打分 | FSRS-4.5 |
| **拼写（spelling）** | 400 词白名单的产出式拼写，只认原形（`go→goes` 判错） | FSRS-4.5（独立卡表） |
| **句型 / 功能词（pattern）** | 15 个句型单元 + 36 条场景表达，中文情境写英文 | 自建阶梯（非 FSRS） |

设计上的几个硬约束（改动前请先读 `docs/DESIGN-LOCK.md`）：

- **没有 XML 布局**。整个 UI 由 `MainActivity` 用代码构建（`LinearLayout`/`TextView` 手搭），单 Activity。
- **不引入 Gradle、不引入第三方依赖**。构建走 `aapt2 + javac + d8 + apksigner` 手工链路。
- **数据库只升不降**。`Db.VER = 14`，`V2 → V14` 每一步都有显式迁移，建索引失败必须硬失败而不是静默跳过。
- **单测跑在 JVM 上**（纯 Java 类，不依赖 Android 框架），`tests/run_tests.sh` 一键跑完。

---

## 目录结构

```
.
├── build.sh                  # 手工构建 APK（aapt2 → javac → d8 → pack → apksigner）
├── app/
│   ├── AndroidManifest.xml   # minSdk 24 / targetSdk 34，versionName 0.14.0
│   ├── src/com/aurora/word/  # 8 个 Java 源文件，约 4,600 行
│   │   ├── MainActivity.java # UI + 全部交互（约 2,570 行，程序化布局）
│   │   ├── Db.java           # SQLite schema + V2→V14 迁移 + 全部读写
│   │   ├── Fsrs.java         # FSRS-4.5 调度（17 参数，TARGET_R=0.90）
│   │   ├── Pattern.java      # 句型判定 + 阶梯调度（普通 / 功能词两套）
│   │   ├── Sense.java        # 中文释义判定（三态：对 / 错 / 无法判定）
│   │   ├── CardRules.java    # 卡片状态机
│   │   ├── Sync.java         # 导出/导入（JSON）
│   │   └── SyncTask.java
│   ├── assets/
│   │   ├── words.json        # 1,208 词 × 12 字段
│   │   ├── forms.json        # 1,035 条屈折/派生映射
│   │   ├── patterns.json     # 15 个句型单元（12 句型 + 3 功能词）
│   │   ├── collocations.json # 36 条场景表达
│   │   └── ambiguous.json    # 一词多形的消歧表
│   ├── res/                  # adaptive icon + 主题
│   └── tools/                # pack.py（zip 对齐打包）、make_icon.py（生成图标）
├── tests/                    # 28 个脚本/文档：JVM 单测、真机探针、验收与审计
├── data/                     # 词库审计与内容构建脚本（不含原始语料）
└── docs/                     # 规格、设计锁、UI 规范、实现状态、测试证据
```

---

## 构建

需要：**JDK 17+**、**Android SDK**（`build-tools;34.0.0` + `platforms;android-34`）。

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export KSPASS='your-keystore-password'      # 可选；不给则不签名
./build.sh
```

产物：`build/apk/aurora.apk`（已签名）或 `build/apk/unsigned.apk`（未签名）。
脚本最后会自检：`classes.dex`、`resources.arsc`、`assets/*.json` 是否齐全，以及 STORED 条目是否 4 字节对齐——这项检查失败会直接 `exit 1`，因为不对齐的 APK 在部分设备上装不上。

> 手动签名（用自己的密钥）：
> ```bash
> keytool -genkeypair -keystore aurora.keystore -alias aurora -keyalg RSA \
>   -keysize 2048 -validity 10000 -storepass "$KSPASS" -keypass "$KSPASS" \
>   -dname "CN=SparkENG, OU=Dev, O=SparkENG, L=CN, S=CN, C=CN"
> KS_PATH=./aurora.keystore KSPASS="$KSPASS" ./build.sh
> ```

---

## 测试

```bash
# JVM 单测（无需设备、无需 Android SDK）
bash tests/run_tests.sh
# 期望：Fsrs 23 + Invariant 31 + Sense 30 + Pattern 44 = 128 PASS
```

`tests/` 里的其他脚本面向真机与验收，需要 `adb` 与一台已连接设备，**在没有设备时会以 SKIP 或非零码退出——这是设计意图，SKIP 不等于通过**：

| 脚本 | 作用 |
|---|---|
| `run_tests.sh` | JVM 单测四件套 |
| `verify_v14.sh` | 一键验收（单测 → 内容校验 → 构建 → 设备探测 → 探针 → 迁移对账） |
| `pattern_probe.py` | 真机句型线探针（点 UI、比前后 DB、正反路径都测） |
| `smoke_adb.py` | 真机冒烟（安装/首启/迁移/闭环） |
| `content_check.py` | `patterns.json` / `collocations.json` 离线结构校验（含负控制） |
| `stable_digest.py` | 数据库行级指纹（IEEE-754 exact / 可选 rounded 降级） |
| `migrate_replay.py` | 合成 V5 库 → 重放 V6–V14 迁移 |
| `MIGRATION-AUDIT.sql` | 只读对账 SQL（行数、关键字段、索引、历史一致性） |
| `ACCEPTANCE-PLAN.md` / `ACCEPTANCE-COVERAGE.md` / `FAULT-INJECTION.md` | 验收计划、覆盖矩阵、故障注入手册 |

---

## 数据资产说明

- `app/assets/words.json`：1,208 词，12 字段（`id, w, ipa, pos, cn, ex, excn, tag, frq, ex0, excn0, syl`），按词频排序。
- `app/assets/patterns.json`：15 个单元。其中 `f-be` / `f-have` / `f-do` 是**功能词**，走 D+1 → D+3 → D+7 → D+14 的加密阶梯；其余 12 条是普通句型（D+3 → D+7 → D+14 → 封顶 30）。
- `data/` 下保留的是**审计产物与构建脚本**（如 `function-words-audit.md` 记录 be/am/is/are/have/has/do/does 等 16 个功能词全部不在 1,208 词库内），**不含**原始语料（CC-CEDICT、ECDICT、句对 TSV 等大文件）。
- 词库与例句为自建整理，仅用于个人学习。

---

## 不包含的东西（故意排除）

| 排除项 | 原因 |
|---|---|
| 签名密钥 `*.keystore`、口令文件 | 凭据不进仓库 |
| 已构建的 `*.apk`、`build/` 产物 | 本仓库是源码仓库 |
| 原始语料 CSV/TSV/BZ2 | 体积大、许可复杂，只保留派生产物 |
| 真机截图与 UI dump（`tests/shots/`） | 含设备信息 |
| 个人笔记与对话记录（`MEMORY-BUS.md` 等） | 与代码无关 |

---

## 状态

- 版本 `0.14.0`（`versionCode 14`），数据库 schema `VER = 14`。
- JVM 单测 128 PASS。
- 真机验收进行中：部分场景仍需在设备上复测（明细见 `docs/IMPLEMENTATION-STATUS.md` 与 `tests/ACCEPTANCE-COVERAGE.md`）。
- 应用名 `Spark·ENG`，包名 `com.aurora.word`（历史原因，未改）。
