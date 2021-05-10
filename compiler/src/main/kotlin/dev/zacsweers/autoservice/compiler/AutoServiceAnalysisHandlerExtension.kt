package dev.zacsweers.autoservice.compiler

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.com.google.common.collect.HashMultimap
import org.jetbrains.kotlin.com.google.common.collect.Multimap
import org.jetbrains.kotlin.com.google.common.collect.Sets
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptorWithTypeParameters
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.findTypeAliasAcrossModuleDependencies
import org.jetbrains.kotlin.descriptors.resolveClassByFqName
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation.FROM_BACKEND
import org.jetbrains.kotlin.load.kotlin.internalName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtPureElement
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.supertypes
import java.io.File
import java.io.IOException
import java.util.SortedSet

public class AutoServiceAnalysisHandlerExtension(
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

    val serviceClasses = files.asSequence()
      .flatMap { it.classesAndInnerClasses() }
      .filter { it.hasAnnotation(autoServiceFqName) }

    // todo: implement correct removal?
    val providers = getServices(module, serviceClasses)

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
    return replace('/', '.')
  }

  private fun getServices(
    module: ModuleDescriptor,
    serviceClasses: Sequence<KtClassOrObject>
  ): Multimap<String, String> {
    val providers: Multimap<String, String> = HashMultimap.create()

    for (service in serviceClasses) {
      // It's necessary to resolve the class in order to check super types.
      val annotation = service.findAnnotation(autoServiceFqName)
        ?: continue

      // TODO - we could piece this together ourselves for a faster impl rather than looking up the
      //  class?
      val internalName = service.requireClassDescriptor(module).classId!!.internalName
      annotation.valueArguments[0].getArgumentExpression()
        ?.parseServices(module)
        ?.forEach { s ->
          // TODO check implementer implements it
          providers.put(s, internalName)
        }
    }

    return providers
  }
}

private fun KtExpression.parseServices(module: ModuleDescriptor): Sequence<String> {
  return when (this) {
    is KtCollectionLiteralExpression -> {
      getInnerExpressions().asSequence().flatMap { it.parseServices(module) }
    }
    is KtClassLiteralExpression -> {
      val fqName = this.receiverExpression!!.requireFqName(module)
      val internalName = fqName.requireClassDescriptor(module).classId!!.internalName
      sequenceOf(internalName)
    }
    else -> emptySequence()
  }
}

private fun KtFile.classesAndInnerClasses(): Sequence<KtClassOrObject> {
  val children = findChildrenByClass(KtClassOrObject::class.java)
  return generateSequence(children.toList()) { list ->
    list
      .flatMap {
        it.declarations.filterIsInstance<KtClassOrObject>()
      }
      .ifEmpty { null }
  }.flatMap { it.asSequence() }
}

private fun KtNamedDeclaration.requireFqName(): FqName = requireNotNull(fqName) {
  "fqName was null for $this, $nameAsSafeName"
}

private fun KtClassOrObject.requireClassDescriptor(module: ModuleDescriptor): ClassDescriptor {
  return module.resolveClassByFqName(requireFqName(), KotlinLookupLocation(this))
    ?: throw AutoServiceCompilationException(
      "Couldn't resolve class for ${requireFqName()}.",
      element = this
    )
}

private fun FqName.requireClassDescriptor(module: ModuleDescriptor): ClassDescriptor {
  return module.resolveClassByFqName(this, FROM_BACKEND)
    ?: throw AutoServiceCompilationException("Couldn't resolve class for $this.")
}

private fun KtAnnotated.hasAnnotation(fqName: FqName): Boolean {
  return findAnnotation(fqName) != null
}

private fun KtAnnotated.findAnnotation(fqName: FqName): KtAnnotationEntry? {
  val annotationEntries = annotationEntries
  if (annotationEntries.isEmpty()) return null

  // Look first if it's a Kotlin annotation. These annotations are usually not imported and the
  // remaining checks would fail.
  if (fqName in kotlinAnnotations) {
    annotationEntries.firstOrNull { annotation ->
      val text = annotation.text
      text.startsWith("@${fqName.shortName()}") || text.startsWith("@$fqName")
    }?.let { return it }
  }

  // Check if the fully qualified name is used, e.g. `@dagger.Module`.
  val annotationEntry = annotationEntries.firstOrNull {
    it.text.startsWith("@${fqName.asString()}")
  }
  if (annotationEntry != null) return annotationEntry

  // Check if the simple name is used, e.g. `@Module`.
  val annotationEntryShort = annotationEntries
    .firstOrNull {
      it.shortName == fqName.shortName()
    }
    ?: return null

  val importPaths = containingKtFile.importDirectives.mapNotNull { it.importPath }

  // If the simple name is used, check that the annotation is imported.
  val hasImport = importPaths.any { it.fqName == fqName }
  if (hasImport) return annotationEntryShort

  // Look for star imports and make a guess.
  val hasStarImport = importPaths
    .filter { it.isAllUnder }
    .any {
      fqName.asString().startsWith(it.fqName.asString())
    }
  if (hasStarImport) return annotationEntryShort

  return null
}

