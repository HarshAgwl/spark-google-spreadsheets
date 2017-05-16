/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.potix2.spark.google.spreadsheets

import java.io.{File, FileInputStream}

import com.github.potix2.spark.google.spreadsheets.util.Credentials
import org.apache.spark.sql.sources.{BaseRelation, CreatableRelationProvider, RelationProvider, SchemaRelationProvider}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}

class DefaultSource extends RelationProvider with SchemaRelationProvider with CreatableRelationProvider {
  final val DEFAULT_CREDENTIAL_PATH = "/etc/gdata/credential.p12"

  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]) = {
    createRelation(sqlContext, parameters, null)
  }

  private[spreadsheets] def pathToSheetNames(parameters: Map[String, String]): (String, String) = {
    val path = parameters.getOrElse("path", sys.error("'path' must be specified for spreadsheets."))
    val elems = path.split('/')
    if (elems.length < 2)
      throw new Exception("'path' must be formed like '<spreadsheet>/<worksheet>'")

    (elems(0), elems(1))
  }

  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String], schema: StructType) = {
    val (spreadsheetName, worksheetName) = pathToSheetNames(parameters)
    val context = createSpreadsheetContext(parameters)
    createRelation(sqlContext, context, spreadsheetName, worksheetName, schema)
  }

  override def createRelation(sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String],
                              data: DataFrame): BaseRelation = {
    val (spreadsheetName, worksheetName) = pathToSheetNames(parameters)
    implicit val context = createSpreadsheetContext(parameters)
    val spreadsheet = SparkSpreadsheetService.findSpreadsheet(spreadsheetName)
    if (!spreadsheet.isDefined)
      throw new RuntimeException(s"no such a spreadsheet: $spreadsheetName")

    spreadsheet.get.addWorksheet(worksheetName, data.schema, data.na.fill("null").collect().toList, Util.toRowData)
    createRelation(sqlContext, context, spreadsheetName, worksheetName, data.schema)
  }

  private[spreadsheets] def createSpreadsheetContext(parameters: Map[String, String]) = {
    val serviceAccountId = parameters.getOrElse("serviceAccountId",
      sys.error("'serviceAccountId' must be specified for the google API account."))

    val credsS3BucketName = parameters.get("credsS3bucket")
    val credsS3ObjectName =  parameters.get("credsS3object")
    val privateKeyFile = parameters.get("privateKeyFile")

    if (credsS3BucketName.isDefined && credsS3ObjectName.isDefined) {
      SparkSpreadsheetService(serviceAccountId,
        Credentials.getCredentialsFromS3File(credsS3BucketName.get, credsS3ObjectName.get))
          }
    else if (privateKeyFile.isDefined) {
      SparkSpreadsheetService(serviceAccountId,
        Credentials.getPrivateKeyFromInputStream(new FileInputStream(new File(privateKeyFile.get))))
    }
    else {
      sys.error("'s3 config for creds' or 'privateKeyFile' must be specified for the google API account.")
    }
  }

  private[spreadsheets] def createRelation(sqlContext: SQLContext,
                                           context: SparkSpreadsheetService.SparkSpreadsheetContext,
                                           spreadsheetName: String,
                                           worksheetName: String,
                                           schema: StructType): SpreadsheetRelation =
    if (schema == null) {
      createRelation(sqlContext, context, spreadsheetName, worksheetName, None)
    }
    else {
      createRelation(sqlContext, context, spreadsheetName, worksheetName, Some(schema))
    }

  private[spreadsheets] def createRelation(sqlContext: SQLContext,
                                           context: SparkSpreadsheetService.SparkSpreadsheetContext,
                                           spreadsheetName: String,
                                           worksheetName: String,
                                           schema: Option[StructType]): SpreadsheetRelation =
    SpreadsheetRelation(context, spreadsheetName, worksheetName, schema)(sqlContext)
}
