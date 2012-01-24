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

package com.ning.http.client.webdav;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Simple {@link AsyncHandler} that add support for WebDav's response manipulation.
 *
 * @param <T>
 */
public abstract class WebDavCompletionHandlerBase<T> implements AsyncHandler<T> {
    private final Logger logger = LoggerFactory.getLogger(AsyncCompletionHandlerBase.class);

    private final Collection<HttpResponseBodyPart> bodies =
            Collections.synchronizedCollection(new ArrayList<HttpResponseBodyPart>());
    private HttpResponseStatus status;
    private HttpResponseHeaders headers;

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public final STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
        bodies.add(content);
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public final STATE onStatusReceived(final HttpResponseStatus status) throws Exception {
        this.status = status;
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public final STATE onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
        this.headers = headers;
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public final T onCompleted() throws Exception {
        if (status != null) {
            Response response = status.provider().prepareResponse(status, headers, bodies);
            Document document = null;
            if (status.getStatusCode() == 207) {
                document = readXMLResponse(response.getResponseBodyAsStream());
            }
            return onCompleted(new WebDavResponse(status.provider().prepareResponse(status, headers, bodies), document));
        } else {
            throw new IllegalStateException("Status is null");
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void onThrowable(Throwable t) {
        logger.debug(t.getMessage(), t);
    }

    /**
     * Invoked once the HTTP response has been fully read.
     *
     * @param response The {@link com.ning.http.client.Response}
     * @return Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
     */
    abstract public T onCompleted(WebDavResponse response) throws Exception;


    private class HttpStatusWrapper extends HttpResponseStatus {

        private final HttpResponseStatus wrapper;

        private final String statusText;

        private final int statusCode;

        public HttpStatusWrapper(HttpResponseStatus wrapper, String statusText, int statusCode) {
            super(wrapper.getUrl(), wrapper.provider());
            this.wrapper = wrapper;
            this.statusText = statusText;
            this.statusCode = statusCode;
        }

        @Override
        public int getStatusCode() {
            return (statusText == null ? wrapper.getStatusCode() : statusCode);
        }

        @Override
        public String getStatusText() {
            return (statusText == null ? wrapper.getStatusText() : statusText);
        }

        @Override
        public String getProtocolName() {
            return wrapper.getProtocolName();
        }

        @Override
        public int getProtocolMajorVersion() {
            return wrapper.getProtocolMajorVersion();
        }

        @Override
        public int getProtocolMinorVersion() {
            return wrapper.getProtocolMinorVersion();
        }

        @Override
        public String getProtocolText() {
            return wrapper.getStatusText();
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
