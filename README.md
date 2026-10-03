# NUCLEAR BOY (核弹男孩)

> 温暖、智能、人性化的移动端 AI 编程助手 · v1.1.76

把一个能写代码、控手机、还能**远程操控电脑上 Claude Code / Codex / OpenCode** 的 AI 助手装进口袋。

---

## ⚠️ 声明

**本仓库非原作者上传。**

- **原作者**: [mzpr00](https://github.com/mzpr00) (穆再排尔·穆合塔尔)
- **说明**: 本仓库为原项目的复制/镜像（mirror），仅供学习研究使用。所有代码版权归原作者 mzpr00 所有。

---

## 核心能力

**💬 聊天与智能体**
- DeepSeek 驱动的对话，聊天 / 思考 / 专家三档模型路由
- Agent 引擎 + 工具调用：文件读写、网络搜索、网页抓取、Python 执行
- run_python + Chaquopy Java 桥接：从 Python 直接控制手机（剪贴板、振动、闪光灯、通知、闹钟、日历、WiFi…）
- 三层记忆系统，自动记住用户偏好并注入每轮对话
- **自定义指令**：写下你的人设/规则，定制 AI 行为
- 消息复制 / 代码块复制 / 编辑重发 / 删除 / 会话内搜索 / 对话导出分享 / 接收外部分享

**🖥️ 远程电脑控制（特色能力）**
- 手机把编程任务下发给电脑上的 **Claude Code / Codex / OpenCode** 执行，结果流式回传
- 会话续传（含 `session="last"`）、断线无损恢复（增量补发漏掉的输出）、取消联动、任务列表
- **远程终端**：手机上直接操作电脑的真实终端（ConPTY 全屏 TUI 模拟器，支持光标定位/颜色/CJK 对齐/方向键）
- 只读浏览 + 写入（需手机审批）电脑文件、列出 Claude 历史会话
- git worktree 隔离执行、危险操作转手机审批
- **连接方式**：局域网 / USB 共享网直连，或自建公网中继外网控制
- **端到端加密**：AES-256-GCM 加密通道，走中继也看不到任务内容和 token
- 扫码配对、任务完成系统通知

> 远程电脑全部核心能力（CLI 任务 / 文件读写 / 会话列表 / 加密 / 终端）均已**真机端到端验证**。

## 技术栈

- **UI**: Jetpack Compose + Material 3 (暖色主题 #FF8C42)
- **DI**: Hilt (SingletonComponent)
- **网络**: OkHttp 4 + 流式 SSE / WebSocket
- **数据库**: Room (SQLite)
- **Python**: Chaquopy 15 (嵌入式 CPython)
- **序列化**: kotlinx.serialization
- **电脑端桥接**: Python（websockets / pywinpty / cryptography），见 [`pc-bridge/`](pc-bridge/)

## 构建手机端

1. 安装 Android Studio + SDK 35
2. 设置 `ANDROID_HOME` 环境变量
3. `./gradlew assembleDebug`

或直接到 [Releases](https://github.com/q7m4v9k2x/nuclear-boy/releases) 下载 APK。

覆盖更新请选择与当前安装一致的构建：正式版用 `*-release.apk`，调试版用 `*-debug.apk`（设置页“关于”可查看 BUILD）。每个版本同时发布两种 APK，发布流程见 [.github/RELEASING.md](.github/RELEASING.md)。

## 1.1.76 重点

- 补齐 v1.1.75 正式版 APK，解决旧正式版检查更新提示没有适配 APK 的问题；后续发布先校验并上传 Debug/Release 两种包，再公开版本
- 缺附件或网络检查失败不再触发 6 小时成功缓存；缺附件提示明确指出所需构建，关闭通知不再导致检查更新误报失败
- 增加更新检查回归测试，覆盖两种构建匹配、发布缺包后重试、通知权限拒绝和版本比较

## 1.1.75 重点

- 生成文本时自动滚动只在用户停留底部时生效；等待尾部项完成布局，避免跳回刚发送的用户消息；开始上翻后立即暂停，新增流式 SSE + Android 15 UIAutomator 回归测试
- 自动定位改为列表实际尾部，避免每个文本块把页面拉回发送消息顶部

## 1.1.74 重点

- 流式回复保持底部跟随；用户向上滚动后不会被强行拉回最新消息开头
- Skill Creator 支持项目级与全局级 Skill，创建后当前会话立即显示并可调用
- 设置中可按需开启 Android「所有文件访问」，`run_python` 使用 `scope=global` 读写共享存储

## 1.1.73 重点

- 新对话会先归档当前消息，可从“历史对话”恢复；应用重启后继续保留当前会话和未完成消息。
- Skill Creator 创建 Skill 后立即刷新当前会话的 Skill 数量、列表和工具注册。
- GitHub 更新检测与一键更新增强：校验 HTTPS APK、包名、版本、大小和 SHA-256；进程重启或授权返回后可继续安装。
- 修复工具受限提示、流式错误恢复和测试配置注入，完成授权真机多轮对话与历史恢复回归。

## 1.1.72 重点

- 首次安装或升级时等待内置 Skill 复制完成，再扫描并注册 `skill_*` 工具，避免首启 Skill 列表为空或工具缺失。
- 串行化 Skill 安装、项目 Skill 切换和工具注销，减少并发重复注册与上下文刷新不及时。
- 稳定调试会话种子的项目选择，已完成模拟器和已授权真机的历史恢复、更新检测与失败提示回归。

## 1.1.71 重点

- 更新检测统一读取 `q7m4v9k2x/nuclear-boy` GitHub Releases
- 按当前 Debug/Release 构建选择对应 APK，设置页可直接下载并打开安装

## 1.1.70 重点

- 重启后恢复上次打开的项目和对话；生成中途关闭应用时保留可恢复的对话尾部
- Skill Creator 创建项目 Skill 后立即刷新 Skill 数量、工具注册和管理列表
- 修复旧记忆覆盖最新项目选择、Skill 工具注销残留、自动首条消息竞态，以及错误状态被误标记为完成

## 1.1.69 重点

- 修复空响应、普通 JSON、截断或格式异常的流式网关被误判为成功。
- 修复正式聊天错误状态被后续完成事件覆盖，并移除固定工具调用轮数限制。
- 修复 Android 15 lint 权限与主题兼容，更新检查统一指向本仓库。
- 已通过单元测试、lint、Debug/Release 构建和 Android 15 模拟器回归。

- 修复第三方 OpenAI/Anthropic 兼容网关把普通回答误判为工具受限的问题。
- 修复 Python 工具的项目工作目录、沙箱路径边界和超时任务取消，避免越权访问与线程泄漏。
- 修复 Chaquopy 启动时包枚举和 Python 版本读取、`write_file` 创建/修改判定，以及无 API Key 重试时丢失用户消息。
- 已通过模块单测、Android 15 模拟器工具链诊断和 `assembleDebug` 构建验证。

## 1.1.35 重点

- 正式聊天失败诊断继续扩展：新增 `401` 鉴权失败、`403` 权限不足、`429`/额度限流、连接失败和超时分类。
- 聊天页会直接给出对应下一步，例如核对 API Key、换有权限的模型/Key、检查余额额度、确认 VPN/地址/端口。
- 真机 UI 门禁新增鉴权失败卡片断言，确认用户不是只看到泛化“处理时遇到了问题”。
- App 版本同步到 `1.1.35 / versionCode 145`，方便真机与 Release 对齐。

## 远程电脑使用指南

让手机上的核弹男孩控制电脑上的 Claude Code / Codex / OpenCode（电脑端桥接源码在 [`pc-bridge/`](pc-bridge/)）：

1. **电脑端**（一次性）：
   ```bash
   cd pc-bridge
   pip install -r requirements.txt     # websockets / pywinpty(远程终端) / cryptography(加密) / qrcode(扫码)
   python bridge.py init               # 生成 token，记下显示的 ws:// 地址
   python bridge.py install-autostart  # 注册登录自启（可选）
   python bridge.py serve              # 启动服务
   ```
2. **手机端**：设置 → 远程电脑 → 打开开关。
   - **扫码配对（推荐）**：电脑端 `python bridge.py pair` 打印二维码，手机点「📷 扫码配对」对准电脑屏幕，地址和 token 自动填好。
   - 或手动填电脑地址（如 `ws://192.168.1.10:7860`）和 token → 测试连接。
   - 走公网中继时建议开启「端到端加密」。
3. **使用**：直接对核弹男孩说——
   - “让电脑上的 claude 修复 D:/myproject 的编译错误”
   - “用 codex 在电脑上写个脚本”（自动返回会话 ID）
   - “继续刚才那个任务，再加上单元测试”（AI 用 session=last 续传）
   - “看看 D:/myproject 里有什么 / 读一下 build.gradle”（pc_list_dir / pc_read_file）
   - “电脑上在跑什么？”（pc_task_list）；实验性改动可要求“隔离执行”（worktree）
4. **远程终端**：设置 → 远程电脑 → 「🖥️ 远程终端」，直接在手机上操作电脑真终端（电脑端 ConPTY，需 `pip install pywinpty`）。

特性：任务输出实时流到聊天工具卡片；手机断线任务不丢（重连增量取回）；取消对话自动终止电脑任务；鉴权失败 5 次封禁 5 分钟防爆破。

### 外网控制（自建公网中继）

不在同一网络时，用一台有公网 IP 的服务器做中继，电脑主动反连、手机经中继接入：

1. **公网服务器**：`python relay/relay_server.py --port 8970 --key 你的口令`（中继只做 room 内透传，不解析业务）。
2. **电脑端**：`python bridge.py serve --relay ws://服务器IP:8970 --relay-key 你的口令`——启动后日志打印 `room=...` 和「手机填地址」。
3. **手机端**：地址填中继 client URL（如 `ws://服务器IP:8970/client/<room>?key=你的口令`），开启端到端加密后，中继只看到密文。

## 项目结构

```
app/         主入口 + DI + 导航 + 主题        agent-core/  Agent 引擎 + 工具注册
common/      共享模型/工具/纯逻辑             api-deepseek/ DeepSeek 客户端
memory/      Room 三层记忆                    remote-pc/   远程电脑桥接客户端
ui-chat/     聊天界面                         ui-workspace/ 文件浏览 + Diff
skills/      Skill 管理 + 市场               tools-docgen/ Word/Excel 生成
python-bridge/ Chaquopy 沙箱                 pc-bridge/   电脑端桥接守护进程（Python）
```

## 更新日志

完整版本历史见 [CHANGELOG.md](CHANGELOG.md)（本会话从 v1.0.81 迭代至 v1.1.7，涵盖远程电脑全套能力、核心聊天增强、代码质量加固与真机验证）。
