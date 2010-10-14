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
import com.ning.http.client.Response;
import com.ning.http.client.filter.AsyncFilterException;
import com.ning.http.client.filter.ThrottleRequestAsyncFilter;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

public class AsyncFilterTest extends AbstractBasicTest {

    private class BasicHandler extends AbstractHandler {

        public void handle(String s,
                           Request r,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) throws IOException, ServletException {

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new BasicHandler();
    }

    @Test(groups = "standalone")
    public void basicTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();

        Response response = c.addAsyncFilter(new ThrottleRequestAsyncFilter(100)).preparePost(getTargetUrl())
                .execute().get();
        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
    }

    @Test(groups = "standalone")
    public void loadThrottleTest() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();
        c.addAsyncFilter(new ThrottleRequestAsyncFilter(10));

        List<Future<Response>> futures = new ArrayList<Future<Response>>();
        for (int i =0; i < 200; i ++) {
            futures.add(c.preparePost(getTargetUrl()).execute());
        }

        for (Future<Response> f: futures) {
            Response r = f.get();
            assertNotNull(f.get());
            assertEquals(r.getStatusCode(), 200);
        }

    }

    @Test(groups = "standalone")
    public void maxConnectionsText() throws Throwable {

        AsyncHttpClient c = new AsyncHttpClient();

        try {
            Response response = c.addAsyncFilter(new ThrottleRequestAsyncFilter(0, 1000)).preparePost(getTargetUrl())
                    .execute().get();
            fail("Should have timed out");
        } catch (IOException ex) {
            assertNotNull(ex);
            assertEquals(ex.getCause().getClass(), AsyncFilterException.class);
        }
    }

    public String getTargetUrl(){
        return String.format("http://127.0.0.1:%d/foo/test", port1);
    }


}
