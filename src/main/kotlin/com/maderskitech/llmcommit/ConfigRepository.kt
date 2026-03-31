package com.maderskitech.llmcommit

import java.io.File
import java.util.Properties

data class AppConfig(
    val llmAddress: String = DEFAULT_LLM_ADDRESS,
    val modelName: String = DEFAULT_MODEL_NAME,
    val alwaysAddAll: Boolean = true,
    val alwaysPush: Boolean = false,
) {
    companion object {
        const val DEFAULT_LLM_ADDRESS = "http://localhost:1234/v1"
        const val DEFAULT_MODEL_NAME = ""
    }
}

class ConfigRepository(
    private val configFile: File = defaultConfigFile(),
) {
    fun load(): AppConfig {
        if (!configFile.exists()) return AppConfig()

        val properties = Properties()
        configFile.inputStream().use(properties::load)

        return AppConfig(
            llmAddress = properties.getProperty(KEY_LLM_ADDRESS, AppConfig.DEFAULT_LLM_ADDRESS),
            modelName = properties.getProperty(KEY_MODEL_NAME, AppConfig.DEFAULT_MODEL_NAME),
            alwaysAddAll = properties.getProperty(KEY_ALWAYS_ADD_ALL, "true").toBooleanStrictOrNull() ?: true,
            alwaysPush = properties.getProperty(KEY_ALWAYS_PUSH, "false").toBooleanStrictOrNull() ?: false,
        )
    }

    fun save(config: AppConfig) {
        configFile.parentFile.mkdirs()

        val properties = Properties()
        properties.setProperty(KEY_LLM_ADDRESS, config.llmAddress)
        properties.setProperty(KEY_MODEL_NAME, config.modelName)
        properties.setProperty(KEY_ALWAYS_ADD_ALL, config.alwaysAddAll.toString())
        properties.setProperty(KEY_ALWAYS_PUSH, config.alwaysPush.toString())

        configFile.outputStream().use { output ->
            properties.store(output, "llm-commit configuration")
        }
    }

    fun configPath(): String = configFile.absolutePath

    companion object {
        private const val KEY_LLM_ADDRESS = "llm.address"
        private const val KEY_MODEL_NAME = "model.name"
        private const val KEY_ALWAYS_ADD_ALL = "git.alwaysAddAll"
        private const val KEY_ALWAYS_PUSH = "git.alwaysPush"

        private fun defaultConfigFile(): File {
            val home = System.getProperty("user.home")
            return File(home, ".config/llm-commit/config.properties")
        }
    }
}
