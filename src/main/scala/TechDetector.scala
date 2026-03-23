import org.apache.spark.sql.SparkSession
import sttp.client4._

object TechDetector extends App {

  /** Load domains from Parquet file */
  def loadDomains(parquetPath: String) = {
    val spark = SparkSession.builder()
      .appName("TechDetector")
      .master("local[*]")
      .getOrCreate()

    /**Set log level to WARN in order to observe better the problems */
    spark.sparkContext.setLogLevel("WARN")

    /**Read the Parquet file and extract the root domains */
    val df = spark.read.parquet(parquetPath)
    df.printSchema()
    df.show(10, truncate = false)

    /**From the schema, select the root_domain column*/
    val domains = df.select("root_domain")
      .collect()
      .map(_.getString(0))
      .toSeq

    /**Print the total number of domains loaded */
    println(s"Total domains: ${domains.size}")

    spark.stop()
    domains
  }

  def fetchDomains(domains: Seq[String]) = {
    /**Create an HTTP client backend using sttp */
    val backend = DefaultSyncBackend()

    /**Iterate over the domains and make HTTP requests to fetch headers and HTML content*/
    domains.foreach { domain =>
      val request = basicRequest
        .get(uri"https://$domain")
        .readTimeout(scala.concurrent.duration.Duration(10, "s")) /**Setting a 10 seconds timeout for the request */

      try {
        /**Get the response from the server */
        val response = request.send(backend)

        /**Checking the status code of the response */
        val html = response.body match {
          case Right(body) => body
          case Left(err)   => s"Error: $err"
        }

      /** Print the headers and the first 500 characters of the HTML content for each domain */
        val headers = response.headers
        println(s"\n$domain")
        println(s"Headers:\n${headers.mkString("\n")}")
        println(s"HTML (first 500 chars):\n${html.take(500)}")

      } catch {
        /**If the request fails (e.g., due to timeout, connection/SSL issues, etc.), catch the exception and print an error message */
        case e: Exception =>
          println(s"Failed for $domain: ${e.getMessage}")
      }
    }

    /**Close the HTTP client backend after processing all domains */
    backend.close()
  }

  // Calling the functions to load domains and fetch their data
  val domains = loadDomains("data/raw/domains.parquet")
  fetchDomains(domains)
}