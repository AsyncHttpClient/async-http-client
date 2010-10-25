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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Realm;
import com.ning.http.client.Response;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class BasicAuthTest extends AbstractBasicTest {

    private final static String user = "user";
    private final static String admin = "admin";

    private Server server2;

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        server = new Server();
        Logger root = Logger.getRootLogger();
        root.setLevel(Level.DEBUG);
        root.addAppender(new ConsoleAppender(
                new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN)));

        port1 = findFreePort();
        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port1);

        server.addConnector(listener);

        LoginService loginService = new HashLoginService("MyRealm", "src/test/resources/realm.properties");
        server.addBean(loginService);

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{user, admin});
        constraint.setAuthenticate(true);

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add(user);
        knownRoles.add(admin);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setConstraintMappings(new ConstraintMapping[]{mapping}, knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(false);
        security.setHandler(configureHandler());

        server.setHandler(security);
        server.start();
        log.info("Local HTTP server started successfully");
    }

    private void setUpSecondServer() throws Exception {
        server2 = new Server();
        port2 = findFreePort();
        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port2);

        server2.addConnector(listener);

        LoginService loginService = new HashLoginService("MyRealm", "src/test/resources/realm.properties");
        server2.addBean(loginService);

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{user, admin});
        constraint.setAuthenticate(true);

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add(user);
        knownRoles.add(admin);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler() {

            @Override
            public void handle(String arg0, Request arg1, HttpServletRequest arg2, HttpServletResponse arg3)
                    throws IOException, ServletException {
                System.err.println("request in security handler");
                System.err.println("Authorization: " + arg2.getHeader("Authorization"));
                System.err.println("RequestUri: " + arg2.getRequestURI());
                super.handle(arg0, arg1, arg2, arg3);
            }
        };
        security.setConstraintMappings(new ConstraintMapping[]{mapping}, knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(true);
        security.setHandler(new RedirectHandler());

        server2.setHandler(security);
        server2.start();
    }

    private void stopSecondServer() throws Exception {
        server2.stop();
    }

    private class RedirectHandler extends AbstractHandler {

        private AtomicBoolean redirectOnce = new AtomicBoolean(false);

        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

            System.err.println(request.getRequestURI());
            if (!redirectOnce.getAndSet(true) && !"/bla".equals(request.getRequestURI())) {
                System.err.println("redirecting to " + request.getHeader("X-302"));
                if (request.getHeader("X-302") != null) {
                    response.setStatus(302);
                    response.setHeader("Location", request.getHeader("X-302"));
                    response.getOutputStream().flush();
                    response.getOutputStream().close();

                    return;
                }
            } else {
                System.err.println("got redirected" + request.getRequestURI());
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-Content-Lenght", String.valueOf(request.getContentLength()));
                response.setStatus(200);
                response.getOutputStream().write("content".getBytes("UTF-8"));
                response.getOutputStream().flush();
                response.getOutputStream().close();
            }
        }
    }

    private class SimpleHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.getOutputStream().flush();
                response.getOutputStream().close();

                return;
            }
            response.addHeader("X-Auth", request.getHeader("Authorization"));
            response.addHeader("X-Content-Lenght", String.valueOf(request.getContentLength()));
            response.setStatus(200);
            response.getOutputStream().flush();
            response.getOutputStream().close();

        }
    }

    @Test(groups = "standalone")
    public void basicAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
    }

    @Test(groups = "standalone")
    public void redirectAndBasicAuthTest() throws Exception, ExecutionException, TimeoutException, InterruptedException {
        try {
            setUpSecondServer();
            AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
            AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl2())
                    .setHeader("X-302", "/bla")
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

            Future<Response> f = r.execute();
            Response resp = f.get(30, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
        } finally {
            stopSecondServer();
        }
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    protected String getTargetUrl2() {
        return "http://127.0.0.1:" + port2 + "/";
    }

    @Test(groups = "standalone")
    public void basic401Test() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setHeader("X-401", "401").setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Integer> f = r.execute(new AsyncHandler<Integer>() {

            private HttpResponseStatus status;


            public void onThrowable(Throwable t) {

            }

            public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                return STATE.CONTINUE;
            }

            public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                this.status = responseStatus;

                if (status.getStatusCode() != 200) {
                    return STATE.ABORT;
                }
                return STATE.CONTINUE;
            }

            public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                return STATE.CONTINUE;
            }

            public Integer onCompleted() throws Exception {
                return status.getStatusCode();
            }
        });
        Integer statusCode = f.get(10, TimeUnit.SECONDS);
        assertNotNull(statusCode);
        assertEquals(statusCode.intValue(), 401);
    }

    @Test(groups = "standalone")
    public void basicAuthTestPreemtiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).setUsePreemptiveAuth(true).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
    }

    @Test(groups = "standalone")
    public void basicAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal("fake").setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), 401);
    }

    @Test(groups = "standalone")
    public void basicAuthInputStreamTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        ByteArrayInputStream is = new ByteArrayInputStream("test".getBytes());
        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl())
                .setBody(is).setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("X-Content-Lenght"), "4");
    }

    @Test(groups = "standalone")
    public void basicAuthFileTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient();
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl())
                .setBody(file).setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("X-Content-Lenght"), "26");
    }

    @Test(groups = "standalone")
    public void basicAuthAsyncConfigTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build()).build());
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl()).setBody(file);

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("X-Content-Lenght"), "26");
    }

    @Test(groups = "standalone")
    public void basicAuthFileNoKeepAliveTest() throws Throwable {
        AsyncHttpClient client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setKeepAlive(false).build());
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl())
                .setBody(file).setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getHeader("X-Content-Lenght"), "26");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }
}
