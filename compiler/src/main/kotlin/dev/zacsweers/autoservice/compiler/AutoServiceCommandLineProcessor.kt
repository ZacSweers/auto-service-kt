package dev.zacsweers.autoservice.compiler

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal const val srcGenDirName = "src-gen-dir"
internal val srcGenDirKey = CompilerConfigurationKey.create<String>("autoservice $srcGenDirName")

@AutoService(CommandLineProcessor::class)
class AutoServiceCommandLineProcessor : CommandLineProcessor {
  companion object {
    const val COMPILER_PLUGIN_ID: String = "dev.zacsweers.autoservice"

    // TODO verify and verbose?
    val OUTPUT_DIR_OPTION: CliOption =
      CliOption(
        optionName = srcGenDirName,
        valueDescription = "<file-path>",
        description = "Path to directory service files should be generated into",
        required = true,
        allowMultipleOccurrences = false
      )
  }

  override val pluginId: String
    get() = COMPILER_PLUGIN_ID

  override val pluginOptions: Collection<CliOption>
    get() = listOf(OUTPUT_DIR_OPTION)
//        get() = listOf()

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration
  ) {
    when (val optionName = option.optionName) {
      srcGenDirName -> configuration.put(srcGenDirKey, value)
      else -> throw CliOptionProcessingException("Unknown option: $optionName")
    }
  }
}