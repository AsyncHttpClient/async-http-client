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
package org.asynchttpclient.request.body;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.testng.Assert.assertEquals;

import java.io.File;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * @author Benjamin Hanzelmann
 */
public class PutLargeFileTest extends AbstractBasicTest {

    @Test(groups = "standalone")
    public void testPutLargeFile() throws Exception {

        File file = createTempFile(1024 * 1024);

        int timeout = (int) file.length() / 1000;

        try (AsyncHttpClient client = asyncHttpClient(config().setConnectTimeout(timeout))) {
            Response response = client.preparePut(getTargetUrl()).setBody(file).execute().get();
            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Test(groups = "standalone")
    public void testPutSmallFile() throws Exception {

        File file = createTempFile(1024);

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = client.preparePut(getTargetUrl()).setBody(file).execute().get();
            assertEquals(response.getStatusCode(), 200);
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            public void handle(String arg0, Request arg1, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {

                resp.setStatus(200);
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                arg1.setHandled(true);
            }
        };
    }
}
