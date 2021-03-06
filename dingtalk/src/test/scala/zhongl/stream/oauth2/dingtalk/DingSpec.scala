package zhongl.stream.oauth2.dingtalk
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives
import akka.stream.Materializer
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zhongl.stream.oauth2.FreshToken.InvalidToken

import scala.concurrent._
import scala.concurrent.duration._

class DingSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Directives with JsonSupport {
  import DingSpec._

  implicit private val tmpAuthCodeF = jsonFormat1(TmpAuthCode)

  implicit private val system = ActorSystem(getClass.getSimpleName)
  implicit private val mat    = Materializer(system)
  implicit private val ec     = system.dispatcher

  private val default: (UserInfo, Uri) => HttpResponse = (_, _) => HttpResponse()
  private val token                                    = AccessToken("token", 60)
  private val config                                   = system.settings.config.getConfig("dingtalk")

  private var bound: ServerBinding = _

  "Ding" should {

    "create location header with uri state" in {
      val uri =
        "https://oapi.dingtalk.com/connect/qrconnect?appid=your_appid&response_type=code&scope=snsapi_login&redirect_uri=%2Fauthorized&state=aHR0cDovL3Rlc3Q="
      Ding(default).authorization("http://test") shouldBe Location(uri)
    }

    "refresh token" in {
      Await.result(Ding(default).refresh, 1.second).`access_token` shouldBe "token"
    }

    "authenticated" in {
      val f = Ding({
        case (UserInfo("i", "name", _, Seq(1), "avatar.ico", true, Seq(Role(_, _, _))), _) => HttpResponse()
        case _                                                                             => HttpResponse(StatusCodes.BadRequest)
      }).authenticate(token, HttpRequest(uri = "/authorized?code=c&state=aHR0cDovL3Rlc3Q="))
      Await.result(f, 1.second) shouldBe HttpResponse()
    }

    "complain missing code or state" in {
      val f = Ding(default).authenticate(token, HttpRequest())
      Await.result(f, 1.second).status shouldBe StatusCodes.BadRequest
    }

    "complain invalid token by 40014" in {
      val f = Ding(default).authenticate(token, HttpRequest(uri = "/authorized?code=40014&state=aHR0cDovL3Rlc3Q="))
      intercept[IllegalArgumentException](Await.result(f, 1.second)) shouldBe InvalidToken
    }

    "complain invalid token by 42001" in {
      val f = Ding(default).authenticate(token, HttpRequest(uri = "/authorized?code=42001&state=aHR0cDovL3Rlc3Q="))
      intercept[IllegalArgumentException](Await.result(f, 1.second)) shouldBe InvalidToken
    }

    "complain invalid response by non-json" in {
      val f = Ding(default).authenticate(token, HttpRequest(uri = "/authorized?code=text&state=aHR0cDovL3Rlc3Q="))
      intercept[IllegalStateException](Await.result(f, 1.second)).getMessage shouldBe "200 OK - text"
    }

  }

  override protected def beforeAll(): Unit = {
    val f = Http().newServerAt("localhost", 10086).bindFlow(mockDingServer)
    bound = Await.result(f, 1.second)
  }

  override protected def afterAll(): Unit = {
    Await.result(bound.unbind().flatMap(_ => system.terminate()), 3.second)
  }

  private def json(content: String) = HttpEntity(ContentTypes.`application/json`, content)

  private def mockDingServer = concat(
    (post & path("sns" / "getuserinfo_bycode") & parameters("signature", "timestamp", "accessKey") & entity(as[TmpAuthCode])) {
      case (signature, timestamp, _, _) if Ding.sign("HmacSHA256", "UTF-8", timestamp, config.getString("mobile.secret")) != signature =>
        complete(Err(-1))
      case (_, _, accessKey, _) if accessKey != config.getString("mobile.appid")                                                       =>
        complete(Err(-1))
      case (_, _, _, TmpAuthCode("42001"))                                                                                             =>
        complete(json("""{ "errcode": 0, "errmsg": "ok", "user_info": { "nick": "", "openid": "", "unionid": "42001" } }"""))
      case (_, _, _, TmpAuthCode("40014"))                                                                                             =>
        complete(json("""{ "errcode": 0, "errmsg": "ok", "user_info": { "nick": "", "openid": "", "unionid": "40014" } }"""))
      case (_, _, _, TmpAuthCode("text"))                                                                                              =>
        complete("text")
      case (_, _, _, _)                                                                                                                =>
        complete(json("""{ "errcode": 0, "errmsg": "ok", "user_info": { "nick": "", "openid": "", "unionid": "u" } }"""))

    },
    (get & path("user" / "getUseridByUnionid") & parameters("access_token", "unionid")) {
      case ("token", "u") =>
        complete(json(""" { "errcode": 0, "errmsg": "ok", "contactType": 0, "userid": "i" } """))
      case (_, "40014")   =>
        complete(Err(40014))
      case (_, "42001")   =>
        complete(json(""" { "errcode": 0, "errmsg": "ok", "contactType": 0, "userid": "42001" } """))
      case (_, _)         =>
        complete(Err(-1))
    },
    (get & path("user" / "get") & parameters("access_token", "userid")) {
      case ("token", "i")     =>
        complete(
          json(
            """{"orderInDepts":"","department":[1],"unionid":"u","userid":"i","isSenior":false,"isBoss":false,"name":"name","errmsg":"ok","stateCode":"86","avatar":"avatar.ico","errcode":0,"isLeaderInDepts":"{1:false}","email":"","roles":[{"id":1,"name":"admin","groupName":"","type":101}],"active":true,"isAdmin":true,"openId":"t","mobile":"","isHide":false}"""
          )
        )
      case ("token", "42001") =>
        complete(Err(42001))
      case (_, _)             =>
        complete(Err(-1))
    },
    (get & path("gettoken") & parameters("appkey", "appsecret")) {
      case (key, secret) if key == config.getString("micro.appkey") && secret == config.getString("micro.secret") =>
        complete(token)
      case (_, _)                                                                                                 =>
        complete(Err(-1))
    }
  )

}

object DingSpec {
  final case class TmpAuthCode(`tmp_auth_code`: String)
}
