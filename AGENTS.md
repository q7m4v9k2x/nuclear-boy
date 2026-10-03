## 目录职责

仓库根目录承载 Android 多模块工程的整体编排、Gradle 入口、仓库级说明和发布版本信息。

## 边界

根目录只放整体级文件，例如 `settings.gradle.kts`、根 `build.gradle.kts`、`README.md`、仓库级交接文档、版本记录，以及 `ccswitch-prompts/` 这类仓库级提示词资料目录。具体实现必须下沉到各模块。

## 允许依赖

整体层可以引用各模块；模块之间依赖以 `settings.gradle.kts` 和各模块 `build.gradle.kts` 为准。

## 仓库与文档维护

- 修改、推送和发布仅面向自有仓库 `q7m4v9k2x/nuclear-boy`；上游仓库只作参考，不向其写入。
- 功能、模块边界、测试入口或发布流程变化时，同批更新受影响目录的 `AGENTS.md`、相关使用说明和版本记录；提交前检查规则与实际代码、脚本一致。
- `AGENTS.md` 记录长期约束和可复用的验证方法；具体版本、故障原因、设备实测结果放入 `CHANGELOG.md` 或发布说明。避免逐版本堆积重复规则。
- 仅修改文档且不影响构建时，核对命令、路径、链接并执行 `git diff --check`，无需升级 APK 版本或重复打包。

## 发布要求

按 [.github/RELEASING.md](.github/RELEASING.md) 执行发布。每版必须同时提供正式版和调试版 APK，保持对应包名和既有签名，同步版本声明并递增 versionCode。使用发布脚本先上传草稿，校验两种附件齐全且完整后才公开，不先发布空 Release。

## 禁止事项

不要在根目录新增业务实现代码。不要在根目录存放 API Key、Token、签名密钥、个人数据或构建产物。提示词资料目录只能放脱敏规则、流程说明和提示词模板。

## 常用命令

- `./gradlew assembleDebug`
- `./gradlew test`
- `./gradlew clean assembleDebug`

## 验证方式

优先验证 `./gradlew assembleDebug`。涉及单元逻辑时补跑对应模块测试。

涉及用户反馈的问题时，补充对应回归场景，并在可用设备上验证实际操作流程。验证记录应包含版本、构建类型、Android 版本、模拟器或真机、操作结果及未验证项；模型连通性或编译通过不能代替聊天、工具执行和覆盖升级的实际验证。
