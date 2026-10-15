# 拾词 WordTrail

原生 Android 离线背词应用，Kotlin + Jetpack Compose + Room + DataStore。最低支持 **Android 8.0（API 26）**。内置 320 个常用词，无账号、无广告、无联网权限。

## 词库管理（1.1.1）

1.1.1 修复文件导入兼容性：支持常见 `word/translations`、嵌套 `headWord/content` JSON/JSONL 和词典 TSV；普通 Tab 文本中的引号不再导致整行失败，CSV 自动识别逗号、分号及 Tab。文件导入可先选文件，名称留空时自动使用文件名；错误提示会一直保留到重试。Excel ANSI 文件可手动选择 GBK/GB18030，Excel 工作簿或压缩包需先转换为 CSV/TXT。

从 **设置 → 词库管理** 进入。列表展示名称、数量、来源和当前学习状态，可搜索、重命名、切换学习；长按卡片进入多选。

- 右下角 **添加词库 → 新建词库**：填写名称和可选描述，创建后逐条添加，也可转为批量导入。
- **粘贴导入**：每行 `英文单词<Tab>中文释义`，长按输入框手动粘贴。应用不会主动读取剪贴板。
- **文件导入**：选择新建或追加到已有自建词库，再通过系统文件选择器选择 CSV、Tab 分隔 TXT/TSV、JSON/JSONL 或拾词备份 JSON。新建名称留空时使用文件名。此入口只导入词条；恢复学习记录仍用原设置入口。直接用 ContentResolver 读取 URI，不请求存储权限。
- CSV 列顺序：`english,meaning,phonetic,partOfSpeech,example,translation`，只有前两列必填；可使用表头调整列顺序。支持中文表头、双引号转义、引号内逗号和换行。词库管理右上角可导出 CSV 示例，源码也提供 `tools/import-template.csv`。
- 默认编码支持 UTF-8 / UTF-8 BOM、带 BOM 的 UTF-16 LE/BE；Excel ANSI 文件可手动改选 GBK/GB18030 后重新选择。无法解码时明确提示，不自动猜测编码。单次上限 20 MB、50000 个词条，字段有长度限制；解析与文件 I/O 在后台执行。
- JSON 支持 `english/meaning`、`word/translations`、`words/data/entries` 包装数组、`headWord/content.word.content` 嵌套词典和逐行 JSONL。保留中文义项、词性、音标与首个例句；短语、同根词等额外词典资料不属于现有 Word 数据结构，不写入。词典 TSV 兼容 3/6/13 列，普通无表头文本仍按“英文、中文释义、音标、词性、例句、翻译”读取。
- 预览显示有效条数、重复数、错误行及前 8 条内容。重复按同词库英文忽略大小写判断，可选跳过或更新；更新保留词条 ID 和学习进度，空的可选字段不覆盖旧内容。错误行必须勾选确认后才可忽略。
- 删除默认进入回收站，词条和进度保留。删除当前词库会自动切换，无可用词库时提供添加/恢复引导。内置词库只能隐藏和恢复。自建词库在回收站可经过两次确认永久删除，仅清理它独占的词条及记录，共享数据保留。
- 导入采用单个 Room 事务，失败回滚。ViewModel 保存进行中的任务和预览，旋转或退到后台不会重复提交；互斥锁阻止连续点击。进程被系统终止后未提交草稿/预览需要重新选择，已提交数据保留，不会自动再次导入。

当前学习、历史、收藏和统计均对应所选词库；每日统计也按该词库过滤。词库、成员关系、当前选择和回收站存于 Room。旧版 JSON 学习备份协议继续保留词条和记录，但不保存词库分组、名称及回收站状态。

## 使用方式

