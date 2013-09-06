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
 */
package org.asynchttpclient.async;

import java.io.File;
import java.net.URL;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;

public abstract class AbstractBasicHttpsTest extends AbstractBasicTest {
    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractBasicHttpsTest.class);

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        port1 = findFreePort();

        ClassLoader cl = getClass().getClassLoader();

        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        LOGGER.info("SSL keystore path: {}", keyStoreFile);
        SslContextFactory sslContextFactory = new SslContextFactory(keyStoreFile);
        sslContextFactory.setKeyStorePassword("changeit");

        String trustStoreFile = new File(cl.getResource("ssltest-cacerts.jks").toURI()).getAbsolutePath();
        LOGGER.info("SSL certs path: {}", trustStoreFile);
        sslContextFactory.setTrustStore(trustStoreFile);
        sslContextFactory.setTrustStorePassword("changeit");

        SslSocketConnector connector = new SslSocketConnector(sslContextFactory);
        connector.setHost("127.0.0.1");
        connector.setPort(port1);
        server.addConnector(connector);

        server.setHandler(configureHandler());
        server.start();
        LOGGER.info("Local HTTP server started successfully");
    }
}
