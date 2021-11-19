package me.tatarka.inject.compiler.ksp

import com.google.devtools.ksp.findActualType
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.toTypeName
import me.tatarka.inject.compiler.AstAnnotated
import me.tatarka.inject.compiler.AstAnnotation
import me.tatarka.inject.compiler.AstBasicElement
import me.tatarka.inject.compiler.AstClass
import me.tatarka.inject.compiler.AstConstructor
import me.tatarka.inject.compiler.AstElement
import me.tatarka.inject.compiler.AstFunction
import me.tatarka.inject.compiler.AstHasModifiers
import me.tatarka.inject.compiler.AstMethod
import me.tatarka.inject.compiler.AstParam
import me.tatarka.inject.compiler.AstProperty
import me.tatarka.inject.compiler.AstProvider
import me.tatarka.inject.compiler.AstType
import me.tatarka.inject.compiler.Messenger
import me.tatarka.inject.compiler.OutputProvider
import me.tatarka.inject.compiler.PROVIDES
import kotlin.reflect.KClass

interface KSAstProvider : AstProvider, OutputProvider {

    val resolver: Resolver

    val logger: KSPLogger

    override val messenger: KSAstMessenger
        get() = KSAstMessenger.also {
            it.logger = logger
        }

    fun KSClassDeclaration.toAstClass(): AstClass {
        return KSAstClass(resolver, this)
    }

    override fun findFunctions(packageName: String, functionName: String): Sequence<AstFunction> {
        val name = resolver.getKSNameFromString("$packageName.$functionName")
        return resolver.getFunctionDeclarationsByName(name, includeTopLevel = true)
            .map { declaration ->
                KSAstFunction(resolver, declaration)
            }
    }

    override fun declaredTypeOf(klass: KClass<*>, vararg astTypes: AstType): AstType {
        val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(klass.qualifiedName!!))!!
        return KSAstType(
            resolver,
            declaration.asType(
                astTypes.map {
                    val type = (it as KSAstType).type
                    resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(type), Variance.INVARIANT)
                }
            )
        )
    }

    override fun validate(element: AstClass): Boolean {
        require(element is KSAstClass)
        return element.declaration.accept(FixedKSValidateVisitor { node, _ ->
            when (node) {
                is KSFunctionDeclaration ->
                    node.getVisibility() != Visibility.PRIVATE &&
                            (node.isAbstract || node.hasAnnotation(PROVIDES.packageName, PROVIDES.simpleName))
                is KSPropertyDeclaration ->
                    node.getVisibility() != Visibility.PRIVATE &&
                            (node.isAbstract() ||
                                    node.getter?.hasAnnotation(PROVIDES.packageName, PROVIDES.simpleName) ?: true)
                else -> true
            }
        }, null)
    }

    override fun AstElement.toTrace(): String {
        require(this is KSAstAnnotated)
        val source = declaration.location as? FileLocation
        return if (source != null) {
            "${source.filePath}:${source.lineNumber}: ${toString()}"
        } else {
            toString()
        }
    }

    override fun TypeSpec.Builder.build(elements: List<AstClass>): TypeSpec {
        val files = elements.mapNotNull { (it as KSAstClass).declaration.containingFile }.toSet()
        for (file in files) {
            addOriginatingKSFile(file)
        }
        return build()
    }
}

object KSAstMessenger : Messenger {
    internal lateinit var logger: KSPLogger

    override fun warn(message: String, element: AstElement?) {
        logger.warn(message, (element as? KSAstAnnotated)?.declaration)
    }

    override fun error(message: String, element: AstElement?) {
        logger.error(message, (element as? KSAstAnnotated)?.declaration)
    }
}

private interface KSAstAnnotated : AstAnnotated {
    val resolver: Resolver
    val declaration: KSAnnotated

    override fun hasAnnotation(packageName: String, simpleName: String): Boolean {
        return declaration.hasAnnotation(packageName, simpleName)
    }

    override fun annotationAnnotatedWith(packageName: String, simpleName: String): AstAnnotation? {
        return declaration.annotationAnnotatedWith(packageName, simpleName)?.let {
            KSAstAnnotation(resolver, it)
        }
    }
}

private interface KSAstHasModifiers : AstHasModifiers, KSAstAnnotated {
    override val declaration: KSDeclaration

    override val visibility: KModifier
        get() = declaration.getVisibility().toKModifier() ?: KModifier.PUBLIC
}

private class KSAstBasicElement(private val declaration: KSDeclaration) : AstBasicElement() {
    override val simpleName: String
        get() = declaration.simpleName.asString()
}

