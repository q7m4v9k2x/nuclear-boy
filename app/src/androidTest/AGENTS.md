## 目录职责

`app/src/androidTest` 承载整体层设备端验证，用于在真实 Android 设备或模拟器上启动完整 APK 并操作用户可见界面。

## 边界

这里只写端到端 UI 旅程和发布门禁测试，不实现业务逻辑、模型协议、Agent 循环或通用 UI 组件。

## 允许依赖

可以依赖 AndroidX Test、UIAutomator、JUnit、目标 App 包和用户可见语义。

## 禁止事项

不要在测试输出、断言消息或日志中打印 API Key、Token、Authorization 头、完整请求体或个人数据。测试应通过可见状态和脱敏错误判断结果。需要给干净安装的 connected test 注入调试模型时，使用 Gradle DSL 从未跟踪的 `android-test-secrets.properties`、`NB_TEST_*` 环境变量或 `nbAndroidTestSecretsFile` 本地文件读取，避免把密钥放在命令行 instrumentation 参数里。

写入临时模型、会话或 workspace 状态的 UI 测试必须在 finally 中恢复原状态，不得把假地址、假模型或固定会话残留给后续真实前端门禁。

## 常用命令

- `./gradlew :app:connectedDebugAndroidTest`
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nuclearboy.app.modeltest.ProviderLightweightConnectivityTest`
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nuclearboy.app.uitest.ChatUserJourneyTest`
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nuclearboy.app.uitest.ChatMultiTurnUserJourneyTest`

## 验证方式

### 应用更新验证

- 更新发布前验证相关逻辑和两种安装包；Release 公开为 latest 后，再分别从旧版正式包、旧版调试包走完整在线升级，具体顺序见 [发布指南](../../../.github/RELEASING.md)。目前没有专用的更新 instrumentation 测试类，不能用聊天测试通过代替升级验证。
- 从设置页检查更新，经匹配 APK 下载、大小/摘要校验、系统未知来源授权和覆盖安装，核对实际包名、versionName、versionCode；升级后确认应用可启动、原会话及配置保留，再手动检查应显示已是最新版本。
- 使用专用测试会话和配置；避免通过卸载、清数据绕过覆盖安装问题。临时改动的设备网络、权限或应用配置应在测试结束后恢复。
- 记录两种构建各自的旧版 → 新版结果、Android 版本和证据位置。模拟器与真机分开标注；未连接真机时明确记为未验证，不宣称真机通过。

### 模型与聊天验证

发布前优先运行模型轻量连通性和聊天真实旅程测试，确认当前选中模型能完成最小 ping，App 可启动、系统权限弹窗可处理、聊天输入可操作、发送按钮可用、正式聊天失败诊断卡可见且语义可定位，并显示脱敏诊断指纹和正式链路测试口径、工具受限卡可见且显示 `tool.protocol` 与正式链路测试口径，且可覆盖英文网关工具不可用表达、模型自述无文件/命令访问权限表达、无法联网/GitHub/服务器/ADB/SSH 访问能力表达、无法操作屏幕/App/点击/安装/控制手机表达、只提供指导/步骤不能替用户执行表达、无法验证/确认/运行测试/缺少运行环境表达、API/接口调用和远程配置类草稿预警、后台/网关/服务端自然语言配置草稿预警、加入/接上/放到/挂到网关类口语执行草稿预警、填到后台配置/部署到服务器类口语执行草稿预警、网关模型换成/模型路由改为类口语执行草稿预警、默认模型设为/设置成类口语执行草稿预警、聊天模型切到/当前模型改用类口语执行草稿预警、网关模型删掉/provider 停用类口语执行草稿预警、API key 轮换/密钥撤销类口语执行草稿预警、账号充值/额度补充/账单开票类口语执行草稿预警、用户封禁/管理员权限/密码重置类口语执行草稿预警、订单取消/物流修改/工单指派类口语执行草稿预警及接口调用记录证据要求、API 学习/咨询问题和参数/请求体/header/鉴权/字段用法咨询不显示工具能力预警、工具型任务发送后证据提示可见、缺少工具证据时后置结果复核警告卡可见且显示 `tool.evidence.missing` 与正式链路测试口径、正式聊天完成且没有空回复或误导错误。涉及模型/聊天路径时，除单轮 smoke 外还要运行多轮真实对话测试，覆盖连续输入、上下文延续、工具调用可见性、错误残留、停止状态恢复和最终自检不自报“有问题/误报/存在不一致/自相矛盾”。connected test 干净安装后若模型配置为空，应通过未跟踪本地配置或环境变量注入脱敏管理的调试模型配置，不要在命令行写 API Key；受限 ROM 上需要先确认 `adb shell su -c id` 可用，否则不得用该测试结果替代真实前端验证。
