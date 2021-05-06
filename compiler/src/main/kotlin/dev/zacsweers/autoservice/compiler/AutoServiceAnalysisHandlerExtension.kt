package dev.zacsweers.autoservice.compiler

import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.com.google.common.collect.HashMultimap
import org.jetbrains.kotlin.com.google.common.collect.Multimap
import org.jetbrains.kotlin.com.google.common.collect.Sets
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.incremental.isClassFile
import org.jetbrains.kotlin.load.kotlin.FileBasedKotlinClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import java.io.File
import java.io.IOException
import java.util.SortedSet

public class AutoServiceAnalysisHandlerExtension(
  private val compilerConfiguration: CompilerConfiguration,
  private val codeGenDir: File
) : AnalysisHandlerExtension {

  override fun analysisCompleted(
    project: Project,
    module: ModuleDescriptor,
    bindingTrace: BindingTrace,
    files: Collection<KtFile>
  ): AnalysisResult? {
    codeGenDir.listFiles()
      ?.forEach {
        check(it.deleteRecursively()) {
          "Could not clean file: $it"
        }
      }

    val bindingContext = bindingTrace.bindingContext
    if (bindingContext.diagnostics.any { it.severity == Severity.ERROR }) return null

    val targetId = TargetId(
      name = compilerConfiguration[CommonConfigurationKeys.MODULE_NAME] ?: module.name.asString(),
      type = "java-production"
    )

    val generationState = GenerationState.Builder(
      project,
      ClassBuilderFactories.BINARIES,
      module,
      bindingContext,
      files.toList(),
      compilerConfiguration
    ).targetId(targetId).build()
    KotlinCodegenFacade.compileCorrectFiles(generationState)

    val outputDir = compilerConfiguration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY)!!
    val outputs = ArrayList<AbiOutput>()

    for (outputFile in generationState.factory.asList()) {
      val file = File(outputDir, outputFile.relativePath)
      outputs.add(AbiOutput(file, outputFile.asByteArray()))
    }

    // todo: implement correct removal
    val providers = getServices(outputs)

    for (providerInterface in providers.keySet()) {
      val resourceFilePath = "META-INF/services/${providerInterface.toBinaryName()}"
//      log("Working on resource file: $resourceFile")
      try {
        val allServices: SortedSet<String> = Sets.newTreeSet()
        val foundImplementers = providers[providerInterface].toSet()
        allServices.addAll(foundImplementers)
//        log("New service file contents: $allServices")
//        log("Originating files: ${ksFiles.map(KSFile::fileName)}")
        val resourceFile = codeGenDir.resolve(resourceFilePath)
          .apply {
            parentFile.mkdirs()
            createNewFile()
          }
        resourceFile.bufferedWriter().use { writer ->
          for (service in allServices) {
            writer.write(service.toBinaryName())
            writer.newLine()
          }
        }
//        log("Wrote to: $resourceFile")
      } catch (e: IOException) {
        error("Unable to create $resourceFilePath, $e")
      }
    }

    return null
  }

  private fun String.toBinaryName(): String {
    return replace(".", "$").replace("/", ".")
  }

  private fun getServices(outputs: List<AbiOutput>): Multimap<String, String> {
    val providers: Multimap<String, String> = HashMultimap.create()

    for (output in outputs) {
      if (!output.file.isClassFile()) continue

      val classData = output.classData() ?: continue
      val header = classData.classHeader
      if (header.kind != KotlinClassHeader.Kind.CLASS) continue
      val visitor = AutoServiceClassVisitor()
      output.accept(visitor)
      // TODO checkImplementer
      val services = visitor.annotationVisitor?.valueVisitor?.services ?: continue

      if (services.isNotEmpty()) {
        val internalName = JvmClassName.byClassId(classData.classId).internalName
        println("Loaded $services")
        for (service in services) {
          providers.put(service, internalName)
        }
      }
    }

    return providers
  }

  internal class AutoServiceClassVisitor : ClassVisitor(Opcodes.API_VERSION) {
    var annotationVisitor: AutoServiceAnnotationVisitor? = null

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
      return when (descriptor) {
        "Lcom/google/auto/service/AutoService;" -> {
          AutoServiceAnnotationVisitor().also { annotationVisitor = it }
        }
        else -> {
          null
        }
      }
    }

    // TODO ignore others after annotations checked?
  }

  // Visitor for the AutoService annotation itself
  internal class AutoServiceAnnotationVisitor : AnnotationVisitor(Opcodes.API_VERSION) {
    lateinit var valueVisitor: AutoServiceAnnotationValueVisitor

    override fun visitArray(name: String?): AnnotationVisitor? {
      if (name == "value") {
        return AutoServiceAnnotationValueVisitor().also { valueVisitor = it }
      }
      return super.visitArray(name)
    }
  }

  // Visitor for the AutoService.value array visitor
  internal class AutoServiceAnnotationValueVisitor : AnnotationVisitor(Opcodes.API_VERSION) {
    val services = mutableListOf<String>()

    override fun visit(name: String?, value: Any?) {
      if (value is Type) {
        services += value.internalName
      }
      super.visit(name, value)
    }
  }

  private data class ClassData(
    val classId: ClassId,
    val classVersion: Int,
    val classHeader: KotlinClassHeader
  )

  private class AbiOutput(
    val file: File,
    // null bytes means that file should not be written
    private var bytes: ByteArray?
  ) {
    fun classData(): ClassData? =
      when {
        bytes == null -> null
        !file.isClassFile() -> null
        else -> FileBasedKotlinClass.create(bytes!!) { classId, classVersion, classHeader, _ ->
          ClassData(classId, classVersion, classHeader)
        }
      }

    fun accept(visitor: ClassVisitor) {
      val bytes = bytes ?: return
      val cr = ClassReader(bytes)
      cr.accept(visitor, 0)
    }
  }
}