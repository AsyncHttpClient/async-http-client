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

import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;
import org.sonatype.ahc.suite.util.AsyncSuiteConfiguration;
import org.sonatype.tests.http.server.jetty.behaviour.BasicAuth;
import org.sonatype.tests.http.server.jetty.behaviour.Content;
import org.sonatype.tests.http.server.jetty.behaviour.Fail;
import org.sonatype.tests.http.server.jetty.behaviour.Realm;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertEquals;

/**
 * @author Benjamin Hanzelmann
 */
public class AuthMethodsTest
        extends AsyncSuiteConfiguration {

    @Test(groups = "standalone")
    public void testNonPreemptiveAuth()
            throws Exception {
        Fail fail = new Fail(1, 401, "not authorized");
        BasicAuth auth = new BasicAuth("user", "password");
        provider().addBehaviour("/auth/*", new Realm("Test server"), fail, auth, new Content());
        setAuthentication("user", "password", false);

        String url = url("auth", "test");
        BoundRequestBuilder rb = client().prepareGet(url);
        Response response = execute(rb);

        assertEquals("test", response.getResponseBody());
        assertEquals(1, fail.getFailedCount());
        assertEquals(0, auth.getFailedCount());
    }

    @Test(groups = "standalone")
    public void testPreemptiveAuth()
            throws Exception {
        BasicAuth auth = new BasicAuth("user", "password");
        provider().addBehaviour("/auth/*", auth,
                new Content());
        setAuthentication("user", "password", true);

        String url = url("auth", "test");
        BoundRequestBuilder rb = client().prepareGet(url);
        Response response = execute(rb);

        assertEquals("test", response.getResponseBody());
        assertEquals(0, auth.getFailedCount());
    }

}
