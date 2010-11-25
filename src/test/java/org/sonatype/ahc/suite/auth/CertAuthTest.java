package org.sonatype.ahc.suite.auth;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import org.sonatype.ahc.suite.util.AsyncSuiteConfiguration;
import org.sonatype.ahc.suite.util.CertUtil;
import org.sonatype.tests.http.runner.annotations.Configurators;
import org.sonatype.tests.http.server.api.ServerProvider;
import org.sonatype.tests.http.server.jetty.configurations.CertAuthSuiteConfigurator;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

/**
 * @author Benjamin Hanzelmann
 */
@Configurators(CertAuthSuiteConfigurator.class)
public class CertAuthTest
        extends AsyncSuiteConfiguration {

    private String keystorePath = "src/test/resources/client.keystore";

    private String keystorePass = "password";

    private String alias = "client";

    private AsyncHttpClient client;

    @Override
    public void configureProvider(ServerProvider provider) {
        super.configureProvider(provider);
        provider.addUser(alias, CertUtil.getCertificate(alias, keystorePath, keystorePass));
    }

    @AfterMethod
    public void after()
            throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test(groups = "standalone")
    public void testCertAuth()
            throws Exception {
        AsyncHttpClientConfig cfg =
                super.builder().setSSLContext(CertUtil.sslContext(keystorePath, keystorePass, alias)).build();
        client = new AsyncHttpClient(cfg);

        Response response = execute(client.prepareGet(url("content", "test")));
        assertEquals(200, response.getStatusCode());
        assertEquals("test", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testCertAuthFail()
            throws Exception {
        try {
            execute(client().prepareGet(url("content", "test")));
        }
        catch (ExecutionException e) {
            Throwable cause = e;
            boolean seen = false;
            while ((cause = cause.getCause()) != null) {
                if (cause instanceof IOException) {
                    seen = true;
                    break;
                }
            }
            assertTrue("No SSLException mentioned as cause", seen);
        }

    }
}
