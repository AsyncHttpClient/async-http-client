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

import com.ning.http.client.AsyncHttpClient;
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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class BasicAuthTest extends AbstractBasicTest {

    private final static String user = "user";
    private final static String admin = "admin";

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

    private class SimpleHandler extends AbstractHandler {
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

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
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet("http://127.0.0.1:" + port1 + "/")
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
    }

    @Test(groups = "standalone")
    public void basicAuthTestPreemtiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = new AsyncHttpClient();
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet("http://127.0.0.1:" + port1 + "/")
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
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet("http://127.0.0.1:" + port1 + "/")
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
        AsyncHttpClient.BoundRequestBuilder r = client.preparePost("http://127.0.0.1:" + port1 + "/")
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

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost("http://127.0.0.1:" + port1 + "/")
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
