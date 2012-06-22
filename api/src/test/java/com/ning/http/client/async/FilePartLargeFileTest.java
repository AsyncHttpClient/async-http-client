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
import com.ning.http.client.FilePart;
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
import java.net.URL;
import java.util.UUID;

import static org.testng.FileAssert.fail;

public abstract class FilePartLargeFileTest
        extends AbstractBasicTest {

    private File largeFile;

    @Test(groups = {"standalone", "default_provider"}, enabled = true)
    public void testPutImageFile()
            throws Exception {
        largeFile = getTestFile();
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100 * 6000).build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        BoundRequestBuilder rb = client.preparePut(getTargetUrl());

        rb.addBodyPart(new FilePart("test", largeFile, "application/octet-stream" , "UTF-8"));

        Response response = rb.execute().get();
        Assert.assertEquals(200, response.getStatusCode());

        client.close();
    }

    @Test(groups = {"standalone", "default_provider"}, enabled = true)
    public void testPutLargeTextFile()
            throws Exception {
        byte[] bytes = "RatherLargeFileRatherLargeFileRatherLargeFileRatherLargeFile".getBytes("UTF-16");
        long repeats = (1024 * 1024 / bytes.length) + 1;
        largeFile = createTempFile(bytes, (int) repeats);

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        BoundRequestBuilder rb = client.preparePut(getTargetUrl());

        rb.addBodyPart(new FilePart("test", largeFile, "application/octet-stream" , "UTF-8"));

        Response response = rb.execute().get();
        Assert.assertEquals(200, response.getStatusCode());
        client.close();
    }

    private static File getTestFile() {
        String testResource1 = "300k.png";

        File testResource1File = null;
        try {
            ClassLoader cl = ChunkingTest.class.getClassLoader();
            URL url = cl.getResource(testResource1);
            testResource1File = new File(url.toURI());
        } catch (Throwable e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource1);
        }

        return testResource1File;
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
                byte[] b = new byte[8192];

                int count = -1;
                int total = 0;
                while ((count = in.read(b)) != -1) {
                    b = new byte[8192];
                    total += count;
                }
                System.err.println("consumed " + total + " bytes.");

                resp.setStatus(200);
                resp.addHeader("X-TRANFERED", String.valueOf(total));
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
