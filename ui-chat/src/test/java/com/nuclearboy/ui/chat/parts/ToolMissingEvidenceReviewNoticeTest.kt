package com.nuclearboy.ui.chat.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMissingEvidenceReviewNoticeTest {

    @Test
    fun detectsMissingEvidenceReview() {
        val notice = detectToolMissingEvidenceReviewNotice(
            "本轮结果复核：需要读取文件和运行测试\n未看到工具执行卡、文件变更卡，也未看到明确的“工具受限，未真实执行”说明；请先不要把本轮回复当作已完成结果。",
        )

        assertNotNull(notice)
        assertEquals("本轮结果复核", notice?.title)
        assertTrue(notice?.summary.orEmpty().contains("不要把本轮回复当作已完成结果"))
        assertTrue(notice?.actions.orEmpty().any { it.contains("tools/function_call") })
        assertEquals("tool.evidence.missing", notice?.diagnosticLabel)
        assertEquals("正式聊天 / stream=true / 工具定义", notice?.verificationLabel)
        assertTrue(notice?.semantics.orEmpty().contains("结果复核提示"))
    }

    @Test
    fun ignoresOrdinarySystemMessage() {
        val notice = detectToolMissingEvidenceReviewNotice("本轮工具能力提示：请以工具卡为准")

        assertNull(notice)
    }

    @Test
    fun keepsTurnSpecificApiEvidenceInCardSummary() {
        val notice = detectToolMissingEvidenceReviewNotice(
            "本轮结果复核：调用接口/API 或远程配置需要模型通过工具真实执行\n" +
                "未看到工具执行卡、接口/API 调用记录、远程配置变更记录或文件变更卡，" +
                "也未看到明确的“工具受限，未真实执行”说明；请先不要把本轮回复当作已完成结果。",
        )

        assertTrue(notice?.summary.orEmpty().contains("本轮工具能力提示"))
        assertTrue(notice?.summary.orEmpty().contains("接口/API 调用记录"))
        assertTrue(notice?.summary.orEmpty().contains("不要把本轮回复当作已完成结果"))
    }
}
