package com.nuclearboy.python

import org.junit.Assert.*
import org.junit.Test

class SandboxPolicyTest {

    private val enforcer = PolicyEnforcer()
    private val projectDir = "workspace"
    private val sandboxDir = "workspace/.sandbox"

    @Test
    fun `strict policy denies network`() {
        val policy = SandboxPolicy.strict(sandboxDir = sandboxDir, projectDir = projectDir)
        val result = enforcer.validate(policy, SandboxOperation.NetworkAccess)
        assertTrue(result.isFailure)
    }

    @Test
    fun `strict policy denies shell`() {
        val policy = SandboxPolicy.strict(sandboxDir = sandboxDir, projectDir = projectDir)
        val result = enforcer.validate(policy, SandboxOperation.ShellCommand("echo test"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `custom policy allows workspace read`() {
        val policy = workspacePolicy()
        val result = enforcer.validate(policy, SandboxOperation.ReadFile("workspace/test.py"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `custom policy allows workspace write`() {
        val policy = workspacePolicy()
        val result = enforcer.validate(policy, SandboxOperation.WriteFile("workspace/output.txt"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `path traversal is blocked`() {
        val policy = SandboxPolicy.strict(sandboxDir = sandboxDir, projectDir = projectDir)
        val result = enforcer.validate(policy, SandboxOperation.ReadFile("../etc/passwd"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `path traversal with encoding is blocked`() {
        val policy = SandboxPolicy.strict(sandboxDir = sandboxDir, projectDir = projectDir)
        val result = enforcer.validate(policy, SandboxOperation.ReadFile("workspace/../../etc/passwd"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `allowed directory prefix sibling is blocked`() {
        val policy = workspacePolicy()
        val result = enforcer.validate(policy, SandboxOperation.WriteFile("workspace-other/output.txt"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `package not in allowlist is denied`() {
        val policy = SandboxPolicy.strict(sandboxDir = sandboxDir, projectDir = projectDir)
        val result = enforcer.validate(policy, SandboxOperation.InstallPackage("malicious-pkg"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `package in allowlist is permitted`() {
        val policy = SandboxPolicy(
            allowedReadPaths = listOf("workspace/**"),
            allowedWritePaths = listOf("workspace/**"),
            networkAllowed = false,
            allowedPackages = listOf("python-docx", "openpyxl"),
            shellAllowed = false,
        )
        val result = enforcer.validate(policy, SandboxOperation.InstallPackage("python-docx"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `document generation policy denies network`() {
        val policy = SandboxPolicy.documentGeneration("workspace/out")
        val result = enforcer.validate(policy, SandboxOperation.NetworkAccess)
        assertTrue(result.isFailure)
    }

    @Test
    fun `document generation policy permits output write`() {
        val policy = SandboxPolicy.documentGeneration("workspace/out")
        val result = enforcer.validate(policy, SandboxOperation.WriteFile("workspace/out/report.docx"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `policy with shell enabled permits safe command`() {
        val policy = workspacePolicy(shellAllowed = true)
        val result = enforcer.validate(policy, SandboxOperation.ShellCommand("echo test"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `build environment sets policy variables`() {
        val policy = workspacePolicy()
        val env = enforcer.buildEnvironment(policy)
        assertTrue(env.containsKey("SANDBOX_NETWORK_ALLOWED"))
        assertEquals("0", env["SANDBOX_NETWORK_ALLOWED"])
    }

    @Test
    fun `validate returns error detail on failure`() {
        val policy = SandboxPolicy.strict(sandboxDir = sandboxDir, projectDir = projectDir)
        val result = enforcer.validate(policy, SandboxOperation.ShellCommand("echo test"))
        assertTrue(result.isFailure)
        val failure = result as com.nuclearboy.common.AppResult.Failure
        assertTrue(failure.error.humanMessage.isNotBlank())
    }

    private fun workspacePolicy(shellAllowed: Boolean = false): SandboxPolicy {
        return SandboxPolicy(
            allowedReadPaths = listOf(projectDir),
            allowedWritePaths = listOf(projectDir),
            networkAllowed = false,
            allowedPackages = listOf("python-docx", "openpyxl"),
            shellAllowed = shellAllowed,
        )
    }
}
