package com.ricardo.farias

import java.io.File

import org.apache.spark.SparkContext
import org.apache.spark.sql.catalyst.parser.LegacyTypeStringParser
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scala.io.{BufferedSource, Source}
import scala.util.Try

abstract class FileSystem {
  def readJson(schema: StructType, filePath: String, product: String,filename: String, timeStampFormat: String = "MM/dd/yyyy hh:mm:ss a")(implicit sparkSession: SparkSession) : (DataFrame, DataFrame)
  def readJsonForStationData(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String = "MM/dd/yyyy hh:mm:ss a")(implicit sparkSession: SparkSession) : (DataFrame, DataFrame)
  def readCsv(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String = "MM/dd/yyyy hh:mm:ss a")(implicit sparkSession: SparkSession) : (DataFrame, DataFrame)
  def readSchemaFromJson(filePath: String, product: String, filename: String)(implicit sparkContext: SparkContext) : StructType
  def write(filePath: String, product: String, filename: String, data: DataFrame)(implicit sparkSession: SparkSession) : Unit
  def listObjects() : Unit
}

object LocalFileSystem extends FileSystem {

  var ROOT_DIRECTORY = Constants.directory

  override def readJson(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String)(implicit sparkSession: SparkSession): (DataFrame, DataFrame) = {
    val df = sparkSession.read.options(
      Map("dateFormat"->"MM/dd/yy",
        "timestampFormat"->timeStampFormat,
        "columnNameOfCorruptRecord"->"Corrupted",
        "nullValues"->"NULL"))
      .schema(schema)
      .json(f"${ROOT_DIRECTORY}/${product}/${filePath}/${filename}")
    val badDF = df.filter(df.col("Corrupted").isNotNull).toDF
    val goodDF = df.filter(df.col("Corrupted").isNull).toDF
    (goodDF, badDF)
  }

  override def readJsonForStationData(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String)(implicit sparkSession: SparkSession): (DataFrame, DataFrame) = {

    val flattenedDF = sparkSession.read.options(
      Map("dateFormat"->"MM/dd/yy",
        "columnNameOfCorruptRecord"->"Corrupted",
        "multiline"->"true",
        "timestampFormat"-> "yyyy-MM-dd hh:mm:ss.SSSSSSZ",
        "nullValues"->"NULL"
      ))
      .schema(schema)
      .json(f"${ROOT_DIRECTORY}/${product}/${filePath}/${filename}")
      .selectExpr("explode(stations) as station_array","*").select("station_array.*","Corrupted")
      .select("extra.*","*").drop("extra").withColumnRenamed("last_updated","last_updated_tmp")
      .selectExpr("from_unixtime(last_updated_tmp) as last_updated","*").drop("last_updated_tmp")

//    flattenedDF = flattenedDF.withColumn("last_updated",from_unixtime($"last_updated"))
    val badDF = flattenedDF.filter(flattenedDF.col("Corrupted").isNotNull).toDF
    val goodDF = flattenedDF.filter(flattenedDF.col("Corrupted").isNull).toDF
    (goodDF, badDF)
  }

  override def readCsv(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String)(implicit sparkSession: SparkSession) : (DataFrame, DataFrame) = {
    val df = sparkSession.read.format("csv")
      .options(
        Map(
          "header"-> "true",
          "timestampFormat"-> timeStampFormat,
          "nullValue"-> "NULL",
          "ignoreTrailingWhiteSpace"->"true",
          "ignoreLeadingWhiteSpace"->"true",
          "columnNameOfCorruptRecord"->"Corrupted"
        ))
      .schema(schema)
      .load(s"${ROOT_DIRECTORY}/${product}/${filePath}/${filename}")

    val badDF = df.filter(df.col("Corrupted").isNotNull).toDF
    val goodDF = df.filter(df.col("Corrupted").isNull).toDF
    (goodDF, badDF)
  }

  override def readSchemaFromJson(filePath: String, product: String, filename: String)(implicit sparkContext: SparkContext) : StructType = {
    val source: BufferedSource = Source.fromFile(s"${ROOT_DIRECTORY}/${product}/${filePath}/${filename}")
    val data = source.getLines.toList.mkString("\n")
    source.close()
    val schema  = Try(DataType.fromJson(data)).getOrElse(LegacyTypeStringParser.parse(data)) match {
      case t: StructType => t
      case _             => throw new RuntimeException(s"Failed parsing StructType: $data")
    }
    schema
  }

  def setRootDirectory(directory: String) : Unit = ROOT_DIRECTORY = directory

