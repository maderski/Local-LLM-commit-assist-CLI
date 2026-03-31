package com.maderskitech.llmcommit

import java.io.File

data class CommitResult(
    val output: String,
    val hash: String,
)

class GitService {
    fun findRepositoryRoot(startingDirectory: File): Result<File> = runCatching {
        val output = runGit(startingDirectory, "rev-parse", "--show-toplevel").trim()
        if (output.isBlank()) error("Current directory is not inside a git repository.")
        File(output)
    }

    fun stageAll(repoDir: File): Result<Unit> = runCatching {
        runGit(repoDir, "add", "-A")
    }

    fun getStagedDiff(repoDir: File): Result<String> = runCatching {
        runGit(repoDir, "diff", "--cached")
    }

    fun getStagedStatSummary(repoDir: File): Result<String> = runCatching {
        runGit(repoDir, "diff", "--cached", "--stat")
    }

    fun getCurrentBranch(repoDir: File): Result<String> = runCatching {
        runGit(repoDir, "branch", "--show-current").trim()
    }

    fun hasUpstreamBranch(repoDir: File, branchName: String): Result<Boolean> = runCatching {
        val process = ProcessBuilder("git", "config", "--get", "branch.$branchName.remote")
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().readText()
        process.waitFor() == 0
    }

    fun commit(repoDir: File, summary: String, description: String): Result<CommitResult> = runCatching {
        val message = buildString {
            append(summary.trim())
            if (description.isNotBlank()) {
                append("\n\n")
                append(description.trim())
            }
        }

        val output = runGit(repoDir, "commit", "-m", message)
        val hash = runGit(repoDir, "rev-parse", "HEAD").trim()
        CommitResult(output = output, hash = hash)
    }

    fun push(repoDir: File): Result<String> = runCatching {
        runGit(repoDir, "push")
    }

    fun publishBranch(repoDir: File, branchName: String): Result<String> = runCatching {
        runGit(repoDir, "push", "--set-upstream", "origin", branchName)
    }

    private fun runGit(repoDir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("git ${args.joinToString(" ")} failed (exit $exitCode): ${output.trim()}")
        }
        return output
    }
}
