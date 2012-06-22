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
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.consumers.AppendableBodyConsumer;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.security.Constraint;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public abstract class BasicAuthTest extends AbstractBasicTest {

    protected final static String MY_MESSAGE = "my message";
    protected final static String user = "user";
    protected final static String admin = "admin";

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

        List<ConstraintMapping> cm = new ArrayList<ConstraintMapping>();
        cm.add(mapping);

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add(user);
        knownRoles.add(admin);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setConstraintMappings(cm, knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(false);
        security.setHandler(configureHandler());

        server.setHandler(security);
        server.start();
        log.info("Local HTTP server started successfully");
    }

    private String getFileContent(final File file) {
        FileInputStream in = null;
        try {
            if (file.exists() && file.canRead()) {
                final StringBuilder sb = new StringBuilder(128);
                final byte[] b = new byte[512];
                int read;
                in = new FileInputStream(file);
                while ((read = in.read(b)) != -1) {
                    sb.append(new String(b, 0, read, "UTF-8"));
                }
                return sb.toString();
            }
            throw new IllegalArgumentException("File does not exist or cannot be read: "
                    + file.getCanonicalPath());
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }

    }

    private void setUpSecondServer() throws Exception {
        server2 = new Server();
        port2 = findFreePort();

        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setHost("127.0.0.1");
        connector.setPort(port2);

        server2.addConnector(connector);

        LoginService loginService = new HashLoginService("MyRealm", "src/test/resources/realm.properties");
        server2.addBean(loginService);

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__DIGEST_AUTH);
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

        List<ConstraintMapping> cm = new ArrayList<ConstraintMapping>();
        cm.add(mapping);

        security.setConstraintMappings(cm, knownRoles);
        security.setAuthenticator(new DigestAuthenticator());
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
        public void handle(String s,
                           Request r,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException, ServletException {

            System.err.println("redirecthandler");
            System.err.println("request: " + request.getRequestURI());
            if ("/uff".equals(request.getRequestURI())) {

                System.err.println("redirect to /bla");
                response.setStatus(302);
                response.setHeader("Location", "/bla");
                response.getOutputStream().flush();
                response.getOutputStream().close();

                return;

            } else {
                System.err.println("got redirected" + request.getRequestURI());
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
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
            response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
            response.setStatus(200);


            int size = 10 * 1024;
            if (request.getContentLength() > 0) {
                size = request.getContentLength();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                int read = request.getInputStream().read(bytes);
                if (read > 0) {
                    response.getOutputStream().write(bytes, 0, read);
                }
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void redirectAndBasicAuthTest() throws Exception, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = null;
        try {
            setUpSecondServer();
            client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).setMaximumNumberOfRedirects(10).build());
            AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl2())
                    // .setHeader( "X-302", "/bla" )
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

            Future<Response> f = r.execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));

        } finally {
            if (client != null) client.close();
            stopSecondServer();
        }
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    protected String getTargetUrl2() {
        return "http://127.0.0.1:" + port2 + "/uff";
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basic401Test() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
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
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthTestPreemtiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).setUsePreemptiveAuth(true).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal("fake").setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), 401);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthInputStreamTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        ByteArrayInputStream is = new ByteArrayInputStream("test".getBytes());
        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl())
                .setBody(is).setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(30, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), "test");
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthFileTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(null);
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());
        final String fileContent = getFileContent(file);

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl())
                .setBody(file).setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), fileContent);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthAsyncConfigTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build()).build());
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());
        final String fileContent = getFileContent(file);

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl()).setBody(file);

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), fileContent);
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void basicAuthFileNoKeepAliveTest() throws Throwable {
        AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAllowPoolingConnection(false).build());
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL url = cl.getResource("SimpleTextFile.txt");
        File file = new File(url.toURI());
        final String fileContent = getFileContent(file);

        AsyncHttpClient.BoundRequestBuilder r = client.preparePost(getTargetUrl())
                .setBody(file).setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(resp.getResponseBody(), fileContent);
        client.close();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = false)
    public void StringBufferBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setRealmPrincipal(user)
                .setRealmPassword(admin)
                .setUrl(getTargetUrl())
                .setHeader("Content-Type", "text/html").build();

        StringBuilder s = new StringBuilder();
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(s.toString(), MY_MESSAGE);
        assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
        assertNotNull(response.getHeader("X-Auth"));

        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void noneAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(getTargetUrl())
                .setRealm((new Realm.RealmBuilder()).setPrincipal(user).setPassword(admin).build());

        Future<Response> f = r.execute();
        Response resp = f.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertNotNull(resp.getHeader("X-Auth"));
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        client.close();
    }
}

