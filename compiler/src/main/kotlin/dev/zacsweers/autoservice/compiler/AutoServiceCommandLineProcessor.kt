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
public class AutoServiceCommandLineProcessor : CommandLineProcessor {

  override val pluginId: String = "dev.zacsweers.autoservice.compiler"

  // TODO verify and verbose?
  override val pluginOptions: Collection<AbstractCliOption> = listOf(
    CliOption(
      optionName = srcGenDirName,
      valueDescription = "<file-path>",
      description = "Path to directory service files should be generated into",
      required = true,
      allowMultipleOccurrences = false
    )
  )

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