private class KSAstClass(override val resolver: Resolver, override val declaration: KSClassDeclaration) :
    AstClass(), KSAstHasModifiers {
    override val isJavaClass: Boolean
        get() = declaration.origin == Origin.JAVA || declaration.origin == Origin.JAVA_LIB

    override val packageName: String
        get() = declaration.packageName.asString()

    override val name: String
        get() = declaration.simpleName.asString()

    override val isAbstract: Boolean
        get() = declaration.isAbstract()

    override val isInterface: Boolean
        get() = declaration.classKind == ClassKind.INTERFACE

    override val companion: AstClass?
        get() = declaration.declarations
            .find { it is KSClassDeclaration && it.isCompanionObject }
            ?.let { KSAstClass(resolver, it as KSClassDeclaration) }

    override val isObject: Boolean
        get() = declaration.classKind == ClassKind.OBJECT

    override val superTypes: Sequence<AstClass>
        get() {
            return declaration
                .superTypes
                .mapNotNull { type ->
                    (type.resolve().declaration as? KSClassDeclaration)?.let { KSAstClass(resolver, it) }
                }
        }

    override val primaryConstructor: AstConstructor?
        get() {
            return declaration.primaryConstructor?.let { KSAstConstructor(resolver, this, it) }
        }

    override val constructors: Sequence<AstConstructor>
        get() = declaration.getConstructors().map { KSAstConstructor(resolver, this, it) }

    override val methods: List<AstMethod>
        get() {
            return mutableListOf<AstMethod>().apply {
                addAll(
                    declaration.getDeclaredProperties().map {
                        KSAstProperty(resolver, it)
                    }
                )
                addAll(
                    declaration.getDeclaredFunctions().map {
                        KSAstFunction(resolver, it)
                    }
                )
            }
        }

    override val type: AstType
        get() {
            return KSAstType(resolver, declaration.asStarProjectedType())
        }

    override fun asClassName(): ClassName {
        return declaration.toClassName()
    }

    override fun equals(other: Any?): Boolean {
        return other is KSAstClass && declaration == other.declaration
    }

    override fun hashCode(): Int {
        return declaration.hashCode()
    }
}

private class KSAstConstructor(
    override val resolver: Resolver,
    private val parent: KSAstClass,
    override val declaration: KSFunctionDeclaration
) : AstConstructor(parent), KSAstAnnotated {

    override val parameters: List<AstParam>
        get() {
            return declaration.parameters.map { param -> KSAstParam(resolver, parent.declaration, param) }
        }

    override fun equals(other: Any?): Boolean {
        return other is KSAstConstructor && declaration == other.declaration
    }

    override fun hashCode(): Int {
        return declaration.hashCode()
    }
}

private class KSAstFunction(override val resolver: Resolver, override val declaration: KSFunctionDeclaration) :
    AstFunction(),
    KSAstHasModifiers {

    override val parameters: List<AstParam>
        get() = declaration.parameters.map { param ->
            KSAstParam(resolver, null, param)
        }

    override val name: String
        get() = declaration.simpleName.asString()

    override val isAbstract: Boolean
        get() = declaration.isAbstract

    override val isSuspend: Boolean
        get() = declaration.modifiers.contains(Modifier.SUSPEND)

    override val receiverParameterType: AstType?
        get() = declaration.extensionReceiver?.let { KSAstType(resolver, it) }

    override fun overrides(other: AstMethod): Boolean {
        if (other !is KSAstFunction) return false
        return resolver.overrides(declaration, other.declaration)
    }

    override val returnType: AstType
        get() = KSAstType(resolver, declaration.returnType!!)

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        require(enclosingClass is KSAstClass)
        // resolver.asMemberOf is expensive and only matters if there are generics involved, so check that first.
        val type = declaration.returnType!!
        if (type.resolve().isConcrete()) {
            return KSAstType(resolver, type)
        }
        return KSAstType(
            resolver,
            declaration.asMemberOf(enclosingClass.declaration.asStarProjectedType()).returnType!!
        )
    }

    override fun asMemberName(): MemberName {
        val packageName = declaration.qualifiedName?.getQualifier().orEmpty()
        return MemberName(packageName, declaration.simpleName.asString())
    }

    override fun equals(other: Any?): Boolean {
        return other is KSAstFunction && declaration == other.declaration
    }

    override fun hashCode(): Int {
        return declaration.hashCode()
    }
}

