package com.nuclearboy.ui.chat.parts

data class ToolMissingEvidenceReviewNotice(
    val title: String,
    val summary: String,
    val actions: List<String>,
    val diagnosticLabel: String = "tool.evidence.missing",
    val verificationLabel: String = "正式聊天 / stream=true / 工具定义",
    val semantics: String,
)

fun detectToolMissingEvidenceReviewNotice(content: String): ToolMissingEvidenceReviewNotice? {
    val text = content.trim()
    if (text.isBlank()) return null
    if (!text.contains("本轮结果复核") || !text.contains("未看到工具执行卡")) return null

    // MessageBubble renders this as a structured warning card and deliberately
    // hides the raw system-message body.  Keep the turn-specific evidence
    // targets in the card summary; otherwise an API/remote-config turn gets
    // reduced to the generic "tool/file" wording and the user cannot tell
    // what evidence is actually missing.
    val cardSummary = text
        .substringAfter("本轮结果复核：", missingDelimiterValue = text)
        .replace("\n", "；")
        .trim()
        .ifBlank {
            "未看到工具执行卡或文件变更卡，也没有明确的工具受限说明；请先不要把本轮回复当作已完成结果。"
        }

    return ToolMissingEvidenceReviewNotice(
        title = "本轮结果复核",
        summary = "本轮工具能力提示：$cardSummary",
        actions = listOf(
            "需要真实读写、运行或验证时，切换支持 tools/function_call 的模型或网关后重试",
            "继续当前模型只适合普通问答，不适合确认文件、命令或测试已完成",
        ),
        semantics = "结果复核提示：本轮缺少工具执行证据，请勿当作已完成结果。",
    )
}
