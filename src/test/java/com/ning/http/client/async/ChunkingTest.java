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
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Random;

import static org.testng.Assert.*;
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
        } catch (Throwable e) {
            LARGE_IMAGE_BYTES = new byte[265495];
            Random x = new Random();
            x.nextBytes(LARGE_IMAGE_BYTES);
        }
    }

    /**
     * Tests that the custom chunked stream result in success and content returned that is unchunked
     */
    @Test()
    public void testBufferLargerThanFile() throws Throwable {
        doTest(new BufferedInputStream(new FileInputStream(getTestFile()), 400000));
    }

    @Test()
    public void testBufferSmallThanFile() throws Throwable {
        doTest(new BufferedInputStream(new FileInputStream(getTestFile())));
    }

    @Test()
    public void testDirectFile() throws Throwable {
        doTest(new FileInputStream(getTestFile()));
    }

    public void doTest(InputStream is) throws Throwable {
        AsyncHttpClientConfig.Builder bc = new AsyncHttpClientConfig.Builder()//
                .setAllowPoolingConnections(true)//
                .setMaxConnectionsPerHost(1)//
                .setMaxConnections(1)//
                .setConnectTimeout(1000)//
                .setRequestTimeout(1000)//
                .setFollowRedirect(true);

        AsyncHttpClient client = getAsyncHttpClient(bc.build());
        try {
            RequestBuilder builder = new RequestBuilder("POST");
            builder.setUrl(getTargetUrl());
            // made buff in stream big enough to mark.
            builder.setBody(new InputStreamBodyGenerator(is));

            ListenableFuture<Response> response = client.executeRequest(builder.build());
            Response res = response.get();
            assertNotNull(res.getResponseBodyAsStream());
            if (500 == res.getStatusCode()) {
                assertEquals(res.getStatusCode(), 500, "Should have 500 status code");
                assertTrue(res.getHeader("X-Exception").contains("invalid.chunk.length"), "Should have failed due to chunking");
                fail("HARD Failing the test due to provided InputStreamBodyGenerator, chunking incorrectly:" + res.getHeader("X-Exception"));
            } else {
                assertEquals(readInputStreamToBytes(res.getResponseBodyAsStream()), LARGE_IMAGE_BYTES);
            }
        } finally {
            if (client != null)
                client.close();
        }
    }
    
    
    private byte[] readInputStreamToBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            int nRead;
            byte[] tmp = new byte[8192];

            while ((nRead = stream.read(tmp, 0, tmp.length)) != -1) {
                buffer.write(tmp, 0, nRead);
            }

            buffer.flush();
            return buffer.toByteArray();

        } finally {
            try {
                stream.close();
            } catch (Exception e2) {
            }
        }
    }

    private static File getTestFile() throws URISyntaxException {
        String testResource1 = "300k.png";
        URL url = ChunkingTest.class.getClassLoader().getResource(testResource1);
        return new File(url.toURI());
    }
}
