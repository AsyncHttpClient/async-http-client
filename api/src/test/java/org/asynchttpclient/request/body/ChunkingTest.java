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

import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_FILE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.FileAssert.fail;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.request.Request;
import org.asynchttpclient.request.RequestBuilder;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.response.Response;
import org.testng.annotations.Test;

/**
 * Test that the url fetcher is able to communicate via a proxy
 * 
 * @author dominict
 */
abstract public class ChunkingTest extends AbstractBasicTest {
    // So we can just test the returned data is the image,
    // and doesn't contain the chunked delimeters.
    @Test()
    public void testBufferLargerThanFile() throws Throwable {
        doTest(new BufferedInputStream(new FileInputStream(LARGE_IMAGE_FILE), 400000));
    }

    @Test()
    public void testBufferSmallThanFile() throws Throwable {
        doTest(new BufferedInputStream(new FileInputStream(LARGE_IMAGE_FILE)));
    }

    @Test()
    public void testDirectFile() throws Throwable {
        doTest(new FileInputStream(LARGE_IMAGE_FILE));
    }

    public void doTest(InputStream is) throws Throwable {
        AsyncHttpClientConfig.Builder bc = new AsyncHttpClientConfig.Builder()//
        .setAllowPoolingConnections(true)//
        .setMaxConnectionsPerHost(1)//
        .setMaxConnections(1)//
        .setConnectTimeout(1000)//
        .setRequestTimeout(1000)
        .setFollowRedirect(true);

        try (AsyncHttpClient c = getAsyncHttpClient(bc.build())) {

            RequestBuilder builder = new RequestBuilder("POST");
            builder.setUrl(getTargetUrl());
            builder.setBody(new InputStreamBodyGenerator(is));

            Request r = builder.build();

            Response response = c.executeRequest(r).get();
            if (500 == response.getStatusCode()) {
                StringBuilder sb = new StringBuilder();
                sb.append("==============\n");
                sb.append("500 response from call\n");
                sb.append("Headers:" + response.getHeaders() + "\n");
                sb.append("==============\n");
                logger.debug(sb.toString());
                assertEquals(response.getStatusCode(), 500, "Should have 500 status code");
                assertTrue(response.getHeader("X-Exception").contains("invalid.chunk.length"), "Should have failed due to chunking");
                fail("HARD Failing the test due to provided InputStreamBodyGenerator, chunking incorrectly:" + response.getHeader("X-Exception"));
            } else {
                assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
            }
        }
    }
}