- **学习**：默认隐藏中文释义与双语例句，点击展开；英文、音标、词性直接显示。新词模式只抽取未看过的词；复习模式优先未掌握词，从最久未看的 5 个候选中随机选择，存在其他词时避免连续重复。
- **上一个 / 下一个**：沿本次模式内的学习顺序前后移动，退回后点击下一个先回到已有的下一张卡片；到队尾才重新抽词。切换模式会开始新的会话顺序。无新词或无旧词时可直接切换模式。
- **自动记录**：首次显示、前后切词、打开历史详情都会记录一次查看；展开释义、切换底部页面、界面重绘和 Activity 重建不会重复计数。记录先提交 Room 事务，再显示相应词条。
- **历史**：按最近查看时间排序，英文不区分大小写，支持中文释义搜索；支持收藏、未掌握、已掌握筛选。点击词条打开完整详情和状态按钮。
- **收藏**：展示收藏的词条，可在详情取消收藏、切换掌握状态。
- **设置**：默认释义开关、跟随系统/浅色/深色、JSON 导入导出。
- **统计**：累计看过的不同词数、已看过且已掌握的词数、今日看过的不同词数。当天多次查看同一词只算一个；今日按学习时设备本地日期计算。

查看记录、收藏和状态存于磁盘数据库，不依赖后台常驻。Android 正常关闭进程不会清空记录。SavedStateHandle 在系统允许恢复任务时恢复卡片与会话；强行停止、清除任务或重启后的会话顺序不承诺保留，但历史与收藏仍存于数据库。

## 安装测试 APK

交付目录中的 `WordTrail-debug.apk` 是调试签名 APK。复制到 Android 8.0 及以上手机后，用系统文件管理器打开并允许该来源安装应用。也可开启 USB 调试后运行：

```powershell
adb install -r WordTrail-debug.apk
```

`-r` 是保留数据的覆盖安装。必须保持相同的 applicationId（`com.wordtrail.app`）与签名证书；换电脑重新生成的 debug 签名可能不同。遇到签名冲突时，先在原应用导出备份，再处理安装。正式发行请创建并长期保管自己的 release 签名，不把私钥提交进源码。

**卸载应用或清除应用数据会删除未备份的本地记录。** 不依赖系统云备份或系统换机迁移，请使用应用内 JSON 备份。

## Android Studio 构建

1. 用支持 Android Gradle Plugin 8.13 的 Android Studio 打开本目录（不是只打开 `app`）。
2. 在 SDK Manager 安装 Android SDK Platform 36 与 Build Tools 35.0.0，选择 JDK 17 作为 Gradle JDK。
3. 让 Android Studio 生成本机 `local.properties`；源码压缩包不携带开发机 SDK 路径。
4. 等待 Gradle Sync。首次构建需要联网下载依赖；安装后的基础学习功能完全离线。
5. 使用 Build APK(s)，或在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

接入模拟器或真机后可执行仪器测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

版本固定：Gradle 8.14.3、AGP 8.13.2、Kotlin / Compose Compiler 2.2.20、Compose BOM 2025.10.00、Room 2.8.3、DataStore 1.1.7。不使用动态版本。项目附带完整 Gradle Wrapper（含 JAR）。

## 结构

```text
app/src/main/java/com/wordtrail/app/
  MainActivity.kt       Compose 四个页面、详情与文件选择器
  LibraryScreen.kt      词库列表、回收站、导入预览与确认
  StudyViewModel.kt     会话前后导航、加载状态、串行操作、备份 I/O
  WordTrailApp.kt       应用级依赖
  data/
    Database.kt         Room 表、DAO、1→2→3 迁移
    Library.kt          词库管理、导入事务、软删除与恢复
    WordImport.kt       TSV / CSV / JSON 解析及编码校验
    JsonWordImport.kt   常见 JSON / JSONL 词典结构适配
    Repository.kt       词库更新、查看事务、备份合并
    StudyLogic.kt       选词、筛选、状态合并规则
    Backup.kt           备份格式与校验
    Settings.kt         DataStore 偏好
app/src/main/assets/words.json    实际安装进 APK 的词库
app/schemas/                     Room 导出的数据库结构
app/src/test/                    纯逻辑测试与 Robolectric 配置
app/src/sharedTest/              本地 Robolectric / 真机共用测试
tools/words.psv                  便于编辑的原始词库
tools/build_wordlist.py          词库生成与结构校验（Python 标准库）
```

