/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
