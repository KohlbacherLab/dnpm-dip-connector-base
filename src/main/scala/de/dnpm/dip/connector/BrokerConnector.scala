package de.dnpm.dip.connector


import java.io.{
  FileInputStream,
  InputStream
}
import java.net.URI
import scala.util.{
  Try,
  Success,
  Failure,
  Using
}
import scala.xml.XML
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.Materializer
import play.api.libs.ws.StandaloneWSRequest
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.json.{
  Json,
  JsValue,
  Reads
}
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site


private object BrokerConnector extends Logging
{

  final case class SiteEntry
  (
    id: String,
    name: String,
    virtualhost: String
  )

  final case class SiteConfig
  (
    sites: Set[SiteEntry]
  )

  object SiteConfig
  {
    implicit val formatEntry: Reads[SiteEntry] =
      Json.reads[SiteEntry]

    implicit val format: Reads[SiteConfig] =
      Json.reads[SiteConfig]
  }

  case class LocalConfig
  (
    private val url: String,
    timeout: Option[Int],
    updatePeriod: Option[Long]
  )
  extends HttpConnector.Config
  {
    def baseURL =
      URI.create(
        if (url endsWith "/")
          url.substring(0,url.length-1)
        else
          url
      )
      .toURL
  }

  private object LocalConfig extends Logging
  {

    /*
     * Expected XML Config structure:
     * 
     *  <?xml version="1.0" encoding="UTF-8"?>
     *  <Config>
     *    ...
     *    <Connector>
     *    
     *      <!-- Base URL to DNPM-Proxy -->
     *      <Broker baseURL="http://localhost"/>
     *      
     *      <!-- OPTIONAL request timeout (in seconds) -->
     *      <Timeout seconds="10"/>
     *      
     *      <!-- OPTIONAL, for periodic auto-update of site list from broker: Period (in seconds) -->
     *      <UpdatePeriod minutes="30"/>
     *    
     *    </Connector>
     *    ...
     *  </Config>
     */

    private def parseXMLConfig(in: InputStream): LocalConfig = {
    
      val xml =
        (XML.load(in) \\ "Connector")
    
      LocalConfig(
        (xml \ "Broker" \@ "baseURL"),
        Try(xml \ "Timeout" \@ "seconds").map(_.toInt).toOption,
        Try(xml \ "UpdatePeriod" \@ "minutes").map(_.toLong).toOption
      )
    }

    
    lazy val instance: LocalConfig = {

      val sysProp = "dnpm.dip.config.file"

      // Try reading config from classpath by default
      Try {
        val file = "config.xml"
    
        log.debug(s"Loading connector config file '$file' from classpath...")
    
        Option(getClass.getClassLoader.getResourceAsStream(file)).get
      }
      // else use system property for configFile path
      .recoverWith {
        case _ =>
          log.debug(s"Couldn't get config file from classpath, trying file configured via system property '$sysProp'")
    
          Try { Option(System.getProperty(sysProp)).get }
            .map(new FileInputStream(_))
      }
      .flatMap(Using(_)(parseXMLConfig))
      // else use system properties for siteId and baseUrl to instantiate Config
      .recoverWith {
        case _ => 
          log.warn(s"Couldn't get config file, most likely due to undefined property '$sysProp'. Attempting configuration via system properties...")
          Try {
            for {
              baseUrl   <- Option(System.getProperty("dnpm.dip.connector.config.baseUrl"))
              timeout   =  Option(System.getProperty("dnpm.dip.connector.config.timeout.seconds")).map(_.toInt)
              period    =  Option(System.getProperty("dnpm.dip.connector.config.update.period")).map(_.toLong)
            } yield LocalConfig(
              baseUrl,
              timeout,
              period
            )
          }
          .map(_.get)
      }
      .get
    }
    
  }  // end LocalConfig


  private implicit lazy val system: ActorSystem =
    ActorSystem()

  private implicit lazy val materializer: Materializer =
    Materializer.matFromSystem

  private lazy val wsclient =
    StandaloneAhcWSClient()

  private val timeout =
    LocalConfig.instance.timeout.getOrElse(10) seconds

  private def request(
    rawUri: String
  ): StandaloneWSRequest = {

    val uri =
      if (rawUri startsWith "/") rawUri.substring(1)
      else rawUri

    wsclient.url(s"${LocalConfig.instance.baseURL}/$uri")
      .withRequestTimeout(timeout)
        
  }


  // Set-up for periodic auto-update of config
  import java.util.concurrent.Executors
  import java.util.concurrent.TimeUnit.SECONDS
  import java.util.concurrent.atomic.AtomicReference

  private lazy val executor =
    Executors.newSingleThreadScheduledExecutor

  private var failedTries = 0
  private val maxTries    = 5
  private val retryPeriod = 30L

  private def getSiteConfig(): Unit = {

    import ExecutionContext.Implicits.global

    log.info(s"Requesting peer connectivity config from broker")

    request("/sites")
      .get()
      .map(_.body[JsValue].as[BrokerConnector.SiteConfig])
      .onComplete {
        case Success(config) =>
          failedTries = 0
          sitesConfig.set(
            config.sites.map {
              case BrokerConnector.SiteEntry(id,name,vhost) => Coding[Site](id,name) -> vhost
            }
            .toMap
          )

        case Failure(t) =>
          log.error(s"Broker connection error: ${t.getMessage}")
          failedTries += 1
          if (failedTries < maxTries){
            log.warn(s"Retrying broker connection in $retryPeriod seconds")
            executor.schedule(
              new Runnable { override def run = getSiteConfig() },
              retryPeriod,
              SECONDS
            )
          } else
            log.error(s"Permanent broker connection failure after $failedTries tries, ensure the overall networking configuration is correct")
          
      }

  }

  private val sitesConfig: AtomicReference[Map[Coding[Site],String]] =
    new AtomicReference(Map.empty)


  LocalConfig.instance.updatePeriod match {
    case Some(period) =>
      executor.scheduleAtFixedRate(
        () => getSiteConfig(),
        0,
        period*60,
        SECONDS
      )
    case None =>
      getSiteConfig()
  }


  def apply(
    requestMapper: HttpConnector.RequestMapper,
  ): BrokerConnector =
    new BrokerConnector(
      requestMapper
    )

}


private class BrokerConnector
(
  private val requestMapper: HttpConnector.RequestMapper
)
extends HttpConnector(requestMapper){

  override def otherSites: Set[Coding[Site]] =
    BrokerConnector.sitesConfig.get match {
      case map if (map.nonEmpty) =>
        map.collect {
          case (site,_) if (site.code != Site.local.code) => site
        }
        .toSet

      case _ =>
        log.warn("Global site config from broker not available, falling back to empty external site list")
        Set.empty[Coding[Site]]
    }

  
  override def request(
    site: Coding[Site],
    uri: String
  ): StandaloneWSRequest = 
    BrokerConnector
      .request(uri)
      .withVirtualHost(
        BrokerConnector.sitesConfig.get()(site)
      )

}
