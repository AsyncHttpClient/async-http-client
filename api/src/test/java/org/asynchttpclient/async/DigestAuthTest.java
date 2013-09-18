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
package org.asynchttpclient.async;

import static org.asynchttpclient.async.util.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public abstract class DigestAuthTest extends AbstractBasicTest {

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();

        server = newJettyHttpServer(port1);
        addDigestAuthHandler(server, false, configureHandler());
        server.start();
        logger.info("Local HTTP server started successfully");
    }

    private static class SimpleHandler extends AbstractHandler {
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            response.addHeader("X-Auth", request.getHeader("Authorization"));
            response.setStatus(200);
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void digestAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet("http://127.0.0.1:" + port1 + "/")//
                    .setRealm(new Realm.RealmBuilder().setPrincipal(USER).setPassword(ADMIN).setRealmName("MyRealm").setScheme(Realm.AuthScheme.DIGEST).build())//
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp.getHeader("X-Auth"));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void digestAuthTestWithoutScheme() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet("http://127.0.0.1:" + port1 + "/")//
                    .setRealm(new Realm.RealmBuilder().setPrincipal(USER).setPassword(ADMIN).setRealmName("MyRealm").build())//
                    .execute();
            Response resp = f.get(60, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp.getHeader("X-Auth"));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void digestAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            Future<Response> f = client.prepareGet("http://127.0.0.1:" + port1 + "/")//
                    .setRealm(new Realm.RealmBuilder().setPrincipal("fake").setPassword(ADMIN).setScheme(Realm.AuthScheme.DIGEST).build())//
                    .execute();
            Response resp = f.get(20, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 401);
        } finally {
            client.close();
        }
    }
}
