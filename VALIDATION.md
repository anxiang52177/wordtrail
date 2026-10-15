# 词库管理验证报告（1.1.0）

日期：2026-10-15（Asia/Shanghai）。项目：`WordTrail`。Kotlin + Jetpack Compose + Room + DataStore，最低 API 26，目标 API 36。沿用原架构、配色及原有学习逻辑，未变更发布签名。

## 构建与自动测试

最终执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain
```

结果：**BUILD SUCCESSFUL**。

- 30 项测试全部通过，0 失败、0 错误、0 跳过；其中原有 11 项测试继续通过。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，约 18.6 MiB，包名 `com.wordtrail.app`，versionCode 2，versionName 1.1.0。
- 仪器测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。
- Lint：0 错误、15 条警告，均为构建工具/依赖版本或 kapt 转 KSP 建议，没有使用 baseline 掩盖错误。
- 最终 APK 通过 `apksigner verify` 和 `zipalign -c -P 16 4`。
- `aapt dump badging` 确认 minSdk 26、targetSdk 36。无 INTERNET、外部存储或所有文件访问权限；只有 AndroidX 合并的应用私有动态接收器权限。
- 尝试 `connectedDebugAndroidTest`，结果为 **No connected devices!**，不计为设备测试通过。

## 已自动验证

本地 Android 测试使用 Robolectric API 35，不代表实体手机运行结果。

| 测试组 | 数量 | 验证内容 |
| --- | ---: | --- |
| WordImportTest | 8 | 中文、UTF-8 BOM、带 BOM 的 UTF-16 LE/BE、非法编码明确报错、空行/首尾空格/CRLF/CR、CSV 引号/逗号/多行字段/双引号转义、CSV 中文表头与列重排、普通 English 词条不会误判为表头、错误物理行号、空文件、项目 JSON/备份词条识别、50000 条解析与超限拒绝 |
| LibraryPersistenceTest | 8 | 新建/描述、稳定 ID 重命名、切换与空词库、预览重复数、大小写去重、追加、跳过与更新、空字段不覆盖旧内容、更新保留次数/收藏/掌握状态、批量删除和恢复、删除当前及最后一个词库、内置隐藏跨 seed 保持、永久删除保护共享词和记录、写入中途失败完整回滚、5000 条写入与重复导入、关闭重开后回收站与当前选择保留、真实 SQLite v2→v3 迁移 |
| FileImportTest | 1 | 通过注册的 ContentProvider 与真实文件描述符读取 content:// URI（应用不解析文件路径）、中文带逗号 CSV、读取失败提示且旧预览清空、空文件预览、通过 ContentResolver 导出模板 |
| LibraryNavigationTest | 2 | 设置入口、新建与逐词添加、中文描述、重命名、删除确认/回收站恢复、小屏列表滚动、粘贴预览和错误行确认、Activity 重建保留预览、后台→前台、连续两次提交只写入一次、导入后开始学习、再次重建不额外累计查看次数 |
| PersistenceTest | 3 | 原有磁盘记录/收藏/掌握保留、备份往返与幂等、坏备份不改数据、真实 SQLite v1→v3 迁移（沿 1→2→3） |
| StudyLogicTest | 7 | 原有新词/复习抽词、历史与收藏搜索筛选、进度合并、备份校验 |
| NavigationTest | 1 | 原有展开释义与例句、前后切词、收藏/掌握、历史搜索与清空、设置、Activity 重建后原卡片与次数保留 |

数据库版本为 3；保留原有 schema 2，并生成 schema 3。迁移不删除旧表，不重建用户数据，不采用 destructive migration。旧版全部词条挂到固定 ID `builtin`，旧词条 ID、progress、daily_views 保持。

## 数据与任务处理

- 新建和导入词库使用 UUID。自建词条也使用独立 ID；重命名不改变关联关系。
- 更新采用 Upsert，不删除并重建词条；保留进度，并用旧值补足导入中的空可选字段。
- 导入预览后在提交事务中重新读取目标和现有词条，防止目标失效及预览后的重复冲突。分批写入仍位于同一事务。
- 软删除只更新词库状态。永久删除在两次确认后清理无任何词库引用的词条及其进度、每日记录，共享词保留。
- 进行中的操作和预览存于 ViewModel，同步互斥锁阻止重复点击。大段粘贴草稿不写入 Bundle，避免旋转时 Binder 大小超限。
- 文件读取、解析、预览计算和写入均使用后台调度。只使用系统文档选择器和 ContentResolver，不主动读取剪贴板。
- 进程终止后未提交草稿和预览需要重新选择；提交中的数据库事务整体完成或回滚，不自动重新执行。

## 尚未实测与限制

1. 没有连接的真机或可启动 AVD；未实际安装运行此 APK，未执行真实手机重启、覆盖升级或厂商后台清理。数据库重开、迁移及 Activity 重建测试已经通过。
2. 未实际操作系统文件选择器的取消、云盘提供者授权、授权撤销或磁盘空间不足。取消回调仅在 URI 非空时开始操作；ContentProvider 正常/失败读取已在本地测试。
3. 深色模式和软键盘复用现有 Material3 主题及 Insets/滚动处理；尚未完成真机横竖屏、多尺寸、大字体和 TalkBack 的视觉/交互验收。
4. 大词库已验证 50000 条解析及 5000 条数据库写入，未测量低端手机导入 50000 条的耗时和内存峰值。当前项目仍整体观察所选词库，未引入分页或重构学习流程。
5. 原版 JSON 学习备份协议继续兼容，但不保存词库分组/名称/回收站状态，上限仍为 10000 条和 10 MB。超过上限会在打开输出流之前明确报错，不写出无法恢复的备份。
6. 原项目没有发音实现，本次未新增语音功能；现有背词、复习、历史、收藏、掌握状态与学习记录均保留。
7. 当前 APK 使用原有 Debug 构建配置。未读取或披露签名密钥、证书详情，也未变更 release 签名。

## 真机验收步骤

1. 保留旧版应用数据，用相同签名覆盖安装 Debug APK，检查旧词条、次数、收藏、掌握与每日记录。
2. 设置→词库管理→添加词库；分别新建逐词添加、粘贴 Tab 文本、选择 CSV/TXT/项目 JSON。取消选择后应保持原状态。
3. 导出 CSV 模板，用中文释义、引号内逗号与换行填写后导入。选择不可读文档、空文件、错误编码和混合错误行，核对反馈及有效词条确认。
4. 追加大小写重复词，分别跳过和更新；更新空的音标/例句应保留旧内容，学习进度不丢失。
5. 预览和导入时旋转、退后台再返回、连续点确认；导入只发生一次。结束后点击开始学习，验证复习与收藏。
6. 重命名、长按多选删除，依次覆盖删除当前词库和最后一个可用词库；回收站恢复后核对记录。内置词库隐藏后重启再恢复。
7. 在回收站对自建词库执行两次永久删除确认，核对其他词库及共享记录不受影响。
8. 重启手机，检查当前词库、回收站和学习进度；在深色、横屏、软键盘、大字体及 TalkBack 下验收。

## 报告位置

- 测试 XML：`app/build/test-results/testDebugUnitTest/`
- 测试 HTML：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint：`app/build/reports/lint-results-debug.html`
- 使用说明：`README.md`
- 模板：`tools/import-template.csv`，也可从应用内导出。
