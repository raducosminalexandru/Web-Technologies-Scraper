import org.apache.spark.sql.SparkSession
import sttp.client4._
import scala.collection.mutable.ArrayBuffer
import sttp.model.Header
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import java.util.concurrent.ForkJoinPool
import org.apache.hadoop.shaded.org.checkerframework.checker.units.qual.g
import org.jsoup.Jsoup
import scala.jdk.CollectionConverters._
import sttp.client4.BackendOptions.ProxyType

object TechDetector extends App {

  // Load domains from Parquet file
  def loadDomains(parquetPath: String) = {
    val spark = SparkSession
      .builder()
      .appName("TechDetector")
      .master("local[*]")
      .getOrCreate()

    // Set log level to WARN in order to observe better the problems
    spark.sparkContext.setLogLevel("WARN")

    // Read the Parquet file and extract the root domains
    val df = spark.read.parquet(parquetPath)

    // From the schema, select the root_domain column
    val domains = df
      .select("root_domain")
      .collect()
      .map(_.getString(0))
      .toSeq
    spark.stop()
    domains
  }

  // Case class to hold domain data and assure immutability and better organization
  case class DomainData(domain: String, headers: Seq[Header], body: String)

  def fetchDomains(domains: Seq[String]) = {
    // define the backend for sttp client
    val backend = DefaultSyncBackend()

    // Using parallelism (significantly faster than sequential processing)
    val parallelDomains = domains.par
    parallelDomains.tasksupport =
      new ForkJoinTaskSupport(new ForkJoinPool(16)) // max 16 threads

    // Fetch the HTML content and headers using flatmap, handling exceptions gracefully with a try-catch block
    val results = parallelDomains.flatMap { domain =>
      val request = basicRequest
        .get(uri"https://$domain")
        .readTimeout(
          scala.concurrent.duration.Duration(10, "s")
        ) // set a timeout to avoid hanging on unresponsive domains

      try {
        // Send the GET request and capture the response
        val response = request.send(backend)

        response.body match {
          case Right(body) =>
            Some(
              DomainData(domain, response.headers, body)
            ) // If the request is successful, create a DomainData instance
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

  // Use this method if you want to use the proxy configuration for Decodo (slower but it works in environments with strict network policies)
  /*
  def fetchDomains(domains: Seq[String]) = {
    //define the backend for sttp client

    // Proxy configuration for Decodo
    val proxyHost = "gate.decodo.com"
    val proxyPort = 7000
    val proxyUser = "YOUR_DECODO_USERNAME" // replace with your Decodo username
    val proxyPass = "YOUR_DECODO_PASSWORD" // replace with your Decodo password

    val backend = DefaultSyncBackend(
      BackendOptions(
        connectionTimeout = scala.concurrent.duration.Duration(10, "s"),
        proxy = Some(
          // Configure the HTTP proxy with authentication for Decodo
          BackendOptions.Proxy(
            host = proxyHost,
            port = proxyPort,
            proxyType = ProxyType.Http,
            auth = Some(BackendOptions.ProxyAuth(proxyUser, proxyPass))
          )
        )
      )
    )

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
   */

  // Global counter to keep track of the total number of technologies detected across all domains, initialized to 0
  var countTechnologies = 0

  def parseHtml() = {
    // Load domains from the Parquet file and fetch their HTML content and headers
    val domains = loadDomains("data/raw/domains.parquet")
    val data = fetchDomains(domains)

    // traverse the fetched data and detect technologies based on the presence of specific keywords in the HTML content and headers
    val techCounts = data.map { d =>
      val technologies =
        scala.collection.mutable
          .ArrayBuffer[String]() // storing detected technologies in a mutable array buffer to allow for dynamic addition

      val doc = Jsoup.parse(d.body)

      // Map of header technologies: key = technology, value = (headerName, keyword)
      val headerTechnologies: Map[String, (String, String)] = Map(
        "Cloudflare" -> ("server", "cloudflare"),
        "Apache" -> ("server", "apache"),
        "Nginx" -> ("server", "nginx"),
        "LiteSpeed" -> ("server", "litespeed"),
        "PHP" -> ("x-powered-by", "php")
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

      // Extract all div IDs from the HTML document and convert them to lowercase for case-insensitive matching
      val divIds =
        doc.select("div[id]").eachAttr("id").asScala.map(_.toLowerCase)

      val divTechnologies: Map[String, String] = Map(
        "YUI3" -> "yui3-css-stamp"
      )

      // 1. Map of technologies to look for in 'href' and 'action' attributes
      val linkTechnologies: Map[String, String] = Map(
        "PHP" -> ".php",
        "ASP.NET" -> ".aspx",
        "Classic ASP" -> ".asp",
        "ColdFusion" -> ".cfm",
        "JSP" -> ".jsp",
        "Perl/CGI" -> ".cgi"
      )

      // 2. Extract values using getElementsByTag (more robust than CSS selectors for legacy HTML)
      // We manually extract 'href' from <a> and 'action' from <form>
      val urlAttributes = (
        doc.getElementsByTag("a").asScala.map(_.attr("href")) ++
          doc.getElementsByTag("form").asScala.map(_.attr("action"))
      ).filter(_.nonEmpty).map(_.toLowerCase)

      // 3. Iterate through the map and check for matches (added a check to ensure the link is internal to avoid false positives from external links)
      for ((tech, keyword) <- linkTechnologies) {
        val isInternalMatch = urlAttributes.exists { url =>
          val isKeywordPresent = url.contains(keyword.toLowerCase)

          // Check if it's a relative link (starts with /)
          // OR if it contains the current domain name
          val isInternal = url.startsWith("/") ||
            url.startsWith("./") ||
            url.contains(d.domain.toLowerCase)

          isKeywordPresent && isInternal
        }

        if (isInternalMatch) {
          technologies += tech
        }
      }

      // Loop through divTechnologies and check if the divIds contain the keyword
      for ((tech, keyword) <- divTechnologies) {
        if (divIds.exists(_.contains(keyword))) {
          technologies += tech
        }
      }

      // Unified technology keywords
      val techKeywords = Map(
        "WordPress" -> "wordpress",
        "Yoast SEO" -> "yoast", // Yoast is a popular WordPress plugin, so its presence can also indicate WordPress usage
        "Google Analytics" -> "google-analytics",
        "Google Tag Manager" -> "googletagmanager",
        "jQuery" -> "jquery",
        "Bootstrap" -> "bootstrap",
        "React" -> "react",
        "Vue" -> "vue",
        "UIkit" -> "uikit",
        "Squarespace" -> "squarespace",
        "UserWay" -> "userway",
        "Json-LD" -> "application/ld+json",
        "Swiper" -> "swiper-bundle",
        "Akamai Bot Manager" -> "akam",
        "Bootstrap" -> "bootstrap.min.js", // tipically bootstrap is included as a script with "bootstrap.min.js" in the src attribute
        "Canva" -> "__canva_website_bootstrap__", // Canva's script tag often includes this unique identifier in the src attribute (every identifier starts with "DA")
        "Shopify" -> "cdn.shopify.com", // Shopify's script tags often include "cdn.shopify.com" in the src attribute
      )

      // Checking the script tags thoroughly to include attributes (like src) and inline content
      val allScripts = doc.select("script").asScala

      // Check script attributes + data + outerHtml to ensure "akam" is caught even in complex tag structures
      for ((tech, keyword) <- techKeywords) {
        val isPresent = allScripts.exists { s =>
          s.data().toLowerCase.contains(keyword) ||
          s.outerHtml().toLowerCase.contains(keyword)
        }

        if (isPresent) {
          technologies += tech
        }
      }

      // Check the meta generator tag for common CMS indicators (e.g., WordPress, GoDaddy Website Builder)
      val generator =
        doc.select("meta[name=generator]").attr("content").toLowerCase

      if (generator.contains("wordpress")) {
        technologies += "WordPress"
      }

      if (
        generator.contains("go daddy website builder") || generator.contains(
          "starfield technologies"
        )
      ) {
        technologies += "GoDaddy Website Builder"
      }

      // Taking the distinct technologies to avoid counting duplicates
      val distinct = technologies.distinct

      // Output the results in JSON format, including the domain and the list of detected technologies, using string interpolation to construct the JSON string
      val json =
        s"""{"domain":"${d.domain}","technologies":[${distinct
            .map(t => s""""$t"""")
            .mkString(",")}]}"""

      println(json)

      // Return the size for this domain to be collected by the map
      distinct.size
    }

    // update the global counter with the number of distinct technologies detected for the current domain
    // We use .sum here as our reduction operation to safely add all parallel results together
    countTechnologies = techCounts.sum
  }

  // Call the function to start the process of loading domains, fetching their HTML content, detecting technologies, and outputting the results in JSON format
  parseHtml()
  // Print the total number of technologies detected across all domains.
  println(s"Total technologies detected: $countTechnologies")
}
