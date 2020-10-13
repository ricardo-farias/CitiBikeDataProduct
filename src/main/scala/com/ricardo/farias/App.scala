package com.ricardo.farias

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object App {
  def main(args : Array[String]): Unit = {
    val config = new SparkConf().setMaster(Constants.master).setAppName(Constants.appName)
    implicit val sparkSession = if (Constants.env == "dev") {
      SparkSession.builder().master(Constants.master).config(config).getOrCreate()
    } else {
      SparkSession.builder().master(Constants.master)
        .config("hive.metastore.connect.retries", 5)
        .config("hive.metastore.client.factory.class",
          "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory")
        .config("spark.sql.sources.partitionOverwriteMode", "dynamic")
        .enableHiveSupport().getOrCreate()
    }

    val fileStorage: FileSystem = if (Constants.env == "dev") LocalFileSystem
    else {
      sparkSession.sparkContext.hadoopConfiguration.set("spark.sql.sources.partitionOverwriteMode", "dynamic")
      sparkSession.sparkContext.hadoopConfiguration.set("hive.metastore.connect.retries", "5")
      sparkSession.sparkContext.hadoopConfiguration.set("hive.metastore.client.factory.class",
        "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory")
      S3FileSystem
    }

    val tripSchema = fileStorage.readSchemaFromJson("raw", "bike-data","citibikedataschema.json")(sparkSession.sparkContext)
    val tripResults = fileStorage.readCsv(tripSchema, "raw","bike-data","201306-citibike-tripdata.csv", "MM/dd/yy hh:mm")
    val tripData = tripResults._1
    tripData.show()
    val corruptTripData = tripResults._2
    corruptTripData.show()
    fileStorage.write("canonical", "bike-data", "citibiketripdata201306", tripData)
    fileStorage.write("error", "bike-data" , "citibiketripdata201306_err", corruptTripData)


    val stationSchema = fileStorage.readSchemaFromJson("raw","bike-data","citibikestationdataschema.json")(sparkSession.sparkContext)
    val stationResults = fileStorage.readJsonForStationData(stationSchema,"raw", "bike-data","201308-citibike-stationdata.json")
    val stationData = stationResults._1
    stationData.show()
    val corruptStationData = stationResults._2
    corruptStationData.show()
    fileStorage.write("canonical","bike-data","citibikestationdata201308", stationData)
    fileStorage.write("error","bike-data","citibikestationdata201308_err", corruptStationData)

  }
}
