## 目录职责

`app` 是整体层模块，负责 Android 应用入口、Hilt 装配、导航、主题、前台服务、更新流程和跨模块协调。

## 边界

这里可以装配下层模块，但不应实现模型协议、Agent 核心循环、文件工具细节或通用 UI 组件逻辑。

## 允许依赖

可以依赖所有业务模块：`common`、`api-deepseek`、`agent-core`、`python-bridge`、`memory`、`skills`、`tools-docgen`、`ui-chat`、`ui-workspace`。

## 禁止事项

不要在装配日志中输出完整工具参数、明文密钥或个人数据。真机 instrumentation 需要调试模型参数时，优先从未跟踪的 `android-test-secrets.properties`、`NB_TEST_*` 环境变量或 `nbAndroidTestSecretsFile` 指向的本地文件注入，不要把 API Key 写进命令参数、源码或 Release 文本。新增跨模块能力时先在低层模块提供稳定接口。

## 更新流程约束

- 更新源固定为自有仓库的 GitHub Releases。优先选择与当前包名匹配的构建，禁止用明确标为另一构建的 APK 补位；兼容历史无构建标识的附件时，下载后仍须校验实际包名和 versionCode。
- 缺少匹配 APK、HTTP 或解析失败不得写入成功检查缓存；手动检查须绕过自动检查间隔。缺包提示要说明版本和所需构建类型。
- 通知权限关闭或通知发送失败不得阻断可用更新结果；只有实际发送成功才记录已通知版本。协程取消必须继续传播。
- 附件大小和摘要应传递至下载及通知入口。新发布包必须有可核验的 SHA-256；历史无摘要附件的兼容行为不得被误写成已完成摘要校验。
- 更新器变更补跑 JVM 回归，并按 [发布指南](../.github/RELEASING.md) 分别验证正式版、调试版的应用内更新和覆盖安装。

## 常用命令

- `./gradlew :app:assembleDebug`
- `./gradlew :app:compileDebugKotlin`

## 验证方式

重点验证 Hilt 装配、APK 打包、权限声明、设置页流程、运行时工具注册和 `app/src/androidTest` 真实 UI 旅程门禁。
