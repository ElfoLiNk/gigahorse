package gigahorse

import scala.collection.JavaConverters._
import java.io.UnsupportedEncodingException
import java.nio.charset.{ Charset, StandardCharsets }
import scala.concurrent.{ Future, Promise }
import com.ning.http.client.{ Response => XResponse, Request => XRequest, ProxyServer => XProxyServer, Realm => XRealm, _ }
import com.ning.http.util.AsyncHttpProviderUtils
import com.ning.http.client.Realm.{ RealmBuilder, AuthScheme => XAuthScheme }
import org.jboss.netty.handler.codec.http.{ HttpHeaders, QueryStringDecoder }

class AhcHttpClient(config: AsyncHttpClientConfig) extends HttpClient {
  private val asyncHttpClient = new AsyncHttpClient(config)
  def underlying[A]: A = asyncHttpClient.asInstanceOf[A]
  def close(): Unit = asyncHttpClient.close()
  override def toString: String =
    s"""AchHttpClient($config)"""

  def this(config: Config) =
    this(AhcConfig.buildConfig(config))

  def execute(request: Request): Future[Response] =
    {
      import com.ning.http.client.AsyncCompletionHandler
      val result = Promise[AhcResponse]()
      val xrequest = buildRequest(request)
      asyncHttpClient.executeRequest(xrequest, new AsyncCompletionHandler[XResponse]() {
        override def onCompleted(response: XResponse) = {
          result.success(new AhcResponse(response))
          response
        }
        override def onThrowable(t: Throwable) = {
          result.failure(t)
        }
      })
      result.future
    }

  /**
   * Creates and returns an AHC request, running all operations on it.
   */
  def buildRequest(request: Request): XRequest = {
    import request._
    // The builder has a bunch of mutable state and is VERY fiddly, so
    // should not be exposed to the outside world.

    val disableUrlEncoding: Option[Boolean] = None
    val builder = disableUrlEncoding.map { disableEncodingFlag =>
      new RequestBuilder(method, disableEncodingFlag)
    }.getOrElse {
      new RequestBuilder(method)
    }

    // Set the URL.
    builder.setUrl(url)

    // auth
    authOpt.foreach { data =>
      val realm = buildRealm(data)
      builder.setRealm(realm)
    }

    // queries
    for {
      (key, values) <- queryString
      value <- values
    } builder.addQueryParam(key, value)

    // Configuration settings on the builder, if applicable
    virtualHostOpt.foreach(builder.setVirtualHost)
    followRedirectsOpt.foreach(builder.setFollowRedirects)

    proxyServerOpt.foreach(p => builder.setProxyServer(buildProxy(p)))

    requestTimeoutOpt foreach { x =>
      builder.setRequestTimeout(AhcConfig.toMillis(x))
    }

    val (builderWithBody, updatedHeaders) = body match {
      case b: EmptyBody => (builder, request.headers)
      case b: FileBody =>
        import com.ning.http.client.generators.FileBodyGenerator
        val bodyGenerator = new FileBodyGenerator(b.file)
        builder.setBody(bodyGenerator)
        (builder, request.headers)
      case b: InMemoryBody =>
        val ct: String = contentType(request).getOrElse("text/plain")

        val h = try {
          // Only parse out the form body if we are doing the signature calculation.
          if (ct.contains(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED) && signatureOpt.isDefined) {
            // If we are taking responsibility for setting the request body, we should block any
            // externally defined Content-Length field (see #5221 for the details)
            val filteredHeaders = request.headers.filterNot { case (k, v) => k.equalsIgnoreCase(HttpHeaders.Names.CONTENT_LENGTH) }

            // extract the content type and the charset
            val charset = Charset.forName(
              Option(AsyncHttpProviderUtils.parseCharset(ct)).getOrElse {
                // NingWSRequest modifies headers to include the charset, but this fails tests in Scala.
                //val contentTypeList = Seq(ct + "; charset=utf-8")
                //possiblyModifiedHeaders = this.headers.updated(HttpHeaders.Names.CONTENT_TYPE, contentTypeList)
                "utf-8"
              }
            )

            // Get the string body given the given charset...
            val stringBody = new String(b.bytes, charset)
            // The Ning signature calculator uses request.getFormParams() for calculation,
            // so we have to parse it out and add it rather than using setBody.

            val params = for {
              (key, values) <- new QueryStringDecoder("/?" + stringBody, charset).getParameters.asScala.toList // FormUrlEncodedParser.parse(stringBody).toSeq
              value <- values.asScala.toList
            } yield new Param(key, value)
            builder.setFormParams(params.asJava)
            filteredHeaders
          } else {
            builder.setBody(b.bytes)
            request.headers
          }
        } catch {
          case e: UnsupportedEncodingException =>
            throw new RuntimeException(e)
        }

        (builder, h)
      // case StreamedBody(bytes) =>
      //  (builder, request.headers)
    }

    // headers
    for {
      header <- updatedHeaders
      value <- header._2
    } builder.addHeader(header._1, value)

    // Set the signature calculator.
    signatureOpt.map {
      case signatureCalculator: com.ning.http.client.SignatureCalculator =>
        builderWithBody.setSignatureCalculator(signatureCalculator)
      case _ =>
        throw new IllegalStateException("Unknown signature calculator found: use a class that implements SignatureCalculator")
    }
    builderWithBody.build()
  }

