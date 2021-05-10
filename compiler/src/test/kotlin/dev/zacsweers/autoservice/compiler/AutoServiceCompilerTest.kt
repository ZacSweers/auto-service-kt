package dev.zacsweers.autoservice.compiler

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.SourceFile.Companion.java
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import org.jetbrains.kotlin.config.JvmTarget
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class AutoServiceCompilerTest(private val useOldBackend: Boolean) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "useOldBackend={0}")
    fun data(): Collection<Array<Any>> {
      return listOf(
        arrayOf(true),
        arrayOf(false)
      )
    }
  }

  @Rule
  @JvmField
  var temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val autoService = java(
    "AutoService.java",
    """
      package com.google.auto.service;
      
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;
      
      @Retention(RetentionPolicy.CLASS)
      @Target(ElementType.TYPE)
      public @interface AutoService {
        Class<?>[] value();
      }
      """
  )

  @Test
  fun simple() {
    val result = compile(
      kotlin(
        "Services.kt",
        """
          package dev.zacsweers.autoservice.compiler.test

          import com.google.auto.service.AutoService
          
          interface TestService

          @AutoService(TestService::class)
          class TestClass : TestService {
            @AutoService(TestService::class)
            class NestedClass : TestService
          }
          """
      )
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

    val outputFile = File(
      temporaryFolder.root,
      "serviceOutput/META-INF/services/dev.zacsweers.autoservice.compiler.test.TestService"
    )
    check(outputFile.exists())
    assertThat(outputFile.readText()).isEqualTo(
      """
        dev.zacsweers.autoservice.compiler.test.TestClass
        dev.zacsweers.autoservice.compiler.test.TestClass${'$'}NestedClass
        
        """.trimIndent()
    )
  }

  @Test
  fun simple_array() {
    val result = compile(
      kotlin(
        "Services.kt",
        """
          package dev.zacsweers.autoservice.compiler.test

          import com.google.auto.service.AutoService
          
          interface TestService
          interface TestService2

          @AutoService(value = [TestService::class, TestService2::class])
          class TestClass : TestService, TestService2
          """
      )
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

    val outputFile = File(
      temporaryFolder.root,
      "serviceOutput/META-INF/services/dev.zacsweers.autoservice.compiler.test.TestService"
    )
    check(outputFile.exists())
    assertThat(outputFile.readText()).isEqualTo("dev.zacsweers.autoservice.compiler.test.TestClass\n")

    val outputFile2 = File(
      temporaryFolder.root,
      "serviceOutput/META-INF/services/dev.zacsweers.autoservice.compiler.test.TestService2"
    )
    check(outputFile2.exists())
    assertThat(outputFile2.readText()).isEqualTo("dev.zacsweers.autoservice.compiler.test.TestClass\n")
  }

  private fun prepareCompilation(vararg sourceFiles: SourceFile): KotlinCompilation {
    return KotlinCompilation()
      .apply {
        workingDir = temporaryFolder.root
        compilerPlugins = listOf(AutoServiceComponentRegistrar())
        inheritClassPath = true
        val processor = AutoServiceCommandLineProcessor()
        commandLineProcessors = listOf(processor)
        pluginOptions = listOf(
          PluginOption(
            pluginId = processor.pluginId,
            optionName = srcGenDirName,
            optionValue = temporaryFolder.newFolder("serviceOutput").absolutePath
          )
        )
        useOldBackend = this@AutoServiceCompilerTest.useOldBackend
        inheritClassPath = true
        sources = sourceFiles.asList() + autoService
        verbose = false
        jvmTarget = (System.getenv()["ci_java_version"]?.let(JvmTarget::fromString)
          ?: JvmTarget.JVM_1_8).description
      }
  }

  private fun compile(vararg sourceFiles: SourceFile): KotlinCompilation.Result {
    return prepareCompilation(*sourceFiles).compile()
  }

}