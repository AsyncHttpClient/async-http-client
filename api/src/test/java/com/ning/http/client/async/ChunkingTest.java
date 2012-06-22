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
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import org.testng.annotations.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Random;

import static org.testng.Assert.assertNotNull;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.FileAssert.fail;

/**
 * Test that the url fetcher is able to communicate via a proxy
 *
 * @author dominict
 */
abstract public class ChunkingTest extends AbstractBasicTest {
    // So we can just test the returned data is the image,
    // and doesn't contain the chunked delimeters.
    public static byte[] LARGE_IMAGE_BYTES;

    static {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream instream = null;
        try {
            ClassLoader cl = ChunkingTest.class.getClassLoader();
            // override system properties
            URL url = cl.getResource("300k.png");
            File sourceFile = new File(url.toURI());
            instream = new FileInputStream(sourceFile);
            byte[] buf = new byte[8092];
            int len = 0;
            while ((len = instream.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            LARGE_IMAGE_BYTES = baos.toByteArray();
        }
        catch (Throwable e) {
            LARGE_IMAGE_BYTES = new byte[265495];
            Random x = new Random();
            x.nextBytes(LARGE_IMAGE_BYTES);
        }
    }

    /**
     * Tests that the custom chunked stream result in success and
     * content returned that is unchunked
     */
    @Test()
    public void testCustomChunking() throws Throwable {
        doTest(true);
    }


    private void doTest(boolean customChunkedInputStream) throws Exception {
        AsyncHttpClient c = null;
        try {
            AsyncHttpClientConfig.Builder bc =
                    new AsyncHttpClientConfig.Builder();

            bc.setAllowPoolingConnection(true);
            bc.setMaximumConnectionsPerHost(1);
            bc.setMaximumConnectionsTotal(1);
            bc.setConnectionTimeoutInMs(1000);
            bc.setRequestTimeoutInMs(1000);
            bc.setFollowRedirects(true);


            c = getAsyncHttpClient(bc.build());

            RequestBuilder builder = new RequestBuilder("POST");
            builder.setUrl(getTargetUrl());
            if (customChunkedInputStream) {
                // made buff in stream big enough to mark.
                builder.setBody(new InputStreamBodyGenerator(new BufferedInputStream(new FileInputStream(getTestFile()), 400000)));
            } else {
                // made buff in stream big enough to mark.
                builder.setBody(new InputStreamBodyGenerator(new BufferedInputStream(new FileInputStream(getTestFile()), 400000)));
            }
            com.ning.http.client.Request r = builder.build();
            Response res = null;

            try {
                ListenableFuture<Response> response = c.executeRequest(r);
                res = response.get();
                assertNotNull(res.getResponseBodyAsStream());
                if (500 == res.getStatusCode()) {
                    System.out.println("==============");
                    System.out.println("500 response from call");
                    System.out.println("Headers:" + res.getHeaders());
                    System.out.println("==============");
                    System.out.flush();
                    assertEquals("Should have 500 status code", 500, res.getStatusCode());
                    assertTrue("Should have failed due to chunking", res.getHeader("X-Exception").contains("invalid.chunk.length"));
                    fail("HARD Failing the test due to provided InputStreamBodyGenerator, chunking incorrectly:" + res.getHeader("X-Exception"));
                } else {
                    assertEquals(LARGE_IMAGE_BYTES, readInputStreamToBytes(res.getResponseBodyAsStream()));
                }
            }
            catch (Exception e) {

                fail("Exception Thrown:" + e.getMessage());
            }
        }
        finally {
            if (c != null) c.close();
        }
    }

    private byte[] readInputStreamToBytes(InputStream stream) {
        byte[] data = new byte[0];
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            int nRead;
            byte[] tmp = new byte[8192];

            while ((nRead = stream.read(tmp, 0, tmp.length)) != -1) {
                buffer.write(tmp, 0, nRead);
            }
            buffer.flush();
            data = buffer.toByteArray();
        }
        catch (Exception e) {

        }
        finally {
            try {
                stream.close();
            } catch (Exception e2) {
            }
            return data;
        }
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

}
