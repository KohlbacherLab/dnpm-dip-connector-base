package de.dnpm.dip.connector


import scala.concurrent.Future
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.must.Matchers._
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.query.{
  Querier,
  PeerToPeerRequest
}
import play.api.libs.json.{
  Json,
  JsObject,
  Writes
}


final case class TestRequest(
  origin: Coding[Site],
  querier: Querier
)
extends PeerToPeerRequest
{
  type ResultType = JsObject
}

object TestRequest
{
  implicit val writes: Writes[TestRequest] =
    Json.writes[TestRequest]
}



class Tests extends AsyncFlatSpec
{

  import BrokerConnectorF.Implicits._


  private val connector =
    BrokerConnector(
      "/api/peer-to-peer/dummy-use-case",
      {
        case _: TestRequest => HttpMethod.POST -> "test" 
      }
    )

  private val connectorF =
    BrokerConnectorF[Future](
      "/api/peer-to-peer/dummy-use-case",
      {
        case _: TestRequest => HttpMethod.POST -> "test" 
      }
    )


  "OtherSites list" must "be empty" in {

    connectorF.otherSites must be (empty)

  }


  "TestQuery" must "have returned empty Map" in {

    val result = 
      connectorF ! TestRequest(
        connector.localSite,
        Querier("Dummy-ID")
      )

    result.map(_ must be (empty))

  }

}