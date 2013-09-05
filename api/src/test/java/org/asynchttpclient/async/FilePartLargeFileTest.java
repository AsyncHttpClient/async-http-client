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

import java.io.File;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClient.BoundRequestBuilder;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FilePart;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

public abstract class FilePartLargeFileTest extends AbstractBasicTest {

    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testPutImageFile() throws Exception {
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100 * 6000).build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        try {
            BoundRequestBuilder rb = client.preparePut(getTargetUrl());

            rb.addBodyPart(new FilePart("test", LARGE_IMAGE_FILE, "application/octet-stream", "UTF-8"));

            Response response = rb.execute().get();
            Assert.assertEquals(200, response.getStatusCode());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testPutLargeTextFile() throws Exception {
        long repeats = (1024 * 1024 / PATTERN_BYTES.length) + 1;
        File largeFile = createTempFile(PATTERN_BYTES, (int) repeats);

        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            BoundRequestBuilder rb = client.preparePut(getTargetUrl());

            rb.addBodyPart(new FilePart("test", largeFile, "application/octet-stream", "UTF-8"));

            Response response = rb.execute().get();
            Assert.assertEquals(200, response.getStatusCode());
        } finally {
            client.close();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            public void handle(String arg0, Request arg1, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {

                ServletInputStream in = req.getInputStream();
                byte[] b = new byte[8192];

                int count = -1;
                int total = 0;
                while ((count = in.read(b)) != -1) {
                    b = new byte[8192];
                    total += count;
                }
                resp.setStatus(200);
                resp.addHeader("X-TRANFERED", String.valueOf(total));
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                arg1.setHandled(true);
            }
        };
    }
}