## 词库扩充与数据升级

词库由项目编写，例句为简短原创句，采用常见美式音标；每词提供一个常用词性及其主要释义，不涵盖全部词性、义项或英美变体。单词 `read` 使用现在时 /rid/，`close` 使用动词 /kloʊz/，`use` 使用动词 /juz/，与对应例句一致。

编辑 `tools/words.psv` 后运行：

```powershell
python tools/build_wordlist.py
```

每行字段为 `英文|音标|词性|中文释义|英文例句|例句翻译`。生成器检查数量、唯一 ID、必填字段与中英文字段结构；不把自动结构检查当作专业语言审校。

**发布后不可随意改动既有词条 ID。** 当前 ID 为小写单词。更新释义和例句保留 ID；同形词新增独立义项时应手动扩展生成器的 ID 规则（如 `close-verb`），并给旧 ID 做明确迁移，不能把已发布 ID 重命名后直接覆盖。应用启动使用 Upsert 更新词库内容，不删除旧词，学习状态独立存储；新增词成为未看过的词。

当前数据库版本为 **3**，应用版本为 **1.1.1（versionCode 3）**。`MIGRATION_1_2` 增加状态更新时间；`MIGRATION_2_3` 新建词库、成员关系和当前选择表，将升级前的全部词条关联到稳定 ID 为 `builtin` 的词库，不更改词条 ID 或学习记录。测试从真实 SQLite v1、v2 结构升级到 v3，并由 Room 验证表结构与旧记录。旧版 schema 保留，`app/schemas/.../3.json` 为现行结构。

今后改变表结构须递增数据库版本、提供 Migration、保留旧 schema 和新增迁移测试，并在 `WordDatabase.open()` 注册路径。**不允许使用 `fallbackToDestructiveMigration()`，也不允许删除数据库来完成升级。** 如果缺少迁移路径，应报错并保留原库。普通 APK 版本更新或词库内容更新无需改变数据库版本。

## 备份协议与恢复

导出文件为 UTF-8 明文 JSON，格式标识 `wordtrail-backup`，协议版本 1。包含完整词条、每词汇总记录、收藏/掌握的修改时间、每日去重日期；不包含外观设置。

导入先限制文件 10 MB、最多 10000 个词条并校验 ID、内容、计数、时间和日期，再用单个 Room 事务合并。任何校验或写入失败均不会部分导入。未知协议版本拒绝导入；文件选择取消不修改数据。

- 已有词条内容保留本机版本，缺失词条从备份补充。
- 首次查看取非零最早时间，最近查看取较晚时间。
- 查看次数取两端较大值，避免重复导入翻倍；因此两台手机独立学习后合并**不承诺精确相加**。
- 收藏和掌握分别按自身修改时间选较新状态；时间相同保留本机状态。设备时钟不准会影响冲突顺序。
- 每日词条按 `(wordId, day)` 并集，保持幂等。
- 导出时读取同一个数据库事务快照。使用 Android 系统文件选择器，无需申请存储权限。

## 验证与限制

请阅读随项目交付的 `VALIDATION.md`，其中记录实际运行的构建、测试、静态检查及尚未执行的真机项目。测试源码存在不等于已经在真机执行。

本版本不提供语音发音、在线同步、账号、考试词书或间隔重复算法；复习采用简单的掌握状态与最近查看时间优先策略。词库规模适合当前应用直接载入内存；扩展到数万词时应增加分页及数据库选词查询。

## 实现参考

- [Android Compose Compiler 配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- [Room 数据迁移](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Robolectric 本地 Android 测试](https://robolectric.org/getting-started/)
