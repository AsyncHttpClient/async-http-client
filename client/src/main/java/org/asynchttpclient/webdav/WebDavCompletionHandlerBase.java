/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.webdav;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.NettyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple {@link AsyncHandler} that add support for WebDav's response manipulation.
 *
 * @param <T> the result type
 */
public abstract class WebDavCompletionHandlerBase<T> implements AsyncHandler<T> {
  private final Logger logger = LoggerFactory.getLogger(AsyncCompletionHandlerBase.class);
  private final List<HttpResponseBodyPart> bodyParts = Collections.synchronizedList(new ArrayList<>());
  private HttpResponseStatus status;
  private HttpHeaders headers;

  /**
   * {@inheritDoc}
   */
  @Override
  public final State onBodyPartReceived(final HttpResponseBodyPart content) {
    bodyParts.add(content);
    return State.CONTINUE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final State onStatusReceived(final HttpResponseStatus status) {
    this.status = status;
    return State.CONTINUE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final State onHeadersReceived(final HttpHeaders headers) {
    this.headers = headers;
    return State.CONTINUE;
  }

  private Document readXMLResponse(InputStream stream) {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    Document document;
    try {
      document = factory.newDocumentBuilder().parse(stream);
      parse(document);
    } catch (SAXException | IOException | ParserConfigurationException e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException(e);
    }
    return document;
  }

  private void parse(Document document) {
    Element element = document.getDocumentElement();
    NodeList statusNode = element.getElementsByTagName("status");
    for (int i = 0; i < statusNode.getLength(); i++) {
      Node node = statusNode.item(i);

      String value = node.getFirstChild().getNodeValue();
      int statusCode = Integer.valueOf(value.substring(value.indexOf(" "), value.lastIndexOf(" ")).trim());
      String statusText = value.substring(value.lastIndexOf(" "));
      status = new HttpStatusWrapper(status, statusText, statusCode);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final T onCompleted() throws Exception {
    if (status != null) {
      Document document = null;
      if (status.getStatusCode() == 207) {
        document = readXMLResponse(new NettyResponse(status, headers, bodyParts).getResponseBodyAsStream());
      }
      // recompute response as readXMLResponse->parse might have updated it
      return onCompleted(new WebDavResponse(new NettyResponse(status, headers, bodyParts), document));
    } else {
      throw new IllegalStateException("Status is null");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onThrowable(Throwable t) {
    logger.debug(t.getMessage(), t);
  }

  /**
   * Invoked once the HTTP response has been fully read.
   *
   * @param response The {@link org.asynchttpclient.Response}
   * @return Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
   * @throws Exception if something wrong happens
   */
  abstract public T onCompleted(WebDavResponse response) throws Exception;

  private class HttpStatusWrapper extends HttpResponseStatus {

    private final HttpResponseStatus wrapped;

    private final String statusText;

    private final int statusCode;

    HttpStatusWrapper(HttpResponseStatus wrapper, String statusText, int statusCode) {
      super(wrapper.getUri());
      this.wrapped = wrapper;
      this.statusText = statusText;
      this.statusCode = statusCode;
    }

    @Override
    public int getStatusCode() {
      return (statusText == null ? wrapped.getStatusCode() : statusCode);
    }

    @Override
    public String getStatusText() {
      return (statusText == null ? wrapped.getStatusText() : statusText);
    }

    @Override
    public String getProtocolName() {
      return wrapped.getProtocolName();
    }

    @Override
    public int getProtocolMajorVersion() {
      return wrapped.getProtocolMajorVersion();
    }

    @Override
    public int getProtocolMinorVersion() {
      return wrapped.getProtocolMinorVersion();
    }

    @Override
    public String getProtocolText() {
      return wrapped.getStatusText();
    }

    @Override
    public SocketAddress getRemoteAddress() {
      return wrapped.getRemoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
      return wrapped.getLocalAddress();
    }
  }
}
