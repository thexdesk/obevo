/**
 * Copyright 2017 Goldman Sachs.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.gs.obevo.db.apps.reveng

import com.gs.obevo.api.appdata.ObjectTypeAndNamePredicateBuilder
import com.gs.obevo.api.platform.ChangeAuditDao
import com.gs.obevo.api.platform.ChangeType
import com.gs.obevo.api.platform.DeployExecutionDao
import com.gs.obevo.api.platform.Platform
import com.gs.obevo.db.impl.core.checksum.DbChecksumDao
import freemarker.template.Configuration
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.eclipse.collections.api.block.function.Function
import org.eclipse.collections.api.block.predicate.Predicate2
import org.eclipse.collections.api.list.MutableList
import org.eclipse.collections.api.set.MutableSet
import org.eclipse.collections.impl.block.factory.HashingStrategies
import org.eclipse.collections.impl.block.factory.StringFunctions
import org.eclipse.collections.impl.factory.HashingStrategyMaps
import org.eclipse.collections.impl.factory.Lists
import org.eclipse.collections.impl.factory.Maps
import org.eclipse.collections.impl.factory.Multimaps
import org.eclipse.collections.impl.factory.Sets
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Locale

class RevengWriter {
    private val templateConfig: Configuration = Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS)

    init {
        // Where load the templates from:
        templateConfig.setClassForTemplateLoading(RevengWriter::class.java, "/")

        // Some other recommended settings:
        templateConfig.defaultEncoding = "UTF-8"
        templateConfig.locale = Locale.US
        templateConfig.templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
    }

    fun write(platform: Platform, allRevEngDestinations: MutableList<ChangeEntry>, outputDir: File, generateBaseline: Boolean, shouldOverwritePredicate: Predicate2<File, RevEngDestination>?, jdbcUrl: String?, dbHost: String?, dbPort: Int?, dbServer: String?, excludeObjects: String?) {
        var shouldOverwritePredicate = shouldOverwritePredicate
        outputDir.mkdirs()
        if (shouldOverwritePredicate == null) {
            shouldOverwritePredicate = defaultShouldOverwritePredicate()
        }

        val coreTablesToExclude = Multimaps.mutable.set.empty<String, String>()
        coreTablesToExclude.putAll(ChangeType.TABLE_STR, Sets.immutable.with(
                ChangeAuditDao.CHANGE_AUDIT_TABLE_NAME,
                DbChecksumDao.SCHEMA_CHECKSUM_TABLE_NAME,
                DeployExecutionDao.DEPLOY_EXECUTION_TABLE_NAME,
                DeployExecutionDao.DEPLOY_EXECUTION_ATTRIBUTE_TABLE_NAME
        ).collect(platform.convertDbObjectName()))

        var objectExclusionPredicateBuilder = platform.objectExclusionPredicateBuilder
                .add(coreTablesToExclude.toImmutable())
        if (excludeObjects != null) {
            objectExclusionPredicateBuilder = objectExclusionPredicateBuilder.add(ObjectTypeAndNamePredicateBuilder.parse(excludeObjects, ObjectTypeAndNamePredicateBuilder.FilterType.EXCLUDE))
        }
        val objectExclusionPredicate = objectExclusionPredicateBuilder.build(
                Function { dest -> dest.dbObjectType.name },
                Function<RevEngDestination, String> { revEngDestination -> revEngDestination.objectName }
        )

        val revEngDestinationMap = HashingStrategyMaps.mutable.of<RevEngDestination, MutableList<ChangeEntry>>(HashingStrategies.fromFunction(Function<RevEngDestination, String> { revEngDestination -> revEngDestination.identity }))
        for (allRevEngDestination in allRevEngDestinations.select { objectExclusionPredicate.accept(it.destination) }) {
            var changeEntries: MutableList<ChangeEntry>? = revEngDestinationMap[allRevEngDestination.destination]
            if (changeEntries == null) {
                changeEntries = Lists.mutable.empty()
                revEngDestinationMap[allRevEngDestination.destination] = changeEntries
            }

            changeEntries!!.add(allRevEngDestination)
        }

        for (pair in revEngDestinationMap.keyValuesView()) {
            val dest = pair.one

            val changes = pair.two
                    .sortedBy { it.name ?: "" }
                    .sortedBy { it.order }

            val metadataAnnotations = changes.flatMap { it.metadataAnnotations }.toMutableList()

            val metadataString = if (metadataAnnotations.isEmpty()) "" else "//// METADATA " + metadataAnnotations.joinToString(" ") + "\n"

            val mainSql = metadataString + changes.joinToString("\n") { it.sql.trim() }

            try {
                val mainDestinationFile = dest.getDestinationFile(outputDir, false)
                if (dest.isBaselineEligible) {
                    if (shouldOverwritePredicate.accept(mainDestinationFile, dest)) {
                        val lines = mutableListOf<String>()

                        var prevChange: String? = null

                        if (!metadataString.isEmpty()) {
                            lines.add(metadataString)
                        }
                        for (changeEntry in changes) {
                            if (prevChange == null || prevChange != changeEntry.name) {
                                val annotationStr = if (StringUtils.isNotEmpty(changeEntry.changeAnnotation))
                                    " " + changeEntry.changeAnnotation
                                else
                                    ""
                                lines.add("//// CHANGE${annotationStr} name=${changeEntry.name}")
                            }

                            lines.add(changeEntry.sql.trim())
                            lines.add("")

                            prevChange = changeEntry.name
                        }

                        FileUtils.writeStringToFile(mainDestinationFile, lines.joinToString("\n"))
                    }
                } else {
                    FileUtils.writeStringToFile(mainDestinationFile, mainSql)
                }

                if (generateBaseline && dest.isBaselineEligible) {
                    FileUtils.writeStringToFile(dest.getDestinationFile(outputDir, true), mainSql)
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

        }

        val schemas = allRevEngDestinations.collect({ entry -> entry.destination.schema }, Sets.mutable.empty())

        try {
            FileWriter(File(outputDir, "system-config.xml")).use { fileWriter ->
                val template = templateConfig.getTemplate("deployer/reveng/system-config-template.xml.ftl")

                val params = Maps.mutable.empty<String, Any>()
                params["platform"] = platform.name
                params["schemas"] = schemas
                params["jdbcUrl"] = jdbcUrl
                params["dbHost"] = dbHost
                params["dbPort"] = dbPort?.toString()
                params["dbServer"] = dbServer
                template.process(params, fileWriter)
            }
        } catch (e: TemplateException) {
            throw RuntimeException(e)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

    }

    companion object {
        @JvmStatic
        fun defaultShouldOverwritePredicate(): Predicate2<File, RevEngDestination> {
            return Predicate2 { mainFile, dbFileRep -> !mainFile.exists() }
        }

        @JvmStatic
        fun overwriteAllPredicate(): Predicate2<File, RevEngDestination> {
            return Predicate2 { mainFile, dbFileRep -> true }
        }

        @JvmStatic
        fun overwriteForSpecificTablesPredicate(
                tableNames: MutableSet<String>): Predicate2<File, RevEngDestination> {
            return Predicate2 { mainFile, dbFileRep ->
                !mainFile.exists() || tableNames.collect(StringFunctions.toLowerCase()).contains(
                        dbFileRep.objectName.toLowerCase())
            }
        }
    }
}
