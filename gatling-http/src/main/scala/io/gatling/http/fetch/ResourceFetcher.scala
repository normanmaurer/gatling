/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.fetch

import java.net.URI

import scala.collection.JavaConversions._
import scala.collection.breakOut
import scala.collection.concurrent
import scala.collection.mutable

import com.typesafe.scalalogging.slf4j.StrictLogging
import io.gatling.core.akka.BaseActor
import io.gatling.core.filter.Filters
import io.gatling.core.result.message.{ OK, Status }
import io.gatling.core.session.Session
import io.gatling.core.util.StringHelper._
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.HeaderNames
import io.gatling.http.action.HttpRequestAction
import io.gatling.http.ahc.HttpTx
import io.gatling.http.cache.CacheHandling
import io.gatling.http.config.HttpProtocol
import io.gatling.http.response.{ Response, ResponseBuilder }
import jsr166e.ConcurrentHashMapV8

sealed trait ResourceFetched {
  def uri: URI
  def status: Status
  def sessionUpdates: Session => Session
}
case class RegularResourceFetched(uri: URI, status: Status, sessionUpdates: Session => Session) extends ResourceFetched
case class CssResourceFetched(uri: URI, status: Status, sessionUpdates: Session => Session, statusCode: Option[Int], lastModifiedOrEtag: Option[String], content: String) extends ResourceFetched

case class InferredPageResources(expire: String, requests: List[NamedRequest])

object ResourceFetcher extends StrictLogging {

  val CssContentCache: concurrent.Map[URI, List[EmbeddedResource]] = new ConcurrentHashMapV8[URI, List[EmbeddedResource]]
  val InferredResourcesCache: concurrent.Map[(HttpProtocol, URI), InferredPageResources] = new ConcurrentHashMapV8[(HttpProtocol, URI), InferredPageResources]

  def pageResources(htmlDocumentURI: URI, filters: Option[Filters], responseChars: Array[Char]): List[EmbeddedResource] = {
    val htmlInferredResources = HtmlParser.getEmbeddedResources(htmlDocumentURI, responseChars)
    filters match {
      case Some(f) => f.filter(htmlInferredResources)
      case none    => htmlInferredResources
    }
  }

  def cssResources(cssURI: URI, filters: Option[Filters], content: String): List[EmbeddedResource] = {
    val cssInferredResources = CssContentCache.getOrElseUpdate(cssURI, CssParser.extractResources(cssURI, content))
    filters match {
      case Some(f) => f.filter(cssInferredResources)
      case none    => cssInferredResources
    }
  }

  def lastModifiedOrEtag(response: Response, protocol: HttpProtocol): Option[String] =
    if (protocol.requestPart.cache)
      response.header(HeaderNames.LastModified).orElse(response.header(HeaderNames.ETag))
    else
      None

  def resourcesFromPage(response: Response, tx: HttpTx): List[NamedRequest] = {

    val htmlDocumentURI = response.request.getURI
    val protocol = tx.protocol

      def inferredResourcesRequests(): List[NamedRequest] =
        pageResources(htmlDocumentURI, protocol.responsePart.htmlResourcesInferringFilters, response.body.string.unsafeChars)
          .flatMap(_.toRequest(protocol, tx.throttled))

    val inferredResources: List[NamedRequest] = response.statusCode match {
      case Some(200) =>
        lastModifiedOrEtag(response, protocol) match {
          case Some(newLastModifiedOrEtag) =>
            val cacheKey = (protocol, htmlDocumentURI)
            InferredResourcesCache.get(cacheKey) match {
              case Some(InferredPageResources(`newLastModifiedOrEtag`, res)) =>
                //cache entry didn't expire, use it
                res
              case _ =>
                // cache entry missing or expired, update it
                val inferredResources = inferredResourcesRequests()
                // FIXME add throttle to cache key?
                InferredResourcesCache.put((protocol, htmlDocumentURI), InferredPageResources(newLastModifiedOrEtag, inferredResources))
                inferredResources
            }

          case None =>
            // don't cache
            inferredResourcesRequests()
        }

      case Some(304) =>
        // no content, retrieve from cache if exist
        val cacheKey = (protocol, htmlDocumentURI)
        InferredResourcesCache.get(cacheKey) match {
          case Some(inferredPageResources) => inferredPageResources.requests
          case _ =>
            logger.warn(s"Got a 304 for $htmlDocumentURI but could find cache entry?!")
            Nil
        }

      case _ => Nil
    }

    inferredResources
  }