  def contentType(request: Request): Option[String] = {
    request.headers.find(p => p._1 == HttpHeaders.Names.CONTENT_TYPE).map {
      case (header, values) =>
        values.head
    }
  }

  def buildRealm(auth: Realm): XRealm =
    {
      import com.ning.http.client.uri.Uri
      val builder = new RealmBuilder
      builder.setScheme(auth.scheme match {
        case AuthScheme.Digest   => XAuthScheme.DIGEST
        case AuthScheme.Basic    => XAuthScheme.BASIC
        case AuthScheme.NTLM     => XAuthScheme.NTLM
        case AuthScheme.SPNEGO   => XAuthScheme.SPNEGO
        case AuthScheme.Kerberos => XAuthScheme.KERBEROS
        case AuthScheme.None     => XAuthScheme.NONE
        case _ => throw new RuntimeException("Unknown scheme " + auth.scheme)
      })
      builder.setPrincipal(auth.username)
      builder.setPassword(auth.password)
      builder.setUsePreemptiveAuth(auth.usePreemptiveAuth)
      auth.realmNameOpt foreach { builder.setRealmName }
      auth.nonceOpt foreach { builder.setNonce }
      auth.algorithmOpt foreach { builder.setAlgorithm }
      auth.responseOpt foreach { builder.setResponse }
      auth.opaqueOpt foreach { builder.setOpaque }
      auth.qopOpt foreach { builder.setQop }
      auth.ncOpt foreach { builder.setNc }
      auth.uriOpt foreach { x => builder.setUri(Uri.create(x.toString)) }
      auth.methodNameOpt foreach { builder.setMethodName }
      auth.charsetOpt foreach { x => builder.setCharset(x) }
      auth.ntlmDomainOpt foreach { builder.setNtlmDomain }
      auth.ntlmHostOpt foreach { builder.setNtlmHost }
      builder.setUseAbsoluteURI(auth.useAbsoluteURI)
      builder.setOmitQuery(auth.omitQuery)
      builder.build()
    }

  def buildProxy(proxy: ProxyServer): XProxyServer =
    {
      import com.ning.http.client.ProxyServer.Protocol
      val protocol = (for {
        auth <- proxy.authOpt
        uri <- auth.uriOpt
        s <- Option(uri.getScheme)
      } yield s).getOrElse("http").toLowerCase match {
        case "http"     => Protocol.HTTP
        case "https"    => Protocol.HTTPS
        case "kerberos" => Protocol.KERBEROS
        case "ntlm"     => Protocol.NTLM
        case "spnego"   => Protocol.SPNEGO
        case s          => throw new RuntimeException("Unrecognized protocol " + s)
      }
      val p = new XProxyServer(protocol, proxy.host, proxy.port,
        proxy.authOpt.map(_.username).orNull,
        proxy.authOpt.map(_.password).orNull)
      proxy.nonProxyHosts foreach { h =>
        p.addNonProxyHost(h)
      }
      proxy.authOpt foreach { auth =>
        auth.ntlmDomainOpt foreach {p.setNtlmDomain}
        auth.ntlmHostOpt foreach { p.setNtlmHost }
        auth.charsetOpt foreach { p.setCharset }
      }
      p
    }
}
