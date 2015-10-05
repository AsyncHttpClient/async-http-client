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

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Simple {@link AsyncHandler} that add support for WebDav's response manipulation.
 *
 * @param <T> the result type
 */
public abstract class WebDavCompletionHandlerBase<T> implements AsyncHandler<T> {
    private final Logger logger = LoggerFactory.getLogger(AsyncCompletionHandlerBase.class);

    private final List<HttpResponseBodyPart> bodies = Collections.synchronizedList(new ArrayList<HttpResponseBodyPart>());
    private HttpResponseStatus status;
    private HttpResponseHeaders headers;

    /**
     * {@inheritDoc}
     */
    @Override
    public final State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
        bodies.add(content);
        return State.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final State onStatusReceived(final HttpResponseStatus status) throws Exception {
        this.status = status;
        return State.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final State onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
        this.headers = headers;
        return State.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final T onCompleted() throws Exception {
        if (status != null) {
            Response response = status.prepareResponse(headers, bodies);
            Document document = null;
            if (status.getStatusCode() == 207) {
                document = readXMLResponse(response.getResponseBodyAsStream());
            }
            return onCompleted(new WebDavResponse(status.prepareResponse(headers, bodies), document));
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

        public HttpStatusWrapper(HttpResponseStatus wrapper, String statusText, int statusCode) {
            super(wrapper.getUri(), null);
            this.wrapped = wrapper;
            this.statusText = statusText;
            this.statusCode = statusCode;
        }

        @Override
        public Response prepareResponse(HttpResponseHeaders headers, List<HttpResponseBodyPart> bodyParts) {
            final Response wrappedResponse = wrapped.prepareResponse(headers, bodyParts);

            return new Response() {

                @Override
                public int getStatusCode() {
                    return statusCode;
                }

                @Override
                public String getStatusText() {
                    return statusText;
                }

                @Override
                public byte[] getResponseBodyAsBytes() {
                    return wrappedResponse.getResponseBodyAsBytes();
                }

                @Override
                public ByteBuffer getResponseBodyAsByteBuffer() {
                    return wrappedResponse.getResponseBodyAsByteBuffer();
                }

                @Override
                public InputStream getResponseBodyAsStream() {
                    return wrappedResponse.getResponseBodyAsStream();
                }

                @Override
                public String getResponseBody(Charset charset) {
                    return wrappedResponse.getResponseBody(charset);
                }

                @Override
                public String getResponseBody() {
                    return wrappedResponse.getResponseBody();
                }

                @Override
                public Uri getUri() {
                    return wrappedResponse.getUri();
                }

                @Override
                public String getContentType() {
                    return wrappedResponse.getContentType();
                }

                @Override
                public String getHeader(String name) {
                    return wrappedResponse.getHeader(name);
                }

                @Override
                public List<String> getHeaders(String name) {
                    return wrappedResponse.getHeaders(name);
                }

                @Override
                public FluentCaseInsensitiveStringsMap getHeaders() {
                    return wrappedResponse.getHeaders();
                }

                @Override
                public boolean isRedirected() {
                    return wrappedResponse.isRedirected();
                }

                @Override
                public List<Cookie> getCookies() {
                    return wrappedResponse.getCookies();
                }

                @Override
                public boolean hasResponseStatus() {
                    return wrappedResponse.hasResponseStatus();
                }

                @Override
                public boolean hasResponseHeaders() {
                    return wrappedResponse.hasResponseHeaders();
                }

                @Override
                public boolean hasResponseBody() {
                    return wrappedResponse.hasResponseBody();
                }

                @Override
                public SocketAddress getRemoteAddress() {
                    return wrappedResponse.getRemoteAddress();
                }

                @Override
                public SocketAddress getLocalAddress() {
                    return wrappedResponse.getLocalAddress();
                }
            };
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

    private Document readXMLResponse(InputStream stream) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document document = null;
        try {
            document = factory.newDocumentBuilder().parse(stream);
            parse(document);
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (ParserConfigurationException e) {
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
}
