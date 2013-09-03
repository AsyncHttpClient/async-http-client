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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClient.BoundRequestBuilder;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author Benjamin Hanzelmann
 */
public abstract class PutLargeFileTest extends AbstractBasicTest {

    private static final File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc-tests-" + UUID.randomUUID().toString().substring(0, 8));
    private static final byte[] PATTERN_BYTES = "RatherLargeFileRatherLargeFileRatherLargeFileRatherLargeFile".getBytes(Charset.forName("UTF-16"));

    static {
        TMP.mkdirs();
        TMP.deleteOnExit();
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testPutLargeFile() throws Exception {

        long repeats = (1024 * 1024 * 100 / PATTERN_BYTES.length) + 1;
        File file = createTempFile(PATTERN_BYTES, (int) repeats);
        long expectedFileSize = PATTERN_BYTES.length * repeats;
        Assert.assertEquals(expectedFileSize, file.length(), "Invalid file length");

        int timeout = (int) (repeats / 1000);

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(timeout).build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        try {
            BoundRequestBuilder rb = client.preparePut(getTargetUrl());

            rb.setBody(file);

            Response response = rb.execute().get();
            Assert.assertEquals(200, response.getStatusCode());
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testPutSmallFile() throws Exception {

        long repeats = (1024 / PATTERN_BYTES.length) + 1;
        File file = createTempFile(PATTERN_BYTES, (int) repeats);
        long expectedFileSize = PATTERN_BYTES.length * repeats;
        Assert.assertEquals(expectedFileSize, file.length(), "Invalid file length");

        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            BoundRequestBuilder rb = client.preparePut(getTargetUrl());

            rb.setBody(file);

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

                resp.setStatus(200);
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                arg1.setHandled(true);
            }
        };
    }

    public static File createTempFile(byte[] pattern, int repeat) throws IOException {
        File tmpFile = File.createTempFile("tmpfile-", ".data", TMP);
        tmpFile.deleteOnExit();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmpFile);
            for (int i = 0; i < repeat; i++) {
                out.write(pattern);
            }
        } finally {
            if (out != null) {
                out.close();
            }
        }

        return tmpFile;
    }
}
