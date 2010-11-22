package org.sonatype.ahc.suite;

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

import com.ning.http.client.AsyncHttpClientConfig.Builder;
import org.sonatype.ahc.suite.util.CertUtil;
import org.sonatype.tests.http.runner.annotations.Configurators;
import org.sonatype.tests.http.server.api.ServerProvider;
import org.sonatype.tests.http.server.jetty.configurations.CertAuthSuiteConfigurator;

import javax.net.ssl.SSLContext;

/**
 * @author Benjamin Hanzelmann
 */
@Configurators(CertAuthSuiteConfigurator.class)
public class CertAuthGetTest
        extends GetTest {

    private String keystorePath = "src/test/resources/client.keystore";

    private String keystorePass = "password";

    private String alias = "client";

    @Override
    protected Builder builder() {
        return super.builder().setSSLContext(sslContext());

    }

    private SSLContext sslContext() {
        return CertUtil.sslContext(keystorePath, keystorePass, alias);
    }

    @Override
    public void configureProvider(ServerProvider provider) {
        super.configureProvider(provider);
        provider.addUser(alias, CertUtil.getCertificate(alias, keystorePath, keystorePass));
    }
}
