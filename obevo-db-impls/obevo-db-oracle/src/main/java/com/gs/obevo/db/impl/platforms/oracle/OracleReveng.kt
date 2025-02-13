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
package com.gs.obevo.db.impl.platforms.oracle

import com.gs.obevo.api.platform.ChangeType
import com.gs.obevo.db.apps.reveng.AbstractDdlReveng
import com.gs.obevo.db.apps.reveng.AquaRevengArgs
import com.gs.obevo.db.apps.reveng.LineParseOutput
import com.gs.obevo.db.apps.reveng.RevengPattern
import com.gs.obevo.db.impl.core.jdbc.JdbcHelper
import com.gs.obevo.impl.changetypes.UnclassifiedChangeType
import com.gs.obevo.impl.util.MultiLineStringSplitter
import com.gs.obevo.util.inputreader.Credential
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.eclipse.collections.api.block.function.Function
import org.eclipse.collections.api.list.ImmutableList
import org.eclipse.collections.api.list.MutableList
import org.eclipse.collections.impl.block.factory.StringPredicates
import org.eclipse.collections.impl.factory.Lists
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintStream
import java.io.StringWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.sql.Clob
import java.sql.Connection
import java.util.regex.Pattern

internal class OracleReveng
    : AbstractDdlReveng(
        OracleDbPlatform(),
        MultiLineStringSplitter("~", true),
        Lists.immutable.of(
                StringPredicates.contains("CLP file was created using DB2LOOK"),
                StringPredicates.startsWith("CREATE SCHEMA"),
                StringPredicates.startsWith("SET CURRENT SCHEMA"),
                StringPredicates.startsWith("SET CURRENT PATH"),
                StringPredicates.startsWith("COMMIT WORK"),
                StringPredicates.startsWith("CONNECT RESET"),
                StringPredicates.startsWith("TERMINATE"),
                StringPredicates.startsWith("SET NLS_STRING_UNITS = 'SYSTEM'")
        ),
        revengPatterns,
        null) {
    init {
        setStartQuote(QUOTE)
        setEndQuote(QUOTE)
    }

    override fun doRevengOrInstructions(out: PrintStream, args: AquaRevengArgs, interimDir: File): Boolean {
        val env = getDbEnvironment(args)

        val jdbcFactory = OracleJdbcDataSourceFactory()
        val ds = jdbcFactory.createDataSource(env, Credential(args.username, args.password), 1)
        val jdbc = JdbcHelper(null, false)
        var charEncoding: Charset

        if(StringUtils.isNotEmpty(args.charsetEncoding))
            charEncoding = Charset.forName(args.charsetEncoding)
        else
            charEncoding = Charset.defaultCharset()
        interimDir.mkdirs()

        ds.connection.use { conn ->
            val bufferedWriter = Files.newBufferedWriter(interimDir.toPath().resolve("output.sql"), charEncoding)
            bufferedWriter.use { fileWriter ->
                // https://docs.oracle.com/database/121/ARPLS/d_metada.htm#BGBJBFGE
                // Note - can't remap schema name, object name, tablespace name within JDBC calls; we will leave that to the existing code in AbstractDdlReveng
                jdbc.update(conn, "begin DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'STORAGE',false); end;")
                jdbc.update(conn, "begin DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',true); end;")

                // Sorting algorithm:
                // - We use SORT_ORDER1 to split between table and index changes, as we want index changes to come after tables,
                // but the SQL query only gives the object name as the index name; hence, we can't group easily.
                // - We use SORT_ORDER2 for having comments come after regular table changes.
                val queryResults = queryObjects(jdbc, conn, args.dbSchema).plus(queryComments(jdbc, conn, args.dbSchema))
                        .sortedWith(compareBy({ it["SORT_ORDER1"] as Comparable<*> }, { it["OBJECT_NAME"] as String }, { it["SORT_ORDER2"] as Comparable<*> }))
                queryResults.forEach { map ->
                    val objectType = map["OBJECT_TYPE"] as String
                    var clobAsString = clobToString(map["OBJECT_DDL"]!!)

                    // TODO all parsing like this should move into the core AbstractReveng logic so that we can do more unit-test logic around this parsing
                    clobAsString = clobAsString.trimEnd()
                    clobAsString = clobAsString.replace(";+\\s*$".toRegex(RegexOption.DOT_MATCHES_ALL), "")  // remove ending semi-colons from generated SQL
                    clobAsString = clobAsString.replace("\\/+\\s*$".toRegex(RegexOption.DOT_MATCHES_ALL), "")  // some generated SQLs end in /
                    if (objectType.contains("PACKAGE")) {
                        clobAsString = clobAsString.replace("^\\/$".toRegex(RegexOption.MULTILINE), "")
                    }
                    clobAsString = clobAsString.trimEnd()

                    LOG.debug("Content for {}: {}", objectType, clobAsString)

                    val sqlsToWrite = if (objectType.equals("COMMENT")) clobAsString.split(";$".toRegex(RegexOption.MULTILINE)) else listOf(clobAsString)

                    sqlsToWrite.forEach {
                        fileWriter.write(it)
                        fileWriter.newLine()
                        fileWriter.write("~")
                        fileWriter.newLine()
                    }

                }

            }
            jdbc.update(conn, "begin DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'DEFAULT',true); end;")
        }

        return true
    }

    private fun queryObjects(jdbc: JdbcHelper, conn: Connection, schema: String): List<Map<String, Any>> {
        try {
            return jdbc.queryForList(conn, getObjectQuery(schema, true, null))
        } catch (_: Exception) {
            // the bulk query may fail if one of the objects cannot be rendered. Hence, we fall back to individual queries
            LOG.info("Failed executing bulk query for all objects; falling back to individual queries")

            // First get the objects
            val objects = jdbc.queryForList(conn, getObjectQuery(schema, false, null))

            // Now query each individually
            return objects.flatMap {
                try {
                    jdbc.queryForList(conn, getObjectQuery(schema, true, it["OBJECT_NAME"] as String))
                } catch (e2: Exception) {
                    // in case of failures, write the object out for the reverse-engineering to process
                    val exceptionText = """OBEVO EXCEPTION ${it["OBJECT_NAME"]!!} of type ${it["OBJECT_TYPE"]!!}
/*
Please report this as an Issue on the Obevo Github page so that we can improve the reverse-engineering logic.
For now, resolve this on your side.
${ExceptionUtils.getStackTrace(e2)}
*/
end
"""
                    listOf(mapOf(
                            "SORT_ORDER1" to it["SORT_ORDER1"]!!,
                            "OBJECT_NAME" to it["OBJECT_NAME"]!!,
                            "SORT_ORDER2" to it["SORT_ORDER2"]!!,
                            "OBJECT_TYPE" to it["OBJECT_TYPE"]!!,
                            "OBJECT_DDL" to exceptionText
                    ))
                }
            }.toSet().toList()
        }
    }

    private fun getObjectQuery(schema: String, retrieveDefinitions: Boolean, objectName: String?): String {
        val objectDefSql = if (retrieveDefinitions) "dbms_metadata.get_ddl(REPLACE(obj.OBJECT_TYPE,' ','_'), obj.OBJECT_NAME, obj.owner) || ';'" else " 'blank'";
        val objectClause = objectName?.let { " AND obj.OBJECT_NAME = '${it}'"} ?: ""

        // we exclude:
        // PACKAGE BODY as those are generated via package anyway
        // DATABASE LINK as the get_ddl function doesn't work with it. We may support this later on
        return """
WITH MY_CONSTRAINT_INDICES AS (
    SELECT DISTINCT INDEX_OWNER, INDEX_NAME
    FROM DBA_CONSTRAINTS
    WHERE OWNER = '${schema}' AND INDEX_OWNER IS NOT NULL AND INDEX_NAME IS NOT NULL
    AND CONSTRAINT_NAME NOT LIKE 'BIN${'$'}%'
)
select CASE WHEN obj.OBJECT_TYPE = 'INDEX' THEN 2 ELSE 1 END SORT_ORDER1
    , obj.OBJECT_NAME
    , 1 AS SORT_ORDER2
    , obj.OBJECT_TYPE
    , ${objectDefSql} AS OBJECT_DDL
FROM DBA_OBJECTS obj
LEFT JOIN DBA_TABLES tab ON obj.OBJECT_TYPE = 'TABLE' AND obj.OWNER = tab.OWNER and obj.OBJECT_NAME = tab.TABLE_NAME
LEFT JOIN MY_CONSTRAINT_INDICES conind ON obj.OBJECT_TYPE = 'INDEX' AND obj.OWNER = conind.INDEX_OWNER AND obj.OBJECT_NAME = conind.INDEX_NAME
WHERE obj.OWNER = '${schema}'
    AND obj.GENERATED = 'N'  -- do not include generated objects
    AND obj.OBJECT_TYPE NOT IN ('PACKAGE BODY', 'LOB', 'TABLE PARTITION', 'DATABASE LINK')
    AND obj.OBJECT_NAME NOT LIKE 'MLOG${'$'}%' AND obj.OBJECT_NAME NOT LIKE 'RUPD${'$'}%'  -- exclude the helper tables for materialized views
    AND obj.OBJECT_NAME NOT LIKE 'SYS_%'  -- exclude other system tables
    AND conind.INDEX_OWNER IS NULL  -- exclude primary keys created as unique indexes, as the CREATE TABLE already includes it; SQL logic is purely in the join above
    AND (tab.NESTED is null OR tab.NESTED = 'NO')
    ${objectClause}
"""
    }

    private fun queryComments(jdbc: JdbcHelper, conn: Connection, schema: String): MutableList<MutableMap<String, Any>> {
        val commentSql = """
SELECT 1 SORT_ORDER1  -- group comment ordering with tables and other objects, and ahead of indices
    , obj.OBJECT_NAME
    , 2 AS SORT_ORDER2  -- sort comments last compared to other table changes
    , 'COMMENT' as OBJECT_TYPE
    , dbms_metadata.get_dependent_ddl('COMMENT', obj.OBJECT_NAME, obj.OWNER) || ';' AS OBJECT_DDL
FROM (
    -- inner table is needed to extract all the object names that have comments (we cannot determine this solely from DBA_OBJECTS)
    -- use DISTINCT as DBA_COL_COMMENTS may have multiple rows for a single table
    SELECT DISTINCT obj.OWNER, obj.OBJECT_NAME, obj.OBJECT_TYPE
    FROM DBA_OBJECTS obj
    LEFT JOIN DBA_TAB_COMMENTS tabcom ON obj.OWNER = tabcom.OWNER and obj.OBJECT_NAME = tabcom.TABLE_NAME and tabcom.COMMENTS IS NOT NULL
    LEFT JOIN DBA_COL_COMMENTS colcom ON obj.OWNER = colcom.OWNER and obj.OBJECT_NAME = colcom.TABLE_NAME and colcom.COMMENTS IS NOT NULL
    WHERE obj.OWNER = '${schema}'
    and (tabcom.OWNER is not null OR colcom.OWNER is not null)
) obj
ORDER BY 1, 2
"""

        // note - need comments grouped in order with tables, but indexes
        /* keeping this for the future as we support more object types with comments
            LEFT JOIN DBA_OPERATOR_COMMENTS opcom ON obj.OWNER = opcom.OWNER and obj.OBJECT_NAME = opcom.OPERATOR_NAME and opcom.COMMENTS IS NOT NULL
            LEFT JOIN DBA_INDEXTYPE_COMMENTS indexcom ON obj.OWNER = indexcom.OWNER and obj.OBJECT_NAME = indexcom.INDEXTYPE_NAME and indexcom.COMMENTS IS NOT NULL
            LEFT JOIN DBA_MVIEW_COMMENTS mviewcom ON obj.OWNER = mviewcom.OWNER and obj.OBJECT_NAME = mviewcom.MVIEW_NAME and mviewcom.COMMENTS IS NOT NULL
            LEFT JOIN DBA_EDITION_COMMENTS edcom ON obj.OBJECT_NAME = edcom.EDITION_NAME and edcom.COMMENTS IS NOT NULL  -- note - no OWNER supported here
         */
        return jdbc.queryForList(conn, commentSql)
    }

    private fun clobToString(clobObject: Any): String {
        if (clobObject is String) {
            return clobObject
        } else if (clobObject is Clob) {
            clobObject.characterStream.use {
                val w = StringWriter()
                IOUtils.copy(it, w)
                return w.toString()
            }
        } else {
            throw RuntimeException("Unexpected type " + clobObject.javaClass + "; expecting String or Clob")
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(OracleReveng::class.java)
        private val QUOTE = "\""

        private val revengPatterns: ImmutableList<RevengPattern>
            get() {
                val schemaNameSubPattern = AbstractDdlReveng.getSchemaObjectPattern(QUOTE, QUOTE)
                val namePatternType = RevengPattern.NamePatternType.TWO

                // need this function to split the package and package body lines, as the Oracle reveng function combines them together
                val prependBodyLineToPackageBody = object : Function<String, LineParseOutput> {
                    private val packageBodyPattern = Pattern.compile("(?i)create\\s+(?:or\\s+replace\\s+)(?:editionable\\s+)package\\s+body\\s+$schemaNameSubPattern", Pattern.DOTALL)

                    override fun valueOf(sql: String): LineParseOutput {
                        val matcher = packageBodyPattern.matcher(sql)
                        if (matcher.find()) {
                            val output = sql.substring(0, matcher.start()) + "\n//// BODY\n" + sql.substring(matcher.start())
                            return LineParseOutput(output)
                        }
                        return LineParseOutput(sql)
                    }
                }
                return Lists.immutable.with(
                        RevengPattern(UnclassifiedChangeType.INSTANCE.name, namePatternType, "(?i)obevo\\s+exception\\s+$schemaNameSubPattern"),
                        RevengPattern(ChangeType.SEQUENCE_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)?sequence\\s+$schemaNameSubPattern").withPostProcessSql(AbstractDdlReveng.REPLACE_TABLESPACE).withPostProcessSql(AbstractDdlReveng.REMOVE_QUOTES),
                        RevengPattern(ChangeType.TABLE_STR, namePatternType, "(?i)create\\s+table\\s+$schemaNameSubPattern").withPostProcessSql(AbstractDdlReveng.REPLACE_TABLESPACE).withPostProcessSql(AbstractDdlReveng.REMOVE_QUOTES),
                        RevengPattern(ChangeType.TABLE_STR, namePatternType, "(?i)alter\\s+table\\s+$schemaNameSubPattern").withPostProcessSql(AbstractDdlReveng.REMOVE_QUOTES),
                        // Comments on columns can apply for both tables and views, with no way to split this from the text. Hence, we don't specify the object type here and rely on the comments being written after the object in the reverse-engineering extraction
                        RevengPattern(null, namePatternType, "(?i)comment\\s+on\\s+(?:\\w+)\\s+$schemaNameSubPattern").withPostProcessSql(AbstractDdlReveng.REMOVE_QUOTES),
                        RevengPattern(ChangeType.TABLE_STR, namePatternType, "(?i)create\\s+(?:unique\\s+)?index\\s+$schemaNameSubPattern\\s+on\\s+$schemaNameSubPattern", 2, 1, "INDEX").withPostProcessSql(AbstractDdlReveng.REPLACE_TABLESPACE).withPostProcessSql(AbstractDdlReveng.REMOVE_QUOTES),
                        RevengPattern(ChangeType.FUNCTION_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)?(?:force\\s+)?(?:editionable\\s+)?function\\s+$schemaNameSubPattern"),
                        RevengPattern(ChangeType.VIEW_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)?(?:force\\s+)?(?:editionable\\s+)?(?:editioning\\s+)?view\\s+$schemaNameSubPattern"),
                        RevengPattern(ChangeType.SP_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)(?:editionable\\s+)?procedure\\s+$schemaNameSubPattern"),
                        RevengPattern(ChangeType.USERTYPE_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)(?:editionable\\s+)?type\\s+$schemaNameSubPattern"),
                        RevengPattern(ChangeType.PACKAGE_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)(?:editionable\\s+)?package\\s+$schemaNameSubPattern").withPostProcessSql(prependBodyLineToPackageBody),
                        RevengPattern(ChangeType.SYNONYM_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)(?:editionable\\s+)?synonym\\s+$schemaNameSubPattern"),
                        RevengPattern(ChangeType.TRIGGER_STR, namePatternType, "(?i)create\\s+(?:or\\s+replace\\s+)(?:editionable\\s+)?trigger\\s+$schemaNameSubPattern")
                )
            }
    }
}
