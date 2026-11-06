/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

@FunctionalInterface
public interface SslEngineFactory {

    /**
     * Creates a new {@link SSLEngine}.
     *
     * @param config   the client config
     * @param peerHost the peer hostname
     * @param peerPort the peer port
     * @return new engine
     */
    SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort);

    /**
     * Creates a new {@link SSLEngine}, optionally permitting HTTP/2 (ALPN {@code h2}) negotiation.
     * <p>
     * AsyncHttpClient passes {@code http2Allowed = false} for a connection that must stay on HTTP/1.1:
     * <ul>
     * <li>a WebSocket connection, since AsyncHttpClient does not implement RFC 8441 (WebSocket over
     * HTTP/2);</li>
     * <li>the connection to an HTTPS proxy, which only ever carries an HTTP/1.1 {@code CONNECT} or
     * absolute-URI request. If it negotiates {@code h2} anyway, the request fails;</li>
     * <li>a connection that a login authenticates, since every stream of an HTTP/2 connection would share
     * that login: an NTLM, Kerberos or SPNEGO login, or any proxy login on a {@code CONNECT} tunnel or through
     * SOCKS.</li>
     * </ul>
     * The default implementation ignores the flag and delegates to
     * {@link #newSslEngine(AsyncHttpClientConfig, String, int)} for backwards compatibility;
     * {@code DefaultSslEngineFactory} overrides it to advertise only {@code http/1.1} in ALPN when
     * {@code http2Allowed} is {@code false}.
     *
     * @param config       the client config
     * @param peerHost     the peer hostname
     * @param peerPort     the peer port
     * @param http2Allowed whether HTTP/2 (ALPN {@code h2}) may be negotiated on this connection
     * @return new engine
     */
    default SSLEngine newSslEngine(AsyncHttpClientConfig config, String peerHost, int peerPort, boolean http2Allowed) {
        return newSslEngine(config, peerHost, peerPort);
    }

    /**
     * Perform any necessary one-time configuration. This will be called just once before {@code newSslEngine} is called
     * for the first time.
     *
     * @param config the client config
     * @throws SSLException if initialization fails. If an exception is thrown, the instance will not be used as client
     *                      creation will fail.
     */
    default void init(AsyncHttpClientConfig config) throws SSLException {
        // no op
    }

    /**
     * Perform any necessary cleanup.
     */
    default void destroy() {
        // no op
    }
}
