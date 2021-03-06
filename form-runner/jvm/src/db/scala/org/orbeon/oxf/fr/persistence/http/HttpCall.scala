/**
 * Copyright (C) 2019 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.persistence.http

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import org.orbeon.dom.Document
import org.orbeon.io.UriScheme
import org.orbeon.oxf.externalcontext.Credentials
import org.orbeon.oxf.fr.permission.Operations
import org.orbeon.oxf.fr.persistence.relational.rest.LockInfo
import org.orbeon.oxf.fr.persistence.relational.{Provider, Version}
import org.orbeon.oxf.http.HttpMethod._
import org.orbeon.oxf.http.{Headers, HttpMethod, HttpResponse, StreamedContent}
import org.orbeon.oxf.test.TestHttpClient
import org.orbeon.oxf.util.IOUtils.useAndClose
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.{Connection, ContentTypes, IndentedLogger, NetUtils}
import org.orbeon.oxf.xml.Dom4j
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.scalatest.Assertions._

import scala.util.Try

private[persistence] object HttpCall {

  private val PersistenceBase = "/fr/service/persistence/"

  sealed trait Body
  case class XML   (doc : Document   ) extends Body
  case class Binary(file: Array[Byte]) extends Body

  case class SolicitedRequest(
    path        : String,
    method      : HttpMethod,
    version     : Version,
    body        : Option[Body]        = None,
    credentials : Option[Credentials] = None,
    timeout     : Option[Int]         = None
  )

  case class ExpectedResponse(
    code        : Int,
    operations  : Option[Operations]  = None,
    formVersion : Option[Int]         = None,
    body        : Option[Body]        = None
  )

  def assertCall(
    actualRequest    : SolicitedRequest,
    expectedResponse : ExpectedResponse)(
    implicit logger  : IndentedLogger
  ): Unit = {
    useAndClose(
      request(
        path = actualRequest.path,
        method = actualRequest.method,
        version = actualRequest.version,
        body = actualRequest.body,
        credentials = actualRequest.credentials,
        timeout = actualRequest.timeout
      )
    ) { closableHttpResponse ⇒

      val actualResponse = closableHttpResponse.httpResponse
      val actualHeaders  = actualResponse.headers

      // Check response code
      assert(actualResponse.statusCode == expectedResponse.code)

      // Check operations
      expectedResponse.operations.foreach { expectedOperations ⇒
        val actualOperationsString = actualHeaders.get("orbeon-operations").map(_.head)
        val actualOperationsList   = actualOperationsString.to[List].flatMap(_.splitTo[List]())
        val actualOperations       = Operations.parse(actualOperationsList)
        assert(expectedOperations == actualOperations)
      }

      // Check form version
      val resultFormVersion = actualHeaders.get(Version.OrbeonFormDefinitionVersionLower).map(_.head).map(_.toInt)
      assert(expectedResponse.formVersion == resultFormVersion)

      // Check body
      expectedResponse.body.foreach { expectedBody ⇒
        val actualBody =
          useAndClose(actualResponse.content.inputStream) { inputStream ⇒
            val outputStream = new ByteArrayOutputStream
            NetUtils.copyStream(inputStream, outputStream)
            outputStream.toByteArray
          }
        expectedBody match {
          case HttpCall.XML(expectedDoc) ⇒
            val resultDoc  = Dom4jUtils.readDom4j(new ByteArrayInputStream(actualBody))
            if (! Dom4j.compareDocumentsIgnoreNamespacesInScope(resultDoc, expectedDoc))
              assert(
                Dom4jUtils.domToPrettyString(resultDoc) ===
                Dom4jUtils.domToPrettyString(expectedDoc)
              )
          case HttpCall.Binary(expectedFile) ⇒
            assert(actualBody == expectedFile)
        }
      }
    }
  }

  private def request(
    path            : String,
    method          : HttpMethod,
    version         : Version,
    body            : Option[Body],
    credentials     : Option[Credentials],
    timeout         : Option[Int] = None)(
    implicit logger : IndentedLogger
  ): ClosableHttpResponse = {

    val documentURL = PersistenceBase + path

    val headersCapitalized = {

      import Version._

      val timeoutHeader = timeout.map(t ⇒ Headers.Timeout → List(Headers.TimeoutValuePrefix + t.toString))
      val versionHeader = version match {
        case Unspecified             ⇒ None
        case Next                    ⇒ Some(OrbeonFormDefinitionVersion → List("next"))
        case Specific(version)       ⇒ Some(OrbeonFormDefinitionVersion → List(version.toString))
        case ForDocument(documentId) ⇒ Some(OrbeonForDocumentId         → List(documentId))
      }
      val headers = (timeoutHeader.toList ++ versionHeader.toList).toMap

      Connection.buildConnectionHeadersCapitalizedIfNeeded(
        scheme           = UriScheme.Http,
        hasCredentials   = false,
        customHeaders    = headers,
        headersToForward = Connection.headersToForwardFromProperty,
        cookiesToForward = Connection.cookiesToForwardFromProperty,
        getHeader        = _ ⇒ None
      )
    }

    val contentType = body.map {
      case XML   (_) ⇒ ContentTypes.XmlContentType
      case Binary(_) ⇒ "application/octet-stream"
    }

    val messageBody = body map {
      case XML   (doc ) ⇒ Dom4jUtils.domToString(doc).getBytes
      case Binary(file) ⇒ file
    }

    val content = messageBody map
      (StreamedContent.fromBytes(_, contentType))

    val (_, httpResponse, _, _) =
      TestHttpClient.connect(
        url          = documentURL,
        method       = method,
        headers      = headersCapitalized,
        content      = content,
        credentials  = credentials
      )

    new ClosableHttpResponse(httpResponse)
  }

  // Wrap `HttpResponse`, so we can later use `ScalaUtils.useAndClose()`, which expects a `.close()`,
  // while `HttpResponse` has a `.disconnect()`.
  private class ClosableHttpResponse(val httpResponse: HttpResponse) {
    def close(): Unit = httpResponse.disconnect()
  }

  private val FormName = "my-form"
  def crudURLPrefix   (provider: Provider) = s"crud/${provider.entryName}/$FormName/"
  def searchURLPrefix (provider: Provider) = s"search/${provider.entryName}/$FormName"
  def metadataURL     (provider: Provider) = s"form/${provider.entryName}/$FormName"

  def post(url: String, version: Version, body: Body, credentials: Option[Credentials] = None)(implicit logger: IndentedLogger): Int =
    useAndClose(request(url, POST, version, Some(body), credentials))(_.httpResponse.statusCode)

  def put(url: String, version: Version, body: Body, credentials: Option[Credentials] = None)(implicit logger: IndentedLogger): Int =
    useAndClose(request(url, PUT, version, Some(body), credentials))(_.httpResponse.statusCode)

  def del(url: String, version: Version, credentials: Option[Credentials] = None)(implicit logger: IndentedLogger): Int =
    useAndClose(request(url, DELETE, version, None, credentials))(_.httpResponse.statusCode)

  def get(url: String, version: Version, credentials: Option[Credentials] = None)(implicit logger: IndentedLogger): (Int, Map[String, Seq[String]], Try[Array[Byte]]) =
    useAndClose(request(url, GET, version, None, credentials)) { chr ⇒

      val httpResponse = chr.httpResponse
      val statusCode   = httpResponse.statusCode
      val headers      = httpResponse.headers

      val body =
        useAndClose(httpResponse.content.inputStream) { inputStream ⇒
          Try {
            val outputStream = new ByteArrayOutputStream
            NetUtils.copyStream(inputStream, outputStream)
            outputStream.toByteArray
          }
        }

      (statusCode, headers, body)
    }

  def lock(url: String, lockInfo: LockInfo, timeout: Int)(implicit logger: IndentedLogger): Int =
    Private.lockUnlock(LOCK, url, lockInfo, Some(timeout))

  def unlock(url: String, lockInfo: LockInfo)(implicit logger: IndentedLogger): Int =
    Private.lockUnlock(UNLOCK, url, lockInfo, None)

  private object Private {
    def lockUnlock(
      method   : HttpMethod,
      url      : String,
      lockInfo : LockInfo,
      timeout  : Option[Int])(implicit
      logger   : IndentedLogger
    ): Int = {
      val body = Some(XML(LockInfo.toDom4j(lockInfo)))
      useAndClose(request(url, method, Version.Unspecified, body, None, timeout))(_.httpResponse.statusCode)
    }
  }
}
