package org.sonatype.ahc.suite.util;

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
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncHttpClientConfig.Builder;
import com.ning.http.client.Realm;
import com.ning.http.client.Realm.RealmBuilder;
import com.ning.http.client.Response;
import org.sonatype.tests.http.runner.testng.TestNGSuiteConfiguration;
import org.sonatype.tests.http.server.api.ServerProvider;
import org.sonatype.tests.http.server.jetty.impl.JettyServerProvider;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Factory;

/**
 * @author Benjamin Hanzelmann
 */
public class AsyncSuiteConfiguration
        extends TestNGSuiteConfiguration {

    protected Realm realm;

    private int timeout = 2000;

    private AsyncHttpClient client;

    protected AsyncHttpClient client() {
        client = new AsyncHttpClient(settings(builder()).build());
        return client;
    }

    /**
     * @return
     */
    protected Builder builder() {
        return new Builder();
    }

    @AfterMethod
    public void after()
            throws Exception {
        super.after();
        if (client != null) {
            client.close();
        }
    }

    protected Response executeGet(String url)
            throws Exception {
        BoundRequestBuilder rb = client().prepareGet(url);
        requestSettings(rb);
        Response response = rb.execute().get();
        return response;
    }

    protected Builder settings(Builder rb) {
        rb.setFollowRedirects(true);
        rb.setConnectionTimeoutInMs(timeout);
        rb.setIdleConnectionInPoolTimeoutInMs(timeout);
        rb.setRequestTimeoutInMs(timeout);
        rb.setMaximumNumberOfRedirects(5);
        return rb;
    }

    protected BoundRequestBuilder requestSettings(BoundRequestBuilder rb) {
        if (realm != null) {
            rb.setRealm(realm);
        }
        return rb;
    }

    protected void setAuthentication(String principal, String password, boolean usePreemptiveAuth) {
        if (principal == null) {
            realm = null;
        } else {
            RealmBuilder realmbuilder = new Realm.RealmBuilder();
            realmbuilder.setPrincipal(principal);
            realmbuilder.setPassword(password);
            realmbuilder.setUsePreemptiveAuth(usePreemptiveAuth);
            realm = realmbuilder.build();
        }
    }

    protected Response execute(BoundRequestBuilder rb)
            throws Exception {
        return requestSettings(rb).execute().get();
    }

    @Override
    public void configureProvider(ServerProvider provider) {
        super.configureProvider(provider);
        if (JettyServerProvider.class.isAssignableFrom(provider.getClass())) {
            ((JettyServerProvider) provider).addDefaultServices();
        }
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Factory
    public Object[] configurationTests()
            throws Exception {
        return TestNGSuiteConfiguration.testNGFactory(getClass());
    }

}
