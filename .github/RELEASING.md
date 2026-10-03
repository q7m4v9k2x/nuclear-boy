# Android 发布

更新器按安装包名选择 APK：正式版 `com.nuclearboy.app` 对应 `nuclear-boy-<版本>-release.apk`，调试版 `com.nuclearboy.app.debug` 对应 `nuclear-boy-<版本>-debug.apk`。两种包名不能互相覆盖升级。每次 GitHub Release 必须包含两个 APK，发布目标仅为 `q7m4v9k2x/nuclear-boy`。

1. 同步 `app/build.gradle.kts` 和 `gradle/libs.versions.toml` 的版本号，确保 versionCode 大于上一发布版，补充版本记录和受影响目录的规则文档。脚本检查两包 code 一致，不代替与上一版比较。
2. 使用与已发布版本相同的签名构建。当前工程两种构建均使用发布机器的本地 debug keystore；不要换成另一台机器新生成的密钥，不要提交 keystore。
3. 执行 `./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon`，完成变更涉及的设备回归。仅在依赖缓存齐全时可加 `--offline`。新版本公开前完成更新逻辑测试、两种构建的安装启动检查，并准备保留测试会话和配置的旧版安装用于升级验证。
4. 在 PowerShell 中将 `$releaseVersion` 设为本次 APK 的版本号（不带 `v`），执行预检：`pwsh .github/scripts/Publish-Release.ps1 -Version $releaseVersion`。不带 `-Publish` 仅检查本地 APK，不创建 Release。脚本读取两包实际包名、版本号、签名并计算 SHA-256；缺任一 APK、版本不符、两包 code 不同或签名与既有证书不符均中止。
5. 提交并推送到自有仓库的 main，保持工作区干净；如同名 tag 已存在，必须指向本次 HEAD。将发布说明保存到本地 UTF-8 文件，并将路径赋给 `$releaseNotesFile`，执行 `pwsh .github/scripts/Publish-Release.ps1 -Version $releaseVersion -NotesFile $releaseNotesFile -Publish`。
6. 公开后确认 GitHub latest 指向本版且两个附件可下载，分别从旧版正式包、旧版调试包在应用内完成检查更新、下载、大小/SHA-256 校验、系统未知来源授权和覆盖安装。用 `adb shell dumpsys package com.nuclearboy.app` 及 `adb shell dumpsys package com.nuclearboy.app.debug` 核对安装后版本，并确认原会话及配置保留、应用可启动，再次手动检查显示已是最新版本。

脚本先创建草稿，上传两种 APK，核对 GitHub 返回的附件数量、上传完成状态、大小和 SHA-256，再公开为最新版本。草稿不得混入其他 APK。上传失败时保留草稿，仅在目标提交和已有附件一致时用相同参数重试；出现冲突先核查原因。禁止先公开空 Release 再补附件。已公开的版本不会被脚本覆盖。

验证记录写明版本、构建类型、Android 版本、模拟器或真机、旧版 → 新版结果及未验证项；截图和脱敏日志保留在忽略的 `app/build/` 下，简要结论写入发布说明。模拟器结果不能表述为真机通过。保留用户数据，不通过卸载或清数据替代覆盖升级验证。

认证读取 `GITHUB_TOKEN` 或本地 `~/.github_cli/config.json`；SDK 读取 `-SdkRoot`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 或未跟踪的 `local.properties`。凭据和 APK 都不进入 Git 源码。
