"""
Skill Creator — 在项目中快速创建新的 Skill 模板。

用法: skill_skill-creator skill_name=my-tool description="我的工具" language=python scope=project

输出: 在项目级 .agent/skills/ 或全局 Skills 目录下创建完整的 skill 文件结构
"""
import os
import sys
import re

def run(skill_name: str, description: str, language: str = "python", scope: str = "project") -> str:
    """Create a skill in the selected scope.

    The host changes cwd to the installed skill directory.  Resolve the
    destination from host-provided absolute roots instead of a relative
    ``.agent/skills`` path, which otherwise writes into the creator itself.
    """
    skill_name = (skill_name or "").strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", skill_name):
        return "❌ skill_name 只能包含小写字母、数字、下划线和连字符，且长度不超过 64"

    scope = (scope or "project").strip().lower()
    if scope in ("global", "全局", "user", "用户"):
        scope = "global"
        base_dir = os.environ.get("NB_GLOBAL_SKILLS_DIR", "")
        scope_label = "全局"
    elif scope in ("project", "项目", "workspace", "当前项目"):
        scope = "project"
        base_dir = os.environ.get("NB_PROJECT_SKILLS_DIR", "")
        scope_label = "项目"
    else:
        return "❌ scope 只能是 project（项目级）或 global（全局）"

    if not base_dir:
        return f"❌ 未找到{scope_label} Skills 目录，请先打开一个项目后重试" if scope == "project" else "❌ 未找到全局 Skills 目录"

    # The host policy checks these roots; name validation above prevents path
    # traversal even when called outside the normal host.
    skills_dir = os.path.join(base_dir, skill_name)
    os.makedirs(skills_dir, exist_ok=True)

    # 生成 skill.yaml
    yaml_content = f"""name: {skill_name}
version: 0.1.0
description: "{description}"
author: "user"
entry_point: "main:run"

permissions:
  filesystem:
    read: [workspace/**]
    write: [workspace/**]
  network:
    allowed: false
  packages:
    allowed: []
  shell:
    allowed: false

parameters:
  - name: input
    type: string
    description: "输入参数"
    required: true
"""
    yaml_path = os.path.join(skills_dir, "skill.yaml")
    with open(yaml_path, "w", encoding="utf-8") as f:
        f.write(yaml_content)
    print(f"[OK] 已创建 skill.yaml → {yaml_path}")

    # 生成入口脚本
    if language == "python":
        main_content = '''"""
{description}
"""
import os, sys, json

def run(input: str = "") -> str:
    print(f"[{skill_name}] 开始执行...")
    print(f"输入: {input}")
    # TODO: 在这里编写你的核心逻辑
    print(f"[{skill_name}] 完成!")
    return json.dumps({{"status": "ok", "result": "done"}}, ensure_ascii=False)
'''.replace("{skill_name}", skill_name).replace("{description}", description)
    else:
        main_content = f"# {skill_name} — {description}\n# Language: {language}\ndef run(input=''):\n    print('Hello from {skill_name}!')\n    return 'done'\n"

    main_path = os.path.join(skills_dir, "main.py")
    with open(main_path, "w", encoding="utf-8") as f:
        f.write(main_content)
    print(f"[OK] 已创建 main.py → {main_path}")

    return f"""✨ Skill 「{skill_name}」创建成功！

📁 文件结构（{scope_label}）:
  {skills_dir}/
  ├── skill.yaml   —— 元数据声明
  └── main.py      —— 入口脚本

🔧 已自动注册为工具: skill_{skill_name}
📝 现在可以使用 skill_{skill_name} 来调用它！

💡 下一步:
  - 修改 main.py 中的 run() 函数实现你的功能
  - 修改 skill.yaml 调整权限和参数
  - 让 AI 帮你编写核心逻辑
"""
