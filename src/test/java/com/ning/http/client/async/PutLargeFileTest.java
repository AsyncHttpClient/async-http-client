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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * @author Benjamin Hanzelmann
 */
public abstract class PutLargeFileTest
        extends AbstractBasicTest {

    private File largeFile;

    @Test(groups = {"standalone", "default_provider"}, enabled = true)
    public void testPutLargeFile()
            throws Exception {
        byte[] bytes = "RatherLargeFileRatherLargeFileRatherLargeFileRatherLargeFile".getBytes("UTF-16");
        long repeats = (1024 * 1024 * 100 / bytes.length) + 1;
        largeFile = createTempFile(bytes, (int) repeats);
        int timeout = (int) (largeFile.length() / 1000);
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setConnectionTimeoutInMs(timeout).build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        BoundRequestBuilder rb = client.preparePut(getTargetUrl());

        rb.setBody(largeFile);

        Response response = rb.execute().get();
        Assert.assertEquals(200, response.getStatusCode());
        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testPutSmallFile()
            throws Exception {
        byte[] bytes = "RatherLargeFileRatherLargeFileRatherLargeFileRatherLargeFile".getBytes("UTF-16");
        long repeats = (1024 / bytes.length) + 1;
//        int timeout = (5000);
        largeFile = createTempFile(bytes, (int) repeats);

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        BoundRequestBuilder rb = client.preparePut(getTargetUrl());

        rb.setBody(largeFile);

        Response response = rb.execute().get();
        Assert.assertEquals(200, response.getStatusCode());
        client.close();
    }

    @AfterMethod
    public void after() {
        largeFile.delete();
    }

    @Override
    public AbstractHandler configureHandler()
            throws Exception {
        return new AbstractHandler() {

            public void handle(String arg0, Request arg1, HttpServletRequest req, HttpServletResponse resp)
                    throws IOException, ServletException {

                ServletInputStream in = req.getInputStream();
                byte[] b = new byte[8092];

                int count = -1;
                int total = 0;
                while ((count = in.read(b)) != -1) {
                    total += count;
                }

                System.err.println("consumed " + total + " bytes.");

                resp.setStatus(200);
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                arg1.setHandled(true);

            }
        };
    }

    private static final File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc-tests-"
            + UUID.randomUUID().toString().substring(0, 8));

    public static File createTempFile(byte[] pattern, int repeat)
            throws IOException {
        TMP.mkdirs();
        TMP.deleteOnExit();
        File tmpFile = File.createTempFile("tmpfile-", ".data", TMP);
        tmpFile.deleteOnExit();
        write(pattern, repeat, tmpFile);

        return tmpFile;
    }

    public static void write(byte[] pattern, int repeat, File file)
            throws IOException {
        file.deleteOnExit();
        file.getParentFile().mkdirs();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            for (int i = 0; i < repeat; i++) {
                out.write(pattern);
            }
        }
        finally {
            if (out != null) {
                out.close();
            }
        }
    }

}
