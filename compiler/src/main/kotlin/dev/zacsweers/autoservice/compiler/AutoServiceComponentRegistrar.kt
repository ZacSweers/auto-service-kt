package dev.zacsweers.autoservice.compiler

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import java.io.File

@AutoService(ComponentRegistrar::class)
public class AutoServiceComponentRegistrar : ComponentRegistrar {
  override fun registerProjectComponents(
    project: MockProject,
    configuration: CompilerConfiguration
  ) {
    val newConfiguration = configuration.copy()
    newConfiguration.get(srcGenDirKey)?.let {
      val dir = File(it)
      newConfiguration.put(JVMConfigurationKeys.OUTPUT_DIRECTORY, dir)
    }
    val sourceGenFolder = File(newConfiguration.getNotNull(srcGenDirKey))
    val extension = AutoServiceAnalysisHandlerExtension(sourceGenFolder)
    AnalysisHandlerExtension.registerExtension(project, extension)
  }
}