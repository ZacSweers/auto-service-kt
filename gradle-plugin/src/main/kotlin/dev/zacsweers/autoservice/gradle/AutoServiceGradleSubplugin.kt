package dev.zacsweers.autoservice.gradle

import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

@Suppress("unused")
class AutoServiceGradleSubplugin : KotlinCompilerPluginSupportPlugin {

  override fun getCompilerPluginId(): String = "autoservice-compiler-plugin"

  override fun getPluginArtifact(): SubpluginArtifact =
    SubpluginArtifact(
      groupId = "dev.zacsweers.autoservice",
      artifactId = "compiler",
      version = VERSION
    )

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
    return (kotlinCompilation.platformType == KotlinPlatformType.jvm || kotlinCompilation.platformType == KotlinPlatformType.androidJvm)
  }

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project

    // Notice that we use the name of the Kotlin compilation as a directory name. Generated code
    // for this specific compile task will be included in the task output. The output of different
    // compile tasks shouldn't be mixed.
    val srcGenDir = File(
      project.buildDir,
      "autoservice${File.separator}src-gen-${kotlinCompilation.name}"
    )

    return project.provider {
      listOf(
        FilesSubpluginOption(
          key = "src-gen-dir",
          files = listOf(srcGenDir)
        )
      )
    }
  }
}
