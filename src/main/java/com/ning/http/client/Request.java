/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package com.ning.http.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import com.ning.http.client.cookie.Cookie;

/**
 * The Request class can be used to construct HTTP request:
 * <blockquote><pre>
 *   Request r = new RequestBuilder().setUrl("url")
 *                      .setRealm((new Realm.RealmBuilder()).setPrincipal(user)
 *                      .setPassword(admin)
 *                      .setRealmName("MyRealm")
 *                      .setScheme(Realm.AuthScheme.DIGEST).build());
 *   r.execute();
 * </pre></blockquote>
 */
public interface Request {

    /**
     * An entity that can be used to manipulate the Request's body output before it get sent.
     */
    public static interface EntityWriter {
        void writeEntity(OutputStream out) throws IOException;
    }

    /**
     * Return the request's type (GET, POST, etc.)
     *
     * @return the request's type (GET, POST, etc.)
     * @deprecated - use getMethod
     */
    String getReqType();

    /**
     * Return the request's method name (GET, POST, etc.)
     *
     * @return the request's method name (GET, POST, etc.)
     */
    String getMethod();

    /**
     * Return the decoded url
     *
     * @return the decoded url
     */
    String getUrl();

    URI getOriginalURI();
    URI getURI();
    URI getRawURI();

    /**
     * Return the InetAddress to override
     *
     * @return the InetAddress
     */
    InetAddress getInetAddress();

    InetAddress getLocalAddress();

    /**
     * Return the undecoded url
     *
     * @return the undecoded url
     */
    String getRawUrl();

    /**
     * Return the current set of Headers.
     *
     * @return a {@link FluentCaseInsensitiveStringsMap} contains headers.
     */
    FluentCaseInsensitiveStringsMap getHeaders();

    /**
     * Return Coookie.
     *
     * @return an unmodifiable Collection of Cookies
     */
    Collection<Cookie> getCookies();

    /**
     * Return the current request's body as a byte array
     *
     * @return a byte array of the current request's body.
     */
    byte[] getByteData();

    /**
     * Return the current request's body as a string
     *
     * @return an String representation of the current request's body.
     */
    String getStringData();

    /**
     * Return the current request's body as an InputStream
     *
     * @return an InputStream representation of the current request's body.
     */
    InputStream getStreamData();

    /**
     * Return the current request's body as an EntityWriter
     *
     * @return an EntityWriter representation of the current request's body.
     */
    EntityWriter getEntityWriter();

    /**
     * Return the current request's body generator.
     *
     * @return A generator for the request body.
     */
    BodyGenerator getBodyGenerator();

    /**
     * Return the current size of the content-lenght header based on the body's size.
     *
     * @return the current size of the content-lenght header based on the body's size.
     * @deprecated
     */
    long getLength();

    /**
     * Return the current size of the content-lenght header based on the body's size.
     *
     * @return the current size of the content-lenght header based on the body's size.
     */
    long getContentLength();

    /**
     * Return the current parameters.
     *
     * @return a {@link FluentStringsMap} of parameters.
     */
    FluentStringsMap getParams();

    /**
     * Return the current {@link Part}
     *
     * @return the current {@link Part}
     */
    List<Part> getParts();

    /**
     * Return the virtual host value.
     *
     * @return the virtual host value.
     */
    String getVirtualHost();

    /**
     * Return the query params.
     *
     * @return {@link FluentStringsMap} of query string
     */
    FluentStringsMap getQueryParams();

    /**
     * Return the {@link ProxyServer}
     *
     * @return the {@link ProxyServer}
     */
    ProxyServer getProxyServer();

    /**
     * Return the {@link Realm}
     *
     * @return the {@link Realm}
     */
    Realm getRealm();

    /**
     * Return the {@link File} to upload.
     *
     * @return the {@link File} to upload.
     */
    File getFile();

    /**
     * Return the <tt>true></tt> to follow redirect
     *
     * @return the <tt>true></tt> to follow redirect
     */
    boolean isRedirectEnabled();

    /**
     *
     * @return <tt>true></tt> if request's redirectEnabled setting
     *          should be used in place of client's
     */
    boolean isRedirectOverrideSet();

    /**
     * Return Per request configuration.
     *
     * @return Per request configuration.
     */
    PerRequestConfig getPerRequestConfig();

    /**
     * Return the HTTP Range header value, or
     *
     * @return the range header value, or 0 is not set.
     */
    long getRangeOffset();

    /**
     * Return the encoding value used when encoding the request's body.
     *
     * @return the encoding value used when encoding the request's body.
     */
    String getBodyEncoding();

    boolean isUseRawUrl();

    ConnectionPoolKeyStrategy getConnectionPoolKeyStrategy();
}
