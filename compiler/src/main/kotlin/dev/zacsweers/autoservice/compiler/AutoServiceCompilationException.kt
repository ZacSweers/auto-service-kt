package dev.zacsweers.autoservice.compiler

import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.com.intellij.psi.PsiElement

internal class AutoServiceCompilationException(
  message: String,
  cause: Throwable? = null,
  element: PsiElement? = null
) : CompilationException(message, cause, element)
