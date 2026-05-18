package com.maderskitech.llmcommit

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigRepositoryTest {
    @Test
    fun load_returnsDefaultsWhenConfigFileDoesNotExist() {
        val configFile = Files.createTempDirectory("llm-commit-config-test")
            .resolve("config.properties")
            .toFile()

        try {
            val repository = ConfigRepository(configFile)

            assertEquals(AppConfig(), repository.load())
        } finally {
            configFile.toPath().deleteIfExists()
            configFile.parentFile.toPath().deleteIfExists()
        }
    }

    @Test
    fun save_andLoad_roundTripAllConfigFields() {
        val configDir = Files.createTempDirectory("llm-commit-config-test")
        val configFile = configDir.resolve("config.properties").toFile()

        try {
            val repository = ConfigRepository(configFile)
            val expected = AppConfig(
                llmAddress = "http://localhost:11434/v1",
                modelName = "qwen3",
                alwaysAddAll = false,
                alwaysPush = true,
            )

            repository.save(expected)

            assertEquals(expected, repository.load())
        } finally {
            configFile.toPath().deleteIfExists()
            configDir.deleteIfExists()
        }
    }

    @Test
    fun load_fallsBackToSafeDefaultsForInvalidBooleanValues() {
        val configDir = Files.createTempDirectory("llm-commit-config-test")
        val configFile = configDir.resolve("config.properties").toFile()

        try {
            configFile.writeText(
                """
                llm.address=http://localhost:1234/v1
                model.name=model
                git.alwaysAddAll=maybe
                git.alwaysPush=sometimes
                """.trimIndent()
            )

            val repository = ConfigRepository(configFile)
            val loaded = repository.load()

            assertEquals(true, loaded.alwaysAddAll)
            assertEquals(false, loaded.alwaysPush)
        } finally {
            configFile.toPath().deleteIfExists()
            configDir.deleteIfExists()
        }
    }
}
