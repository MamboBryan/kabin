package com.attafitamim.kabin.compiler.sql.generator.dao

import com.attafitamim.kabin.compiler.sql.utils.poet.SYMBOL_ACCESS_SIGN
import com.attafitamim.kabin.compiler.sql.utils.poet.buildSpec
import com.attafitamim.kabin.compiler.sql.utils.poet.simpleNameString
import com.attafitamim.kabin.compiler.sql.utils.poet.toPascalCase
import com.attafitamim.kabin.compiler.sql.utils.poet.typeInitializer
import com.attafitamim.kabin.compiler.sql.utils.poet.writeType
import com.attafitamim.kabin.compiler.sql.utils.spec.getDataReturnType
import com.attafitamim.kabin.core.dao.KabinDao
import com.attafitamim.kabin.processor.ksp.options.KabinOptions
import com.attafitamim.kabin.processor.utils.throwException
import com.attafitamim.kabin.specs.dao.DataTypeSpec
import com.attafitamim.kabin.specs.dao.DaoActionSpec
import com.attafitamim.kabin.specs.dao.DaoFunctionSpec
import com.attafitamim.kabin.specs.dao.DaoSpec
import com.attafitamim.kabin.specs.relation.compound.CompoundPropertySpec
import com.attafitamim.kabin.specs.relation.compound.CompoundSpec
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName

class DaoGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: KabinOptions
) {

    fun generate(daoSpec: DaoSpec): Result {
        val daoFilePackage = daoSpec.declaration.packageName.asString()
        val daoFileName = buildString {
            append(daoSpec.declaration.simpleName.asString())
            append(options.getOrDefault(KabinOptions.Key.DAO_SUFFIX))
        }

        val daoQueriesFilePackage = daoSpec.declaration.packageName.asString()
        val daoQueriesFileName = buildString {
            append(daoSpec.declaration.simpleName.asString())
            append(options.getOrDefault(KabinOptions.Key.DAO_QUERIES_SUFFIX))
        }

        val className = ClassName(daoFilePackage, daoFileName)
        val daoQueriesClassName = ClassName(daoQueriesFilePackage, daoQueriesFileName)

        val superClassName = daoSpec.declaration.toClassName()
        val kabinDaoInterface = KabinDao::class.asClassName()
            .parameterizedBy(daoQueriesClassName)

        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(kabinDaoInterface)
            .addSuperinterface(superClassName)

        val daoQueriesPropertyName = KabinDao<*>::queries.name
        val constructorBuilder = FunSpec.constructorBuilder()
            .addParameter(daoQueriesPropertyName, daoQueriesClassName)
            .build()

        val daoQueriesPropertySpec = PropertySpec.builder(
            daoQueriesPropertyName,
            daoQueriesClassName,
            KModifier.OVERRIDE
        ).initializer(daoQueriesPropertyName).build()

        classBuilder
            .primaryConstructor(constructorBuilder)
            .addProperty(daoQueriesPropertySpec)

        daoSpec.functionSpecs.forEach { functionSpec ->
            val functionCodeBuilder = CodeBlock.builder()

            val returnType = functionSpec.returnTypeSpec
            if (functionSpec.transactionSpec != null) {
                if (returnType != null) {
                    functionCodeBuilder.beginControlFlow("return transactionWithResult")
                } else {
                    functionCodeBuilder.beginControlFlow("transaction")
                }
            }

            functionCodeBuilder.addQueryLogic(
                daoQueriesPropertyName,
                functionSpec,
                returnType
            )

            if (functionSpec.transactionSpec != null) {
                functionCodeBuilder.endControlFlow()
            }

            val functionBuilder = functionSpec.declaration.buildSpec()
                .addModifiers(KModifier.OVERRIDE)
                .addCode(functionCodeBuilder.build())
                .build()

            classBuilder.addFunction(functionBuilder)
        }

        codeGenerator.writeType(
            className,
            classBuilder.build()
        )

        return Result(className)
    }

    private fun CodeBlock.Builder.addQueryLogic(
        daoQueriesPropertyName: String,
        functionSpec: DaoFunctionSpec,
        returnType: DataTypeSpec?
    ) {
        val functionName = functionSpec.declaration.simpleNameString
        val parameters = functionSpec.parameters.joinToString { parameter ->
            parameter.name
        }

        val returnTypeSpec = returnType?.getDataReturnType()
        val returnTypeDataType = returnTypeSpec?.dataType
        if (returnTypeDataType is DataTypeSpec.DataType.Compound) {
            addCompoundReturnLogic(
                daoQueriesPropertyName,
                functionName,
                parameters,
                returnType,
                returnTypeDataType.spec
            )
        } else {
            addSimpleReturnLogic(
                daoQueriesPropertyName,
                functionName,
                parameters,
                functionSpec,
                returnType
            )
        }
    }

    private fun CodeBlock.Builder.addSimpleReturnLogic(
        daoQueriesPropertyName: String,
        functionName: String,
        parameters: String,
        functionSpec: DaoFunctionSpec,
        returnType: DataTypeSpec?
    ) {
        val awaitFunction = returnType?.getAwaitFunction()
        val functionCall = when (functionSpec.actionSpec) {
            is DaoActionSpec.Delete,
            is DaoActionSpec.Insert,
            is DaoActionSpec.Update,
            is DaoActionSpec.Upsert,
            is DaoActionSpec.Query,
            is DaoActionSpec.RawQuery -> {
                if (awaitFunction.isNullOrBlank()) {
                    "$daoQueriesPropertyName.$functionName($parameters)"
                } else {
                    "$daoQueriesPropertyName.$functionName($parameters).$awaitFunction()"
                }
            }

            null -> "super.$functionName($parameters)"
        }

        val actualFunctionCall = if (returnType != null && functionSpec.transactionSpec == null) {
            "return $functionCall"
        } else {
            functionCall
        }

        addStatement(actualFunctionCall)
    }

    private fun CodeBlock.Builder.addCompoundReturnLogic(
        daoQueriesPropertyName: String,
        functionName: String,
        parameters: String,
        returnType: DataTypeSpec,
        compoundSpec: CompoundSpec,
        isNested: Boolean = false
    ) {
        when (val type = returnType.dataType) {
            is DataTypeSpec.DataType.Wrapper -> {
                addCompoundPropertyMapping(
                    functionName,
                    parameters,
                    compoundSpec.mainProperty,
                    returnType.getAwaitFunction(),
                    isNested = isNested
                )

                addCompoundReturnLogic(
                    daoQueriesPropertyName,
                    functionName,
                    parameters,
                    type.wrappedDeclaration,
                    compoundSpec,
                    isNested = true
                )

                endControlFlow()
            }

            is DataTypeSpec.DataType.Compound -> {
                addCompoundMappingLogic(
                    functionName,
                    parameters,
                    compoundSpec,
                    isNested = isNested
                )
            }

            is DataTypeSpec.DataType.Entity,
            is DataTypeSpec.DataType.Class -> error("not supported here")
        }
    }

    private fun CodeBlock.Builder.addCompoundMappingLogic(
        functionName: String,
        parameters: String,
        compoundSpec: CompoundSpec,
        isNested: Boolean = false,
        parent: String? = null
    ) {
        val addedProperties = ArrayList<String>()
        val mainProperty = compoundSpec.mainProperty
        val mainPropertyName = mainProperty.declaration.simpleNameString

        when (val dataType = mainProperty.dataTypeSpec.dataType) {
            is DataTypeSpec.DataType.Entity -> {
                if (!isNested) {
                    addCompoundPropertyDeclaration(
                        functionName,
                        parameters,
                        mainProperty
                    )
                }


                addedProperties.add(mainPropertyName)
                compoundSpec.relations.forEach { relationSpec ->
                    val propertyName = relationSpec.property.declaration.simpleNameString
                    val parentColumn = dataType.spec.columns.first {  columnSpec ->
                        columnSpec.name == relationSpec.relation.parentColumn
                    }

                    val relationParameters = buildString {
                        append(
                            mainPropertyName,
                            SYMBOL_ACCESS_SIGN,
                            parentColumn.declaration.simpleNameString
                        )
                    }

                    addCompoundPropertyDeclaration(
                        functionName,
                        relationParameters,
                        relationSpec.property
                    )

                    addedProperties.add(propertyName)
                }
            }

            is DataTypeSpec.DataType.Compound -> {
                val newFunctionName = buildString {
                    append(functionName, mainPropertyName.toPascalCase())
                }

                addCompoundMappingLogic(
                    newFunctionName,
                    parameters,
                    dataType.spec,
                    isNested,
                    mainPropertyName
                )

                addedProperties.add(mainPropertyName)
                compoundSpec.relations.forEach { relationSpec ->
                    val entity = dataType.spec.mainProperty.dataTypeSpec.dataType as DataTypeSpec.DataType.Entity
                    val parentColumn = entity.spec.columns.first { columnSpec ->
                        columnSpec.name == relationSpec.relation.parentColumn
                    }

                    val propertyName = relationSpec.property.declaration.simpleNameString
                    val relationParameters = buildString {
                        append(
                            mainPropertyName,
                            SYMBOL_ACCESS_SIGN,
                            dataType.spec.mainProperty.declaration.simpleNameString,
                            SYMBOL_ACCESS_SIGN,
                            parentColumn.declaration.simpleNameString
                        )
                    }

                    addCompoundPropertyDeclaration(
                        functionName,
                        relationParameters,
                        relationSpec.property
                    )

                    addedProperties.add(propertyName)
                }
            }

            is DataTypeSpec.DataType.Class,
            is DataTypeSpec.DataType.Collection,
            is DataTypeSpec.DataType.Stream -> error("not supported")
        }


        val isForReturn = !isNested && parent.isNullOrBlank()
        val typeInitializer = typeInitializer(addedProperties, isForReturn = isForReturn)

        if (!parent.isNullOrBlank()) {
            addStatement(
                "val $parent = $typeInitializer",
                compoundSpec.declaration.toClassName()
            )
        } else {
            addStatement(
                typeInitializer,
                compoundSpec.declaration.toClassName()
            )
        }
    }

    private fun CodeBlock.Builder.addCompoundPropertyDeclaration(
        functionName: String,
        parameters: String,
        property: CompoundPropertySpec,
        awaitFunction: String? = null
    ) {
        val propertyName = property.declaration.simpleNameString
        val actualAwaitFunction = awaitFunction ?: property.dataTypeSpec.getAwaitFunction()

        val queriesFunctionName = buildString {
            append(functionName, propertyName.toPascalCase())
        }

        val functionCall = "queries.$queriesFunctionName($parameters).$actualAwaitFunction()"
        addStatement("val $propertyName = $functionCall")
    }

    private fun CodeBlock.Builder.addCompoundPropertyMapping(
        functionName: String,
        parameters: String,
        property: CompoundPropertySpec,
        awaitFunction: String? = null,
        isNested: Boolean = false
    ) {
        val propertyName = property.declaration.simpleNameString

        val functionCall = if (isNested) {
            propertyName
        } else {
            val actualAwaitFunction = awaitFunction ?: property.dataTypeSpec.getAwaitFunction()

            val queriesFunctionName = buildString {
                append(functionName, propertyName.toPascalCase())
            }

            "return queries.$queriesFunctionName($parameters).$actualAwaitFunction()"
        }

        beginControlFlow("$functionCall.map { $propertyName -> ")
    }

    private fun DataTypeSpec.getAwaitFunction(): String = when (val type = dataType) {
        is DataTypeSpec.DataType.Data -> if (isNullable) {
            "awaitAsOneOrNullIO"
        } else {
            "awaitAsOneNotNullIO"
        }

        is DataTypeSpec.DataType.Collection -> "awaitAsListIO"
        is DataTypeSpec.DataType.Stream -> when (type.wrappedDeclaration.dataType) {
            is DataTypeSpec.DataType.Collection -> "asFlowIOList"
            is DataTypeSpec.DataType.Data -> if (type.wrappedDeclaration.isNullable) {
                "asFlowIONullable"
            } else {
                "asFlowIONotNull"
            }

            is DataTypeSpec.DataType.Stream -> {
                logger.throwException("Nested streams are not supported",
                    this.reference
                )
            }
        }
    }

    data class Result(
        val className: ClassName
    )
}