  override def write(filePath: String, product: String, filename: String, data: DataFrame)(implicit sparkSession: SparkSession)  : Unit = {
    data.write.mode(SaveMode.Overwrite).parquet(f"${ROOT_DIRECTORY}/${product}/${filePath}/${filename}.parquet")
  }

  override def listObjects() : Unit = {
    val dir = new File(ROOT_DIRECTORY)
    dir.listFiles().foreach(println)
  }

}

object S3FileSystem extends FileSystem {
  private val bucket = Constants.bucket

  override def readCsv(schema : StructType, filePath: String, product: String, filename: String, timeStampFormat: String)(implicit sparkSession: SparkSession) : (DataFrame, DataFrame) = {
    val df = sparkSession.read.format("csv")
      .options(
        Map(
          "header"-> "true",
          "dateFormat"-> "MM/dd/yyyy",
          "timestampFormat"->timeStampFormat,
          "nullValue"-> "NULL",
          "ignoreTrailingWhiteSpace"->"true",
          "ignoreLeadingWhiteSpace"->"true",
          "columnNameOfCorruptRecord"->"Corrupted"
        ))
      .schema(schema)
      .load(s"s3a://${bucket}/${product}/${filePath}/${filename}")
    val badDF = df.filter(df.col("Corrupted").isNotNull).toDF
    val goodDF = df.filter(df.col("Corrupted").isNull).toDF
    (goodDF, badDF)
  }

  override def readJson(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String)(implicit sparkSession: SparkSession) : (DataFrame, DataFrame) = {
    val df = sparkSession.read.options(
      Map("dateFormat"->"MM/dd/yy",
        "timestampFormat"->timeStampFormat,
        "columnNameOfCorruptRecord"->"Corrupted",
        "nullValues"->"NULL"))
      .schema(schema)
      .json(f"s3a://${Constants.bucket}/${product}//${filePath}/${filename}")
    val badDF = df.filter(df.col("Corrupted").isNotNull).toDF
    val goodDF = df.filter(df.col("Corrupted").isNull).toDF
    (goodDF, badDF)
  }

  override def readJsonForStationData(schema: StructType, filePath: String, product: String, filename: String, timeStampFormat: String)(implicit sparkSession: SparkSession) : (DataFrame, DataFrame) = {
    val flattenedDF = sparkSession.read.options(
      Map("dateFormat"->"MM/dd/yy",
        "columnNameOfCorruptRecord"->"Corrupted",
        "multiline"->"true",
        "timestampFormat"-> "yyyy-MM-dd hh:mm:ss.SSSSSSZ",
        "nullValues"->"NULL"
      ))
      .schema(schema)
      .json(f"s3a://${bucket}/${product}/${filePath}/${filename}")
      .selectExpr("explode(stations) as station_array","*").select("station_array.*","Corrupted")
      .select("extra.*","*").drop("extra").withColumnRenamed("last_updated","last_updated_tmp")
      .selectExpr("from_unixtime(last_updated_tmp) as last_updated","*").drop("last_updated_tmp")

      val badDF = flattenedDF.filter(flattenedDF.col("Corrupted").isNotNull).toDF
      val goodDF = flattenedDF.filter(flattenedDF.col("Corrupted").isNull).toDF
      (goodDF, badDF)
    }

  override def readSchemaFromJson(filePath: String, product: String, filename: String)(implicit sparkContext: SparkContext) : StructType = {
    val source = sparkContext.textFile(f"s3a://${bucket}/${product}/${filePath}/${filename}").collect()

    val data = source.toList.mkString("\n")
    val schema  = Try(DataType.fromJson(data)).getOrElse(LegacyTypeStringParser.parse(data)) match {
      case t: StructType => t
      case _             => throw new RuntimeException(s"Failed parsing StructType: $data")
    }
    schema
  }

  override def write(filePath: String, product: String, filename: String, data: DataFrame)(implicit sparkSession: SparkSession) : Unit = {
    val name = if (filename.contains("/")) filename.split("/")(1) else filename
    data.createOrReplaceTempView(filename)
    //    sparkSession.sql(s"""CREATE TABLE default.${name}
    //      USING PARQUET
    //      OPTIONS ('compression'='snappy')
    //      LOCATION 's3://${bucket}/${filename}'
    //      SELECT * FROM ${name}""")
    //data.write.mode(SaveMode.Overwrite).parquet(f"s3a://${bucket}/${filename}.parquet")
    data.write.format("parquet")
      .mode(SaveMode.Overwrite)
      .option("path", f"s3a://${bucket}/${product}/${filePath}/${filename}.parquet")
      .saveAsTable(s"${Constants.database}.${name}")
  }

  override def listObjects() : Unit = {
    // TODO
  }

}