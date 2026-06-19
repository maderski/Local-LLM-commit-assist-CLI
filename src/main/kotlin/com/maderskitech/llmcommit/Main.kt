package com.maderskitech.llmcommit

import java.io.File
import kotlin.system.exitProcess

private data class RunOptions(
    val push: Boolean? = null,
    val addAll: Boolean? = null,
)

fun main(args: Array<String>) {
    val cli = LlmCommitCli(
        configRepository = ConfigRepository(),
        gitService = GitService(),
        llmService = LlmService(),
    )

    val exitCode = cli.run(args.toList())
    exitProcess(exitCode)
}

class LlmCommitCli(
    private val configRepository: ConfigRepository,
    private val gitService: GitService,
    private val llmService: LlmService,
) {
    fun run(args: List<String>): Int {
        return when {
            args.isEmpty() -> runCommit(emptyList())
            args == listOf("--test") -> runConnectionTest()
            args.first() == "config" -> handleConfig(args.drop(1))
            args.first() == "help" || args.first() == "--help" || args.first() == "-h" -> {
                printUsage()
                0
            }
            else -> runCommit(args)
        }
    }

    private fun runConnectionTest(): Int {
        val config = configRepository.load()
        val model = config.modelName.ifBlank { "local-model" }

        println("Testing local LLM at ${config.llmAddress} using model $model...")
        val startedAt = System.nanoTime()
        val result = llmService.testConnection(config.llmAddress, config.modelName)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val response = result.getOrElse { error ->
            System.err.println("LLM test failed after $elapsedMs ms: ${error.message ?: error::class.simpleName}")
            return 1
        }

        println("LLM response:")
        println(response)
        println()
        println("Response time: $elapsedMs ms")
        return 0
    }

    private fun runCommit(args: List<String>): Int {
        val options = parseRunOptions(args).getOrElse { error ->
            System.err.println(error.message)
            printUsage()
            return 1
        }

        val config = configRepository.load()
        val repoDir = gitService.findRepositoryRoot(File(System.getProperty("user.dir"))).getOrElse { error ->
            System.err.println(error.message)
            return 1
        }

        val addAll = options.addAll ?: config.alwaysAddAll
        val push = options.push ?: config.alwaysPush

        if (addAll) {
            gitService.stageAll(repoDir).getOrElse { error ->
                System.err.println("Failed to stage files: ${error.message}")
                return 1
            }
        }

        val diff = gitService.getStagedDiff(repoDir).getOrElse { error ->
            System.err.println("Failed to read staged diff: ${error.message}")
            return 1
        }

        if (diff.isBlank()) {
            System.err.println(
                if (addAll) {
                    "No changes found to commit."
                } else {
                    "No staged changes found. Stage files first or enable always-add-all."
                }
            )
            return 1
        }

        val statSummary = gitService.getStagedStatSummary(repoDir).getOrDefault("").trim()
        if (statSummary.isNotBlank()) {
            println(statSummary)
            println()
        }

        val commitMessage = llmService.generateCommitMessage(config.llmAddress, config.modelName, diff).getOrElse { error ->
            System.err.println("Failed to generate commit message: ${error.message}")
            return 1
        }

        println("Commit message:")
        println(commitMessage.summary)
        if (commitMessage.description.isNotBlank()) {
            println()
            println(commitMessage.description)
        }
        println()

        val commitResult = gitService.commit(repoDir, commitMessage.summary, commitMessage.description).getOrElse { error ->
            System.err.println("Commit failed: ${error.message}")
            return 1
        }

        println(commitResult.output.trim())

        if (!push) {
            return 0
        }

        val branchName = gitService.getCurrentBranch(repoDir).getOrElse { error ->
            System.err.println("Commit succeeded, but failed to detect branch for push: ${error.message}")
            return 1
        }

        val hasUpstream = gitService.hasUpstreamBranch(repoDir, branchName).getOrDefault(false)
        val pushResult = if (hasUpstream) {
            gitService.push(repoDir)
        } else {
            gitService.publishBranch(repoDir, branchName)
        }

        pushResult.onSuccess { output ->
            if (output.isNotBlank()) {
                println(output.trim())
            }
        }.onFailure { error ->
            System.err.println("Commit succeeded, but push failed: ${error.message}")
            return 1
        }

        return 0
    }

    private fun handleConfig(args: List<String>): Int {
        val current = configRepository.load()

        return when {
            args.isEmpty() || args.first() == "show" -> {
                println("Config file: ${configRepository.configPath()}")
                println("llm-address=${current.llmAddress}")
                println("model-name=${current.modelName}")
                println("always-add-all=${current.alwaysAddAll}")
                println("always-push=${current.alwaysPush}")
                0
            }
            args.first() == "path" -> {
                println(configRepository.configPath())
                0
            }
            args.first() == "set" && args.size == 3 -> {
                val updated = when (args[1]) {
                    "llm-address" -> current.copy(llmAddress = args[2])
                    "model-name" -> current.copy(modelName = args[2])
                    "always-add-all" -> current.copy(alwaysAddAll = parseBoolean(args[2], args[1]) ?: return 1)
                    "always-push" -> current.copy(alwaysPush = parseBoolean(args[2], args[1]) ?: return 1)
                    else -> {
                        System.err.println("Unknown config key: ${args[1]}")
                        return 1
                    }
                }
                configRepository.save(updated)
                println("Updated ${args[1]}")
                0
            }
            else -> {
                System.err.println("Invalid config command.")
                printUsage()
                1
            }
        }
    }

    private fun parseBoolean(value: String, key: String): Boolean? {
        return value.toBooleanStrictOrNull() ?: run {
            System.err.println("Invalid boolean for $key: $value")
            null
        }
    }

    private fun parseRunOptions(args: List<String>): Result<RunOptions> = runCatching {
        var push: Boolean? = null
        var addAll: Boolean? = null

        args.forEach { arg ->
            when (arg) {
                "--push" -> push = true
                "--no-push" -> push = false
                "--add-all" -> addAll = true
                "--no-add-all" -> addAll = false
                else -> error("Unknown argument: $arg")
            }
        }

        RunOptions(push = push, addAll = addAll)
    }

    private fun printUsage() {
        println(
            """
            Usage:
              llm-commit --test
              llm-commit [--push|--no-push] [--add-all|--no-add-all]
              llm-commit config show
              llm-commit config path
              llm-commit config set <llm-address|model-name|always-add-all|always-push> <value>
            """.trimIndent()
        )
    }
}
