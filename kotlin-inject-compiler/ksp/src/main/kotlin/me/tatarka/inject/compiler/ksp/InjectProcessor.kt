package me.tatarka.inject.compiler.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ksp.writeTo
import me.tatarka.inject.compiler.AstClass
import me.tatarka.inject.compiler.COMPONENT
import me.tatarka.inject.compiler.FailedToGenerateException
import me.tatarka.inject.compiler.InjectGenerator
import me.tatarka.inject.compiler.Options

class InjectProcessor(
    private val options: Options,
    private val codeGenerator: CodeGenerator,
    override val logger: KSPLogger,
) : SymbolProcessor, KSAstProvider {

    override lateinit var resolver: Resolver

    private val generator = InjectGenerator(this, options)
    private var deferred: MutableList<KSClassDeclaration> = mutableListOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        this.resolver = resolver

        val previousDiffered = deferred

        deferred = mutableListOf()

        for (element in previousDiffered + resolver.getSymbolsWithClassAnnotation(
            COMPONENT.packageName,
            COMPONENT.simpleName,
        )) {
            val astClass = element.toAstClass()
            if (validate(astClass)) {
                process(astClass)
            } else {
                deferred.add(element)
            }
        }

        return deferred
    }

    private fun process(astClass: AstClass) {
        try {
            val file = generator.generate(astClass)
            file.writeTo(codeGenerator, aggregating = true)
        } catch (e: FailedToGenerateException) {
            error(e.message.orEmpty(), e.element)
            // Continue so we can see all errors
        }
    }

    override fun finish() {
        // Last round, generate as much as we can, reporting errors for types that still can't be resolved.
        for (element in deferred) {
            val astClass = element.toAstClass()
            process(astClass)
        }
        deferred = mutableListOf()
    }
}

class InjectProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return InjectProcessor(Options.from(environment.options), environment.codeGenerator, environment.logger)
    }
}