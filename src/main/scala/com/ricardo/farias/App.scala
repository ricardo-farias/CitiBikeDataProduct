package com.ricardo.farias

import com.amazonaws.auth.profile.ProfileCredentialsProvider
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
      val envAuth = new ProfileCredentialsProvider("profile sparkapp")
      sparkSession.sparkContext.hadoopConfiguration.set("spark.sql.sources.partitionOverwriteMode", "dynamic")
      sparkSession.sparkContext.hadoopConfiguration.set("fs.s3a.access.key", envAuth.getCredentials.getAWSAccessKeyId)
      sparkSession.sparkContext.hadoopConfiguration.set("fs.s3a.secret.key", envAuth.getCredentials.getAWSSecretKey)
      sparkSession.sparkContext.hadoopConfiguration.set("fs.s3a.aws.credentials.provider",
        "com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper,com.amazonaws.auth.profile.ProfileCredentialsProvider")
      sparkSession.sparkContext.hadoopConfiguration.set("fs.s3a.endpoint", "s3.amazonaws.com")
      sparkSession.sparkContext.hadoopConfiguration.set("hive.metastore.connect.retries", "5")
      sparkSession.sparkContext.hadoopConfiguration.set("hive.metastore.client.factory.class",
        "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory")
      S3FileSystem
    }

    val tripSchema = fileStorage.readSchemaFromJson("raw","citibikedataschema.json")(sparkSession.sparkContext)
    val tripResults = fileStorage.readCsv(tripSchema, "raw","201306-citibike-tripdata.csv", "MM/dd/yy hh:mm")
    val tripData = tripResults._1
    tripData.show()
    val corruptTripData = tripResults._2
    corruptTripData.show()
    fileStorage.write("citibiketripdata201306", tripData, "canonical")
    fileStorage.write("citibiketripdata201306_err", corruptTripData, "error")


    val stationSchema = fileStorage.readSchemaFromJson("raw","citibikestationdataschema.json")(sparkSession.sparkContext)
    val stationResults = fileStorage.readJsonForStationData(stationSchema,"raw","201308-citibike-stationdata.json")
    val stationData = stationResults._1
    stationData.show()
    val corruptStationData = stationResults._2
    corruptStationData.show()
    fileStorage.write("citibikestationdata201308", stationData, "canonical")
    fileStorage.write("citibikestationdata201308_err", corruptStationData, "error")

  }
}