private val jvmSuppressWildcardsFqName = FqName(JvmSuppressWildcards::class.java.canonicalName)
private val publishedApiFqName = FqName(PublishedApi::class.java.canonicalName)
private val autoServiceFqName = FqName(AutoService::class.java.canonicalName)
private val kotlinAnnotations = listOf(jvmSuppressWildcardsFqName, publishedApiFqName)

private fun PsiElement.requireFqName(
  module: ModuleDescriptor
): FqName {
  val containingKtFile = parentsWithSelf
    .filterIsInstance<KtPureElement>()
    .first()
    .containingKtFile

  fun failTypeHandling(): Nothing = throw AutoServiceCompilationException(
    "Don't know how to handle Psi element: $text",
    element = this
  )

  val classReference = when (this) {
    // If a fully qualified name is used, then we're done and don't need to do anything further.
    is KtDotQualifiedExpression -> return FqName(text)
    is KtNameReferenceExpression -> getReferencedName()
    is KtUserType -> {
      val isGenericType = children.any { it is KtTypeArgumentList }
      if (isGenericType) {
        // For an expression like Lazy<Abc> the qualifier will be null. If the qualifier exists,
        // then it may refer to the package and the referencedName refers to the class name, e.g.
        // a KtUserType "abc.def.GenericType<String>" has three children: a qualifier "abc.def",
        // the referencedName "GenericType" and the KtTypeArgumentList.
        val qualifierText = qualifier?.text
        val className = referencedName

        if (qualifierText != null) {

          // The generic might be fully qualified. Try to resolve it and return early.
          module
            .resolveClassByFqName(FqName("$qualifierText.$className"), FROM_BACKEND)
            ?.let { return it.fqNameSafe }

          // If the name isn't fully qualified, then it's something like "Outer.Inner".
          // We can't use `text` here because that includes the type parameter(s).
          "$qualifierText.$className"
        } else {
          className ?: failTypeHandling()
        }
      } else {
        val text = text

        // Sometimes a KtUserType is a fully qualified name. Give it a try and return early.
        if (text.contains(".") && text[0].isLowerCase()) {
          module
            .resolveClassByFqName(FqName(text), FROM_BACKEND)
            ?.let { return it.fqNameSafe }
        }

        // We can't use referencedName here. For inner classes like "Outer.Inner" it would only
        // return "Inner", whereas text returns "Outer.Inner", what we expect.
        text
      }
    }
    is KtTypeReference -> {
      val children = children
      if (children.size == 1) {
        try {
          // Could be a KtNullableType or KtUserType.
          return children[0].requireFqName(module)
        } catch (e: AutoServiceCompilationException) {
          // Fallback to the text representation.
          text
        }
      } else {
        text
      }
    }
    is KtNullableType -> return innerType?.requireFqName(module) ?: failTypeHandling()
    is KtAnnotationEntry -> return typeReference?.requireFqName(module) ?: failTypeHandling()
    else -> failTypeHandling()
  }

  // E.g. OuterClass.InnerClass
  val classReferenceOuter = classReference.substringBefore(".")

  val importPaths = containingKtFile.importDirectives.mapNotNull { it.importPath }

  // First look in the imports for the reference name. If the class is imported, then we know the
  // fully qualified name.
  importPaths
    .filter { it.alias == null && it.fqName.shortName().asString() == classReference }
    .also { matchingImportPaths ->
      when {
        matchingImportPaths.size == 1 ->
          return matchingImportPaths[0].fqName
        matchingImportPaths.size > 1 ->
          return matchingImportPaths.first { importPath ->
            module.resolveClassByFqName(importPath.fqName, FROM_BACKEND) != null
          }.fqName
      }
    }

  importPaths
    .filter { it.alias == null && it.fqName.shortName().asString() == classReferenceOuter }
    .also { matchingImportPaths ->
      when {
        matchingImportPaths.size == 1 ->
          return FqName("${matchingImportPaths[0].fqName.parent()}.$classReference")
        matchingImportPaths.size > 1 ->
          return matchingImportPaths.first { importPath ->
            val fqName = FqName("${importPath.fqName.parent()}.$classReference")
            module.resolveClassByFqName(fqName, FROM_BACKEND) != null
          }.fqName
      }
    }

  // If there is no import, then try to resolve the class with the same package as this file.
  module.findClassOrTypeAlias(containingKtFile.packageFqName, classReference)
    ?.let { return it.fqNameSafe }

  // If this doesn't work, then maybe a class from the Kotlin package is used.
  module.resolveClassByFqName(FqName("kotlin.$classReference"), FROM_BACKEND)
    ?.let { return it.fqNameSafe }

  // If this doesn't work, then maybe a class from the Kotlin collection package is used.
  module.resolveClassByFqName(FqName("kotlin.collections.$classReference"), FROM_BACKEND)
    ?.let { return it.fqNameSafe }

  // If this doesn't work, then maybe a class from the Kotlin jvm package is used.
  module.resolveClassByFqName(FqName("kotlin.jvm.$classReference"), FROM_BACKEND)
    ?.let { return it.fqNameSafe }

  // Or java.lang.
  module.resolveClassByFqName(FqName("java.lang.$classReference"), FROM_BACKEND)
    ?.let { return it.fqNameSafe }

  findFqNameInSuperTypes(module, classReference)
    ?.let { return it }

  containingKtFile.importDirectives
    .asSequence()
    .filter { it.isAllUnder }
    .mapNotNull {
      // This fqName is the everything in front of the star, e.g. for "import java.io.*" it
      // returns "java.io".
      it.importPath?.fqName
    }
    .forEach { importFqName ->
      module.findClassOrTypeAlias(importFqName, classReference)?.let { return it.fqNameSafe }
    }

  // Check if it's a named import.
  containingKtFile.importDirectives
    .firstOrNull { classReference == it.importPath?.importedName?.asString() }
    ?.importedFqName
    ?.let { return it }

  // Everything else isn't supported.
  throw AutoServiceCompilationException(
    "Couldn't resolve FqName $classReference for Psi element: $text",
    element = this
  )
}