private class KSAstProperty(override val resolver: Resolver, override val declaration: KSPropertyDeclaration) :
    AstProperty(), KSAstHasModifiers {

    override val name: String
        get() = declaration.simpleName.asString()

    override val isAbstract: Boolean
        get() = declaration.isAbstract()

    override val receiverParameterType: AstType?
        get() = declaration.extensionReceiver?.let { KSAstType(resolver, it) }

    override fun overrides(other: AstMethod): Boolean {
        if (other !is KSAstProperty) return false
        return resolver.overrides(declaration, other.declaration)
    }

    override val returnType: AstType
        get() = KSAstType(resolver, declaration.type)

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        require(enclosingClass is KSAstClass)
        // resolver.asMemberOf is expensive and only matters if there are generics involved, so check that first.
        val type = declaration.type
        if (type.resolve().isConcrete()) {
            return KSAstType(resolver, type)
        }
        return KSAstType(
            resolver,
            declaration.asMemberOf(enclosingClass.declaration.asStarProjectedType())
        )
    }

    override fun asMemberName(): MemberName {
        val packageName = declaration.qualifiedName?.getQualifier().orEmpty()
        return MemberName(packageName, declaration.simpleName.toString())
    }

    override fun hasAnnotation(packageName: String, simpleName: String): Boolean {
        return declaration.getter?.hasAnnotation(packageName, simpleName) == true
    }

    override fun annotationAnnotatedWith(packageName: String, simpleName: String): AstAnnotation? {
        return declaration.getter?.annotationAnnotatedWith(packageName, simpleName)?.let {
            KSAstAnnotation(resolver, it)
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is KSAstProperty && declaration == other.declaration
    }

    override fun hashCode(): Int {
        return declaration.hashCode()
    }
}

private class KSAstType private constructor(
    private val resolver: Resolver,
    private val typeRef: KSTypeReference,
    val type: KSType
) : AstType() {

    constructor(resolver: Resolver, typeRef: KSTypeReference) : this(resolver, typeRef, typeRef.resolve())

    constructor(resolver: Resolver, type: KSType) : this(resolver, resolver.createKSTypeReferenceFromKSType(type), type)

    override val packageName: String
        get() = type.declaration.simplePackageName()

    override val simpleName: String
        get() = type.declaration.shortName

    override val arguments: List<AstType>
        get() = type.arguments.map {
            KSAstType(resolver, it.type!!)
        }

    override val isError: Boolean
        get() = type.isError

    override fun isUnit(): Boolean {
        return type == resolver.builtIns.unitType
    }

    override fun isPlatform(): Boolean = type.nullability == Nullability.PLATFORM

    override fun isFunction(): Boolean {
        return type.isFunctionType || type.isSuspendFunctionType
    }

    override fun isTypeAlias(): Boolean {
        return type.declaration is KSTypeAlias
    }

    override fun resolvedType(): AstType {
        val declaration = type.declaration
        return if (declaration is KSTypeAlias) {
            KSAstType(resolver, declaration.type.resolve().replace(type.arguments))
        } else {
            this
        }
    }

    override fun asElement(): AstBasicElement {
        return KSAstBasicElement(type.declaration)
    }

    override fun toAstClass(): AstClass {
        return when (val declaration = type.declaration) {
            is KSClassDeclaration -> KSAstClass(resolver, declaration)
            is KSTypeAlias -> KSAstClass(resolver, declaration.findActualType())
            else -> throw IllegalArgumentException("unknown declaration: $declaration")
        }
    }

    override fun asTypeName(): TypeName {
        return typeRef.toTypeName()
    }

    override fun isAssignableFrom(other: AstType): Boolean {
        require(other is KSAstType)
        return type.isAssignableFrom(other.type)
    }

    override fun equals(other: Any?): Boolean {
        // don't use type == other.type as the impl declares differing typealias as equal
        // and we don't want that behavior.
        return other is KSAstType && type.eqv(other.type)
    }

    override fun hashCode(): Int {
        return type.eqvHashCode()
    }

    override fun toString(): String {
        return typeRef.toString().shortenPackage()
    }
}

private class KSAstParam(
    override val resolver: Resolver,
    val parentClass: KSClassDeclaration?,
    override val declaration: KSValueParameter
) : AstParam(),
    KSAstAnnotated {

    override val name: String
        get() = declaration.name!!.asString()

    override val type: AstType
        get() = KSAstType(resolver, declaration.type)

    override val isVal: Boolean
        get() = declaration.isVal

    override val isPrivate: Boolean
        get() = parentClass?.getDeclaredProperties()?.find { it.simpleName == declaration.name }?.isPrivate() ?: false

    override val hasDefault: Boolean
        get() = declaration.hasDefault

    override fun asParameterSpec(): ParameterSpec {
        return ParameterSpec.builder(name, type.asTypeName())
            .build()
    }

    override fun toString(): String = "$name: $type"
}

private class KSAstAnnotation(private val resolver: Resolver, val annotation: KSAnnotation) : AstAnnotation() {

    override val type: AstType
        get() = KSAstType(resolver, annotation.annotationType)

    override fun equals(other: Any?): Boolean {
        return other is KSAstAnnotation && annotation.eqv(other.annotation)
    }

    override fun hashCode(): Int {
        return toString().hashCode()
    }

    override fun toString(): String {
        return "$annotation(${
            annotation.arguments.joinToString(", ") { arg -> "${arg.name?.asString()}=${arg.value}" }
        })"
    }
}