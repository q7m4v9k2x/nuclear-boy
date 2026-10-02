## 目录职责

`app/src/debug` 承载仅 debug 构建启用的 Manifest 覆盖和调试入口注册。

## 边界

这里只能放调试构建专用配置，不承载正式业务能力。可注册 ADB 诊断、调试写配置、固定测试会话注入等开发入口，但 release 包不得暴露这些组件。

## 允许依赖

可以引用 main 源集里已经实现的 debug 守卫接收器、诊断编排和 Android 基础组件。

## 禁止事项

不要在 debug manifest 中注册会泄露明文 API Key、Token、个人数据或绕过正式权限边界的 release 能力。调试广播必须在代码里再次校验 `BuildConfig.DEBUG`。

## 常用命令

- `./gradlew :app:assembleDebug`
- `adb shell am broadcast -a com.nuclearboy.app.RUN_DIAGNOSTICS -n com.nuclearboy.app.debug/com.nuclearboy.app.diagnostics.DiagnosticsReceiver`
- `adb shell am broadcast -a com.nuclearboy.app.DEBUG_SAVE_CUSTOM_MODEL -n com.nuclearboy.app.debug/com.nuclearboy.app.diagnostics.DebugModelConfigReceiver ...`
- `adb shell am broadcast -a com.nuclearboy.app.DEBUG_SAVE_PC_BRIDGE -n com.nuclearboy.app.debug/com.nuclearboy.app.diagnostics.DebugPcBridgeConfigReceiver --es url "ws://<电脑IP>:7860" --es token "<token>" --ez enabled true`（可选 `--es run_cli claude --es run_prompt "..."` 实跑一条任务，结果看 logcat 标签 NuclearBoyDebugPcBridge）
- `adb shell am broadcast -a com.nuclearboy.app.DEBUG_SEED_CONVERSATION -n com.nuclearboy.app.debug/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver --es project_id __general__ --ez select_after_write true`

## 验证方式

构建 debug APK，安装后通过 ADB 广播验证诊断入口和调试配置入口只在 debug 包可用，日志不得输出明文密钥。