private fun PsiElement.findFqNameInSuperTypes(
  module: ModuleDescriptor,
  classReference: String
): FqName? {
  fun tryToResolveClassFqName(outerClass: FqName): FqName? =
    module
      .resolveClassByFqName(FqName("$outerClass.$classReference"), FROM_BACKEND)
      ?.fqNameSafe

  return parents.filterIsInstance<KtClassOrObject>()
    .flatMap { clazz ->
      tryToResolveClassFqName(clazz.requireFqName())?.let { return@flatMap sequenceOf(it) }

      // At this point we can't work with Psi APIs anymore. We need to resolve the super types
      // and try to find inner class in them.
      val descriptor = clazz.requireClassDescriptor(module)
      listOf(descriptor.defaultType).getAllSuperTypes()
        .mapNotNull { tryToResolveClassFqName(it) }
    }
    .firstOrNull()
}

private fun List<KotlinType>.getAllSuperTypes(): Sequence<FqName> =
  generateSequence(this) { kotlinTypes ->
    kotlinTypes.ifEmpty { null }?.flatMap { it.supertypes() }
  }
    .flatMap { it.asSequence() }
    .map { it.classDescriptorForType().fqNameSafe }

private fun KotlinType.classDescriptorForType() = DescriptorUtils.getClassDescriptorForType(this)

private fun ModuleDescriptor.findClassOrTypeAlias(
  packageName: FqName,
  className: String
): ClassifierDescriptorWithTypeParameters? {
  resolveClassByFqName(FqName("${packageName.safePackageString()}$className"), FROM_BACKEND)
    ?.let { return it }

  findTypeAliasAcrossModuleDependencies(ClassId(packageName, Name.identifier(className)))
    ?.let { return it }

  return null
}

/**
 * This function should only be used for package names. If the FqName is the root (no package at
 * all), then this function returns an empty string whereas `toString()` would return "<root>". For
 * a more convenient string concatenation the returned result can be prefixed and suffixed with an
 * additional dot. The root package never will use a prefix or suffix.
 */
private fun FqName.safePackageString(
  dotPrefix: Boolean = false,
  dotSuffix: Boolean = true
): String =
  if (isRoot) {
    ""
  } else {
    val prefix = if (dotPrefix) "." else ""
    val suffix = if (dotSuffix) "." else ""
    "$prefix$this$suffix"
  }