  def fetchResources(tx: HttpTx, explicitResources: List[NamedRequest]): Option[() => ResourceFetcher] =
    resourceFetcher(tx, Nil, explicitResources)

  def fromCache(htmlDocumentURI: URI, tx: HttpTx, explicitResources: List[NamedRequest]): Option[() => ResourceFetcher] = {
    val cacheKey = (tx.protocol, htmlDocumentURI)
    val inferredResources = InferredResourcesCache.get(cacheKey).map(_.requests).getOrElse(Nil)

    resourceFetcher(tx, inferredResources, explicitResources)
  }

  def resourceFetcher(tx: HttpTx, inferredResources: List[NamedRequest], explicitResources: List[NamedRequest]) = {

    val uniqueResources: Map[URI, NamedRequest] = {
      val inf: Map[URI, NamedRequest] = inferredResources.map(res => res.ahcRequest.getURI -> res)(breakOut)
      val exp: Map[URI, NamedRequest] = explicitResources.map(res => res.ahcRequest.getURI -> res)(breakOut)
      inf ++ exp
    }

    if (uniqueResources.isEmpty)
      None
    else {
      Some(() => new ResourceFetcher(tx, uniqueResources.values))
    }
  }
}

// FIXME handle crash
class ResourceFetcher(tx: HttpTx, initialResources: Iterable[NamedRequest]) extends BaseActor {

  var session = tx.session
  val alreadySeen: Set[URI] = initialResources.map(_.ahcRequest.getURI).toSet
  val bufferedRequestsByHost = mutable.HashMap.empty[String, List[NamedRequest]].withDefaultValue(Nil)
  val availableTokensByHost = mutable.HashMap.empty[String, Int].withDefaultValue(tx.protocol.enginePart.maxConnectionsPerHost)
  var pendingRequestsCount = initialResources.size
  var okCount = 0
  var koCount = 0
  val start = nowMillis

  // start fetching
  fetchOrBufferResources(initialResources)

  def fetchResource(request: NamedRequest): Unit = {
    logger.debug(s"Fetching ressource ${request.ahcRequest.getUrl}")

    val resourceTx = tx.copy(
      session = this.session,
      request = request.ahcRequest,
      requestName = request.name,
      checks = request.checks,
      responseBuilderFactory = ResponseBuilder.newResponseBuilderFactory(request.checks, None, tx.protocol),
      next = self,
      resourceFetching = true)

    HttpRequestAction.startHttpTransaction(resourceTx)
  }

  def handleCachedRequest(request: NamedRequest): Unit = {
    logger.info(s"Fetching resource ${request.ahcRequest.getURI} from cache")
    // FIXME check if it's a css this way or use the Content-Type?
    val resourceFetched = if (ResourceFetcher.CssContentCache.contains(request.ahcRequest.getURI))
      CssResourceFetched(request.ahcRequest.getURI, OK, identity, None, None, "")
    else
      RegularResourceFetched(request.ahcRequest.getURI, OK, identity)

    receive(resourceFetched)
  }

  def fetchOrBufferResources(requests: Iterable[NamedRequest]): Unit = {

      def sendRequests(host: String, requests: Iterable[NamedRequest]): Unit = {
        requests.foreach(fetchResource)
        availableTokensByHost += host -> (availableTokensByHost(host) - requests.size)
      }

      def bufferRequests(host: String, requests: Iterable[NamedRequest]): Unit =
        bufferedRequestsByHost += host -> (bufferedRequestsByHost(host) ::: requests.toList)

    val (nonCachedRequests, cachedRequests) = requests.partition { request =>
      val uri = request.ahcRequest.getURI
      CacheHandling.getExpire(tx.protocol, session, uri) match {
        case None => true
        case Some(expire) if nowMillis > expire =>
          // ugly, side effecting
          session = CacheHandling.clearExpire(session, uri)
          true
        case _ => false
      }
    }

    cachedRequests.foreach(handleCachedRequest)

    nonCachedRequests
      .groupBy(_.ahcRequest.getURI.getHost)
      .foreach {
        case (host, reqs) =>
          val availableTokens = availableTokensByHost(host)
          val (immediateRequests, bufferedRequests) = reqs.splitAt(availableTokens)
          sendRequests(host, immediateRequests)
          bufferRequests(host, bufferedRequests)
      }
  }

