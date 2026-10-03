# Android 发布

更新器按安装包名选择 APK：正式版 `com.nuclearboy.app` 对应 `*-release.apk`，调试版 `com.nuclearboy.app.debug` 对应 `*-debug.apk`。两种包名不能互相覆盖升级。每次 GitHub Release 必须包含两个 APK。

1. 同步 `app/build.gradle.kts` 和 `gradle/libs.versions.toml` 的版本号，递增 versionCode，补充版本记录。
2. 使用与已发布版本相同的签名构建。当前工程两种构建均使用发布机器的本地 debug keystore；不要换成另一台机器新生成的密钥，不要提交 keystore。
3. 执行 `./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon`，完成变更涉及的设备回归。更新流程需用旧版正式包验证检查、下载和覆盖安装。
4. 预检：`pwsh .github/scripts/Publish-Release.ps1 -Version 1.1.76`。脚本直接读取两个 APK 的包名、版本号和签名，校验 SHA-256；缺任一 APK、版本或签名不一致均中止。
5. 提交并推送到自有仓库 `q7m4v9k2x/nuclear-boy` 的 main。将本次说明保存到本地 UTF-8 文件，然后执行 `pwsh .github/scripts/Publish-Release.ps1 -Version 1.1.76 -NotesFile <说明文件> -Publish`。

脚本先创建草稿，上传两种 APK，核对 GitHub 返回的附件大小和 SHA-256，再公开为最新版本。上传失败时保留草稿，可用相同参数重试；相同附件必须校验一致。禁止先公开空 Release 再补附件。已公开的版本不会被脚本覆盖。

认证读取 `GITHUB_TOKEN` 或本地 `~/.github_cli/config.json`；SDK 读取 `-SdkRoot`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 或未跟踪的 `local.properties`。凭据和 APK 都不进入 Git 源码。
