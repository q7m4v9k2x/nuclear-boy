package com.nuclearboy.app.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuclearboy.common.SkillInfo
import com.nuclearboy.skills.SkillManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerPanel(
    skillManager: SkillManager,
    onNavigateBack: () -> Unit,
) {
    val skills by skillManager.activeSkills.collectAsState()
    var selectedSkill by remember { mutableStateOf<SkillInfo?>(null) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    // 当前选中 skill 的文件列表——用 LaunchedEffect 异步加载，避免 Composable body 里同步读磁盘
    var skillFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedSkill) {
        val skill = selectedSkill
        if (skill == null) {
            skillFiles = emptyList()
        } else {
            skillFiles = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Project skills live under the current project's .agent/skills
                // directory; using the global skillsDir here made newly
                // created project skills appear empty even after registration.
                val dir = skillManager.getSkillDirectory(skill.name)
                    ?: return@withContext emptyList()
                if (dir.exists() && dir.isDirectory) dir.walkTopDown().filter { it.isFile }.toList()
                else emptyList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🧩 Skill 管理", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF0A84FF)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "退出", tint = Color(0xFF838896))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0F12)),
            )
        },
    ) { padding ->
        val currentFile = selectedFile
        val currentSkill = selectedSkill
        if (currentFile != null && fileContent != null) {
            // MD 文件预览
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    IconButton(onClick = { selectedFile = null; fileContent = null }) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Color(0xFF00E676))
                    }
                    Text(currentFile.name, color = Color(0xFFE3E5E8), fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = Color(0xFF1E2230))
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    fileContent!!.lines().forEach { line ->
                        when {
                            line.startsWith("# ") -> Text(line, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                            line.startsWith("## ") -> Text(line, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0A84FF))
                            line.startsWith("### ") -> Text(line, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE3E5E8))
                            line.startsWith("```") -> Text("┌─ code ─", fontSize = 10.sp, color = Color(0xFF4E515B))
                            line.startsWith("- ") -> Text("  $line", fontSize = 13.sp, color = Color(0xFFE3E5E8))
                            line.isBlank() -> Spacer(Modifier.height(4.dp))
                            else -> Text(line, fontSize = 13.sp, color = Color(0xFFB0B3B8))
                        }
                    }
                }
            }
        } else if (currentSkill != null) {
            // Skill 详情
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    IconButton(onClick = { selectedSkill = null }) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = Color(0xFF00E676))
                    }
                    Column {
                        Text(currentSkill.name, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(currentSkill.description, color = Color(0xFF838896), fontSize = 11.sp)
                    }
                }
                Text(
                    if (currentSkill.isProjectSkill) "📁 项目级 Skill" else "🌐 全局级 Skill",
                    color = if (currentSkill.isProjectSkill) Color(0xFF0A84FF) else Color(0xFF00E676),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1E2230))
                Spacer(Modifier.height(8.dp))
                Text("📄 Skill 文件", color = Color(0xFF838896), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val files = skillFiles
                if (files.isEmpty()) {
                    Text("  无可用文件", color = Color(0xFF4E515B), fontSize = 11.sp)
                } else {
                    files.take(20).forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val text = try {
                                        withContext(Dispatchers.IO) { file.readText() }
                                    } catch (_: Exception) { "(无法读取)" }
                                    fileContent = text
                                    selectedFile = file
                                }
                            }.padding(vertical = 6.dp, horizontal = 8.dp),
                        ) {
                            Icon(if (file.extension == "md") Icons.Filled.Description else Icons.Filled.InsertDriveFile,
                                null, tint = Color(0xFF838896), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(file.name, color = Color(0xFFE3E5E8), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else {
            // Skill 列表（带蓝框）
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("已激活 Skills (${skills.size})", color = Color(0xFF0A84FF), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("项目级写入 .agent/skills/，全局级使用 scope=global；创建后会立即加载", color = Color(0xFF4E515B), fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))

                if (skills.isEmpty()) {
                    Text("暂无激活的 Skill", color = Color(0xFF838896), fontSize = 13.sp)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF0A84FF).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF0A0C10)),
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(skills, key = { it.name }) { skill ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF111318))
                                        .clickable { selectedSkill = skill }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(skill.name, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(skill.description.take(100), color = Color(0xFF838896), fontSize = 11.sp, maxLines = 2)
                                        Text(
                                            if (skill.isProjectSkill) "项目级" else "全局级",
                                            color = if (skill.isProjectSkill) Color(0xFF0A84FF) else Color(0xFF00E676),
                                            fontSize = 10.sp,
                                        )
                                    }
                                    Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF4E515B), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
