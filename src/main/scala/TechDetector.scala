import org.apache.spark.sql.SparkSession
import sttp.client4._
import scala.collection.mutable.ArrayBuffer
import sttp.model.Header
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import java.util.concurrent.ForkJoinPool

object TechDetector extends App {

  //Load domains from Parquet file
  def loadDomains(parquetPath: String) = {
    val spark = SparkSession.builder()
      .appName("TechDetector")
      .master("local[*]")
      .getOrCreate()

    //Set log level to WARN in order to observe better the problems
    spark.sparkContext.setLogLevel("WARN")

    //Read the Parquet file and extract the root domains
    val df = spark.read.parquet(parquetPath)

    //From the schema, select the root_domain column
    val domains = df.select("root_domain")
      .collect()
      .map(_.getString(0))
      .toSeq

    spark.stop()
    domains
  }

  // Case class to hold domain data and assure immutability and better organization
  case class DomainData(domain: String, headers: Seq[Header], body: String)

  def fetchDomains(domains: Seq[String]) = {
    //define the backend for sttp client
    val backend = DefaultSyncBackend()

    // Using parallelism (significantly faster than sequential processing)
    val parallelDomains = domains.par
    parallelDomains.tasksupport =
      new ForkJoinTaskSupport(new ForkJoinPool(16)) // max 16 threads

    //Fetch the HTML content and headers using flatmap, handling exceptions gracefully with a try-catch block
    val results = parallelDomains.flatMap { domain =>
      val request = basicRequest
        .get(uri"https://$domain")
        .readTimeout(scala.concurrent.duration.Duration(10, "s")) // set a timeout to avoid hanging on unresponsive domains

      try {
        //Send the GET request and capture the response
        val response = request.send(backend)

        response.body match {
          case Right(body) =>
            Some(DomainData(domain, response.headers, body)) // If the request is successful, create a DomainData instance
          case Left(_) =>
            None // If the request fails (e.g., 404, timeout), return None to skip this domain (flatMap ignores None values)
        }

      } catch {
        case _: Exception =>
          None // Handle any exceptions (e.g., network errors) by returning None and skip this domain
      }
    }

    // Close the backend after all the requests are made to free up resources
    backend.close()
    results
  }

  // Global counter to keep track of the total number of technologies detected across all domains, initialized to 0
  var countTechnologies = 0

  def parseHtml() = {
    // Load domains from the Parquet file and fetch their HTML content and headers
    val domains = loadDomains("data/raw/domains.parquet")
    val data    = fetchDomains(domains)

    //traverse the fetched data and detect technologies based on the presence of specific keywords in the HTML content and headers
    for (d <- data) {
      // convert the HTML content to lowercase for case-insensitive matching and easier keyword detection
      val htmlLower = d.body.toLowerCase

      val technologies =
        scala.collection.mutable.ArrayBuffer[String]() //storing detected technologies in a mutable array buffer to allow for dynamic addition

      if (htmlLower.contains("wordpress")) technologies += "WordPress"
      if (htmlLower.contains("yoast")) technologies += "Yoast SEO"

      // Create a map of headers for easier access and case-insensitive matching
      val headersMap: Map[String, String] =
        d.headers.map(h => h.name.toLowerCase -> h.value.toLowerCase).toMap

      if (headersMap.getOrElse("server", "").contains("cloudflare"))
        technologies += "Cloudflare"

      //Taking the distinct technologies to avoid counting duplicates
      val distinct = technologies.distinct
      countTechnologies += distinct.size //update the global counter with the number of distinct technologies detected for the current domain

      //Output the results in JSON format, including the domain and the list of detected technologies, using string interpolation to construct the JSON string
      val json =
        s"""{"domain":"${d.domain}","technologies":[${distinct.map(t => s""""$t"""").mkString(",")}]}"""

      println(json)
    }
  }

  // Call the function to start the process of loading domains, fetching their HTML content, detecting technologies, and outputting the results in JSON format
  parseHtml()

  // Print the total number of technologies detected across all domains.
  println(s"Total technologies detected: $countTechnologies")
}