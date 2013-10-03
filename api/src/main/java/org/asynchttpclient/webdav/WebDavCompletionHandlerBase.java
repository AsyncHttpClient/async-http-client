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

import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Cookie;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
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
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple {@link AsyncHandler} that add support for WebDav's response manipulation.
 *
 * @param <T>
 */
public abstract class WebDavCompletionHandlerBase<T> implements AsyncHandler<T> {
    private final Logger logger = LoggerFactory.getLogger(AsyncCompletionHandlerBase.class);

    private final List<HttpResponseBodyPart> bodies =
            Collections.synchronizedList(new ArrayList<HttpResponseBodyPart>());
    private HttpResponseStatus status;
    private HttpResponseHeaders headers;

    /**
     * {@inheritDoc}
     */
    @Override
    public final STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
        bodies.add(content);
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final STATE onStatusReceived(final HttpResponseStatus status) throws Exception {
        this.status = status;
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final STATE onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
        this.headers = headers;
        return STATE.CONTINUE;
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
                public byte[] getResponseBodyAsBytes() throws IOException {
                    return wrappedResponse.getResponseBodyAsBytes();
                }

                @Override
                public ByteBuffer getResponseBodyAsByteBuffer() throws IOException {
                    return wrappedResponse.getResponseBodyAsByteBuffer();
                }

                @Override
                public InputStream getResponseBodyAsStream() throws IOException {
                    return wrappedResponse.getResponseBodyAsStream();
                }

                @Override
                public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
                    return wrappedResponse.getResponseBodyExcerpt(maxLength, charset);
                }

                @Override
                public String getResponseBody(String charset) throws IOException {
                    return wrappedResponse.getResponseBody(charset);
                }

                @Override
                public String getResponseBodyExcerpt(int maxLength) throws IOException {
                    return wrappedResponse.getResponseBodyExcerpt(maxLength);
                }

                @Override
                public String getResponseBody() throws IOException {
                    return wrappedResponse.getResponseBody();
                }

                @Override
                public URI getUri() throws MalformedURLException {
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
