import org.apache.spark.sql.SparkSession
import sttp.client4._
import scala.collection.mutable.ArrayBuffer
import sttp.model.Header
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import java.util.concurrent.ForkJoinPool
import org.apache.hadoop.shaded.org.checkerframework.checker.units.qual.g

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
    parallelDomains.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(16)) // max 16 threads

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

      // List of technologies to detect in HTML body
      val htmlTechnologies = List(
        ("WordPress", "wordpress"),
        ("Yoast SEO", "yoast"),
        ("Google Analytics", "google-analytics"),
        ("Google Tag Manager", "googletagmanager")
      )

      for ((tech, keyword) <- htmlTechnologies) {
        if (htmlLower.contains(keyword)) {
          technologies += tech
        }
      }

 // Map of header technologies: key = technology, value = (headerName, keyword)
      val headerTechnologies: Map[String, (String, String)] = Map(
        "Cloudflare" -> ("server", "cloudflare"),
        "Apache"     -> ("server", "apache"),
        "Nginx"      -> ("server", "nginx"),
        "LiteSpeed"  -> ("server", "litespeed"),
        "PHP"        -> ("x-powered-by", "php")
      )

      // Convert headers to a lowercase map for case-insensitive access
      val headersMap: Map[String, String] =
        d.headers.map(h => h.name.toLowerCase -> h.value.toLowerCase).toMap

      // Loop through each technology and check the corresponding header
      for ((tech, (headerName, keyword)) <- headerTechnologies) {
        if (headersMap.getOrElse(headerName, "").contains(keyword)) {
          technologies += tech
        }
      }
      
      // Checking the script tag so we don't get false positives from the body content (e.g., a blog post mentioning "React" without actually using the React library)
      val scriptSrcs = "<script[^>]*src=[\"']([^\"']+)[\"']".r.findAllMatchIn(d.body).map(_.group(1).toLowerCase).toList

      // List of technologies and the keywords to detect in script srcs
      val techKeywords = Map(
        "jQuery"      -> "jquery",
        "Bootstrap"   -> "bootstrap",
        "React"       -> "react",
        "Vue"         -> "vue",
        "UIkit"       -> "uikit",
        "Squarespace" -> "squarespace"
      )

      // Check each script src for all keywords
      for ((tech, keyword) <- techKeywords) {
        if (scriptSrcs.exists(_.contains(keyword.toLowerCase))) {
          technologies += tech
        }
      }

      // Taking the distinct technologies to avoid counting duplicates
      val distinct = technologies.distinct
      countTechnologies += distinct.size //update the global counter with the number of distinct technologies detected for the current domain

      // Output the results in JSON format, including the domain and the list of detected technologies, using string interpolation to construct the JSON string
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