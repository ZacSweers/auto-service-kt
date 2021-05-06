package dev.zacsweers.autoservice.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.AppliedPlugin
import org.gradle.api.plugins.PluginManager
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@Suppress("unused")
public class AutoServiceGradleSubplugin : KotlinCompilerPluginSupportPlugin {

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
    return kotlinCompilation.target.project.plugins.hasPlugin(AutoServiceGradleSubplugin::class.java)
  }

  override fun getCompilerPluginId(): String = "dev.zacsweers.autoservice.compiler"

  override fun getPluginArtifact(): SubpluginArtifact =
    SubpluginArtifact(
      groupId = BuildConstants.GROUP,
      artifactId = "compiler",
      version = BuildConstants.PROJECT_VERSION
    )

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project

    // Notice that we use the name of the Kotlin compilation as a directory name. Generated code
    // for this specific compile task will be included in the task output. The output of different
    // compile tasks shouldn't be mixed.
    val srcGenDir = File(
      project.buildDir,
      "generated${File.separator}autoService${File.separator}src-gen-${kotlinCompilation.name}"
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

  override fun apply(target: Project) {
    val extension = target.extensions.create("autoService", AutoServiceExtension::class.java)

    val once = AtomicBoolean()

    fun PluginManager.withPluginOnce(
      id: String,
      action: (AppliedPlugin) -> Unit
    ) {
      withPlugin(id) {
        if (once.compareAndSet(false, true)) {
          action(it)
        }
      }
    }

    target.pluginManager.withPluginOnce("org.jetbrains.kotlin.android") {
      realApply(target, extension)
    }
    target.pluginManager.withPluginOnce("org.jetbrains.kotlin.jvm") {
      realApply(target, extension)
    }

    target.afterEvaluate {
      if (!once.get()) {
        throw GradleException(
          "No supported plugins for AutoServiceKt found on project " +
            "'${target.path}'. Only Android and JVM modules are supported for now."
        )
      }
    }
  }

  private fun realApply(
    project: Project,
    extension: AutoServiceExtension
  ) {
    val autoServiceAnnotationsDepProvider = extension.autoServiceVersion.map {
      "com.google.auto.service:auto-service-annotations:$it"
    }
    project.dependencies.add("implementation", autoServiceAnnotationsDepProvider)
  }
}

/** Configuration for the AutoService Gradle Plugin. */
public abstract class AutoServiceExtension @Inject constructor(objects: ObjectFactory) {
  /** Specifies the version of auto-service-annotations to use. */
  public val autoServiceVersion: Property<String> = objects.property(String::class.java)
    .convention(BuildConstants.AUTO_SERVICE_VERSION)
}