  private def done(): Unit = {
    logger.debug("All resources were fetched")
    tx.next ! session.logGroupAsyncRequests(nowMillis - start, okCount, koCount)
    context.stop(self)
  }

  def resourceFetched(uri: URI, status: Status): Unit = {

      def releaseToken(host: String, bufferedRequests: List[NamedRequest]): Unit =
        bufferedRequests match {
          case Nil =>
            // nothing to send for this host
            availableTokensByHost += host -> (availableTokensByHost(host) + 1)

          case request :: tail =>
            bufferedRequestsByHost += host -> tail
            val uri = request.ahcRequest.getURI
            CacheHandling.getExpire(tx.protocol, session, uri) match {
              case None =>
                // recycle token, fetch a buffered resource
                fetchResource(request)

              case Some(expire) if nowMillis > expire =>
                // expire reached
                session = CacheHandling.clearExpire(session, uri)
                fetchResource(request)

              case _ =>
                handleCachedRequest(request)
                releaseToken(host, tail)
            }
        }

    logger.debug(s"Resource $uri was fetched")
    pendingRequestsCount -= 1

    if (status == OK)
      okCount = okCount + 1
    else
      koCount = koCount + 1

    if (pendingRequestsCount == 0)
      done()
    else {
      val requests = bufferedRequestsByHost.get(uri.getHost) match {
        case Some(reqs) => reqs
        case _          => Nil
      }
      releaseToken(uri.getHost, requests)
    }
  }

  def cssFetched(uri: URI, status: Status, statusCode: Option[Int], lastModifiedOrEtag: Option[String], content: String): Unit = {

    val protocol = tx.protocol

    if (status == OK) {
      // this css might contain some resources

      val rawCssResources: List[NamedRequest] = statusCode match {
        case Some(200) =>
          // try to get from cache
          lastModifiedOrEtag match {
            case Some(newLastModifiedOrEtag) =>
              val cacheKey = (protocol, uri)
              ResourceFetcher.InferredResourcesCache.get(cacheKey) match {
                case Some(InferredPageResources(`newLastModifiedOrEtag`, inferredResources)) =>
                  //cache entry didn't expire, use it
                  inferredResources
                case _ =>
                  // cache entry missing or expired, update it
                  ResourceFetcher.CssContentCache.remove(protocol -> uri)
                  val inferredResources = ResourceFetcher.cssResources(uri, protocol.responsePart.htmlResourcesInferringFilters, content).flatMap(_.toRequest(protocol, tx.throttled))
                  ResourceFetcher.InferredResourcesCache.put((protocol, uri), InferredPageResources(newLastModifiedOrEtag, inferredResources))
                  inferredResources
              }

            case None =>
              // don't cache
              ResourceFetcher.cssResources(uri, protocol.responsePart.htmlResourcesInferringFilters, content).flatMap(_.toRequest(protocol, tx.throttled))
          }

        case Some(304) =>
          // no content, retrieve from cache if exist
          val cacheKey = (protocol, uri)
          ResourceFetcher.InferredResourcesCache.get(cacheKey) match {
            case Some(inferredPageResources) => inferredPageResources.requests
            case _ =>
              logger.warn(s"Got a 304 for $uri but could find cache entry?!")
              Nil
          }
        case _ => Nil
      }

      val filtered = rawCssResources.filterNot(res => alreadySeen.contains(res.ahcRequest.getURI))

      pendingRequestsCount += filtered.size
      fetchOrBufferResources(filtered)
    }
  }

  def receive: Receive = {
    case RegularResourceFetched(uri, status, sessionUpdates) =>
      session = sessionUpdates(session)
      resourceFetched(uri, status)

    case CssResourceFetched(uri, status, sessionUpdates, statusCode, lastModifiedOrEtag, content) =>
      session = sessionUpdates(session)
      cssFetched(uri, status, statusCode, lastModifiedOrEtag, content)
      resourceFetched(uri, status)
  }
}
