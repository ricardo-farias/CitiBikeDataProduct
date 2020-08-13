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
    //fileStorage.listObjects()

//    val good = fileStorage.schemalessReadCsv("201306-citibike-tripdata.csv")
//    val schema = fileStorage.readSchemaFromJson("citibikedataschema.json")(sparkSession.sparkContext)
//    val results = fileStorage.readCsv(schema, "201306-citibike-tripdata.csv", "MM/dd/yy hh:mm")
//    val good = results._1
//    good.show()
//    val bad = results._2
//    bad.show()
//    fileStorage.write("citibiketripdata201306", good)

    val stationSchema = fileStorage.readSchemaFromJson("citibikestationdataschema.json")(sparkSession.sparkContext)
    val stationResults = fileStorage.readJsonForStationData(stationSchema,"201308-citibike-stationdata.json")
  }
}
