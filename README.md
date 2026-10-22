# ColorOS 录音增强

一个作用于 ColorOS 系统录音机（`com.coloros.soundrecorder`）的 [LSPosed](https://github.com/LSPosed/LSPosed) 模块。

通话录音入库后**自动触发"录音转文本"**，无需手动逐条点击；录音列表右上角菜单支持**一键批量转文本**；原生搜索原本只能搜"摘要"，本模块为其注入了**转写正文全文搜索**能力。

> 模块无界面、无桌面图标，全部功能均在录音机进程内通过 Hook 实现。

## ✨ 功能

### 1. 一键批量转文本

在录音列表（BrowseFragment）右上角展开菜单中注入两个菜单项（位于"设置"下方）：

| 菜单项     | 行为                                                                   |
| ---------- | ---------------------------------------------------------------------- |
| **转文本** | 扫描全部通话录音（最新优先），跳过已转完的，取最多 10 条未转的批量触发 |
| **转全部** | 同上但不设上限，库里所有未转的通话录音全部入队                         |

批量任务由**事件驱动的串行队列**（`ConvertQueue`）逐条推进：每条转写完成后（Hook 完成回调）延迟 3s 再触发下一条，长录音也会等到真正转完才继续；另设超时保底防止队列卡死。

### 2. 转写正文全文搜索

原生搜索只能命中"摘要"（AI 生成，非全部录音都有）。本模块在后台为每份转写 txt 建立 SQLite FTS 索引，并将命中结果**伪装成摘要条目**注入原生搜索结果：

- 关键词高亮、点击跳转播放页等 UI 全部复用宿主原生逻辑，零自定义界面；
- 注入条目统一打上「转文本：」标签，与原生"摘要"区分；
- 转写完成 / 录音删除时增量同步索引（Hook 落库口与删除口），另有一次搜索前懒对账兜底清理。

## 🔧 实现原理

```
通话录音写库（所有链路）
        │
        ▼
RecorderProvider#insert/bulkInsert  ──after──▶  record_type==3 ?
        │                                            │ 反查 MediaStore 拿 mediaId
        │                                            ▼
        │                                    TriggerHandle（去重/防重）
        │                                            │ 反射 startConvertTask
        │                                            ▼
        │                                    ConvertQueue 串行队列
        │                                            │ 完成回调 hook
        │                                            ▼
        │                              ConvertIndexer → FTS 索引
        │
搜索时：摘要关键字查询 hook ──after──▶ FTS 查询转写正文，注入结果
```

**Hook 定位采用两层策略，兼顾稳定性与跨版本兼容：**

- **层 1**：直接 `Class.forName` 已知类名（录音机的 Kotlin 业务类大多未混淆）；
- **层 2**：层 1 失败时，用 [DexKit](https://github.com/LuckyPray/DexKit) 在后台线程按结构特征 / SQL 字符串锚点重新定位（不阻塞 App 启动），跨版本漂移也能兜底。

所有 Hook 均为 `PROTECTIVE` 异常模式 + 全量 try-catch：模块自身异常只进 Xposed 日志，绝不影响录音机原有流程。

**核心源码结构**（`app/src/main/java/.../lsp_colorOSRecord/`）：

| 文件                                          | 职责                                 |
| --------------------------------------------- | ------------------------------------ |
| `ModuleMain.kt`                               | 模块入口（libxposed 现代 API）       |
| `HookInstaller.kt`                            | Hook 安装编排 + DexKit 兜底定位      |
| `TriggerHandle.kt`                            | 转文本触发器（去重、防重、反射调用） |
| `ConvertQueue.kt`                             | 完成事件驱动的串行转文本队列         |
| `ScanButtonInjector.kt`                       | 注入"转文本 / 转全部"菜单项          |
| `SearchInjector.kt`                           | 转写正文搜索结果注入                 |
| `ConvertIndexer.kt` / `ConvertSearchIndex.kt` | FTS 索引调度与实现                   |
| `XLog.kt`                                     | 日志封装                             |

## 📱 兼容性

- **宿主**：ColorOS 系统录音机 `com.coloros.soundrecorder`，实际开发与测试基于 **17.8.8** 版本；
- **框架**：LSPosed（libxposed 现代 API，API ≥ 102）；
- 其他 ColorOS / realme UI 版本未逐一验证，类名漂移时依赖 DexKit 兜底，欢迎反馈 issue。

## 🔨 构建

要求：JDK 17 + Android SDK 37（AGP 9 已内置 Kotlin 编译支持）。

```bash
./gradlew assembleRelease
```

- 版本号全自动：`versionCode` = 当前分支提交总数，`versionName` = 最近的 `v*` tag（如 `v1.2` → `1.2`），无需手动维护；
- 正式签名：在项目根目录创建 `keystore.properties`（已被 gitignore，不进仓库）：

  ```properties
  storeFile=release.jks
  storePassword=xxxxxx
  keyAlias=xxxxxx
  keyPassword=xxxxxx
  ```

  该文件不存在时自动回退 debug 签名，便于本地验证。

## 📥 安装

1. 构建 APK 后安装到手机（或从 [Releases](https://github.com/imRoxy2222/lsp_colorOSSSoundRecordEnhance/releases) 下载）；
2. 在 LSPosed 中启用本模块；
3. 作用域勾选 **ColorOS 录音机**（`com.coloros.soundrecorder`，模块声明为静态作用域，一般会自动勾选）；
4. 重启系统界面或重启手机，打开录音机验证。

验证方式：打开录音机后，在 LSPosed 管理器日志中能看到 `module loaded` / `hooked ...` 等日志；通话录音入库后数秒内出现"转文本已完成"提示。

## 🙋 FAQ

**Q：手动录的音也会被自动转文本吗？**
不会。自动触发仅针对 `record_type == 3` 的通话录音，手动录音走的是另一条链路，不受影响。

**Q：QQ / 微信电话录音为什么没有自动转？**
VoIP 通话结束时录音机进程通常不在运行，Hook 不存在。打开录音机后点右上角菜单的"转文本"或"转全部"即可批量处理。

**Q：搜索里出现的「转文本：」结果是什么？**
这是模块注入的转写正文全文搜索命中，非原生摘要。App 原有的关键词高亮和点击跳转均正常可用。

## 📄 许可证

[MIT License](LICENSE) © 2026 Frank2222

## ⚠️ 免责声明

本项目仅供学习交流与个人使用，通过 Hook 技术修改第三方应用行为，与 OPPO / ColorOS 无任何关联。使用本模块产生的任何后果由使用者自行承担，请勿用于非法用途。

