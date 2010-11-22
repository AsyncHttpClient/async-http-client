package org.sonatype.ahc.suite.redirect;

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

import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Response;
import org.sonatype.ahc.suite.util.AsyncSuiteConfiguration;
import org.sonatype.tests.http.server.jetty.behaviour.Pause;
import org.sonatype.tests.http.server.jetty.behaviour.Redirect;
import org.sonatype.tests.http.server.jetty.impl.JettyServerProvider;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutionException;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.fail;

/**
 * @author Benjamin Hanzelmann
 */
public class RedirectHttpTest
        extends AsyncSuiteConfiguration {

    @Test(groups = "standalone", expectedExceptions = MaxRedirectException.class)
    public void testTooManyRedirects()
            throws Throwable {
        String url = url("redirect", String.valueOf(client().getConfig().getMaxRedirects() + 1), "foo");
        try {
            executeGet(url);
            fail("expected error");
        }
        catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    @Test(groups = "standalone", enabled = false)
    // TODO: "Redirects == connection attempts? (first connect counts as a redirection?)" )
    public void testMaxRedirects()
            throws Exception {
        String url = url("redirect", String.valueOf(client().getConfig().getMaxRedirects()), "foo");
        Response response = executeGet(url);
        assertEquals(200, response.getStatusCode());
        assertEquals("foo", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testMaxRedirectsOffByOne()
            throws Exception {
        String url = url("redirect", String.valueOf(client().getConfig().getMaxRedirects() - 1), "foo");
        Response response = executeGet(url);
        assertEquals(200, response.getStatusCode());
        assertEquals("foo", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testRedirectAbsolute()
            throws Exception {
        provider().addBehaviour("/absolute/*", new Redirect(url("content", "someContent")));
        String url = url("absolute", "foo");
        Response response = executeGet(url);
        assertEquals(200, response.getStatusCode());
        assertEquals("someContent", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testRedirectOtherServer()
            throws Exception {
        JettyServerProvider p = new JettyServerProvider();
        p.addDefaultServices();
        p.start();

        provider().addBehaviour("/external/*", new Redirect("http://localhost:" + p.getPort() + "/content/foo"));
        Response response = executeGet(url("external", "bar"));

        assertEquals(200, response.getStatusCode());
        assertEquals("foo", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testRedirectToSSL()
            throws Exception {
        JettyServerProvider p = new JettyServerProvider();
        p.setSSL("keystore", "password");
        p.addDefaultServices();
        p.start();

        provider().addBehaviour("/external/*", new Redirect(p.getUrl() + "/content/foo"));
        Response response = executeGet(url("external", "bar"));

        assertEquals(200, response.getStatusCode());
        assertEquals("foo", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testTimeoutAfterRedirect()
            throws Exception {
        JettyServerProvider p = new JettyServerProvider();
        p.addDefaultServices();
        p.start();

        provider().addBehaviour("/external/*", new Redirect("http://localhost:" + p.getPort() + "/content/foo"),
                new Pause(10000));
        Response response = executeGet(url("external", "bar"));

        assertEquals(200, response.getStatusCode());
        assertEquals("foo", response.getResponseBody());
    }

}
