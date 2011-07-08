/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.consumers.AppendableBodyConsumer;
import com.ning.http.client.consumers.OutputStreamBodyConsumer;
import com.ning.http.client.generators.FileBodyGenerator;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import com.ning.http.client.simple.HeaderMap;
import com.ning.http.client.simple.SimpleAHCTransferListener;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;

import static junit.framework.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNotSame;

public abstract class SimpleAsyncHttpClientTest extends AbstractBasicTest {

    private final static String MY_MESSAGE = "my message";

    @Test(groups = { "standalone", "default_provider" })
    public void inpuStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setIdleConnectionInPoolTimeoutInMs(100).setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000).setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();

        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getResponseBody(), MY_MESSAGE);

        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void StringBufferBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setIdleConnectionInPoolTimeoutInMs(100).setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000).setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();

        StringBuilder s = new StringBuilder();
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(s.toString(), MY_MESSAGE);

        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void ByteArrayOutputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setIdleConnectionInPoolTimeoutInMs(100).setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000).setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();

        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(o.toString(), MY_MESSAGE);

        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void RequestByteArrayOutputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).build();

        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(o.toString(), MY_MESSAGE);

        client.close();
    }

    /**
     * See https://issues.sonatype.org/browse/AHC-5
     */
    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testPutZeroBytesFileTest() throws Throwable {
        System.err.println("setting up client");
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setIdleConnectionInPoolTimeoutInMs(100).setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 1000).setUrl(getTargetUrl() + "/testPutZeroBytesFileTest.txt").setHeader("Content-Type", "text/plain").build();

        File tmpfile = File.createTempFile("testPutZeroBytesFile", ".tmp");
        tmpfile.deleteOnExit();

        Future<Response> future = client.put(new FileBodyGenerator(tmpfile));

        System.out.println("waiting for response");
        Response response = future.get();

        tmpfile.delete();

        assertEquals(response.getStatusCode(), 200);

        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testDerive() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().build();
        SimpleAsyncHttpClient derived = client.derive().build();

        assertNotSame(derived, client);
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testDeriveOverrideURL() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl("http://invalid.url").build();
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);

        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes()));
        OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(o);

        SimpleAsyncHttpClient derived = client.derive().setUrl(getTargetUrl()).build();

        Future<Response> future = derived.post(generator, consumer);

        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(o.toString(), MY_MESSAGE);

        client.close();
        derived.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testSimpleTransferListener() throws Exception {

        SimpleAHCTransferListener listener = new SimpleAHCTransferListener() {

            public void onStatus(String url, int statusCode, String statusText) {
                assertEquals(statusCode, 200);
                assertEquals(url, getTargetUrl());
            }

            public void onHeaders(String url, HeaderMap headers) {
                assertEquals(url, getTargetUrl());
                assertNotNull(headers);
                assertTrue(!headers.isEmpty());
                assertEquals(headers.getFirstValue("X-Custom"), "custom");
            }

            public void onCompleted(String url, int statusCode, String statusText) {
                assertEquals(statusCode, 200);
                assertEquals(url, getTargetUrl());
            }

            public void onBytesSent(String url, long amount, long current, long total) {
                assertEquals(url, getTargetUrl());
                assertEquals(total, MY_MESSAGE.getBytes().length);
            }

            public void onBytesReceived(String url, long amount, long current, long total) {
                assertEquals(url, getTargetUrl());
                assertEquals(total, -1);
            }
        };

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).setHeader("Custom", "custom").setListener(listener).build();
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);

        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes()));
        OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(o);

        Future<Response> future = client.post(generator, consumer);

        Response response = future.get();
        client.close();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(o.toString(), MY_MESSAGE);
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testNullUrl() throws Exception {
        try {
            new SimpleAsyncHttpClient.Builder().build().derive().build();
            assertTrue(true);
        } catch (NullPointerException ex) {
            fail();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testCloseDerivedValidMaster() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).build();
        SimpleAsyncHttpClient derived = client.derive().build();

        derived.get().get();

        derived.close();

        Response response = client.get().get();

        assertEquals(response.getStatusCode(), 200);
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testCloseMasterInvalidDerived() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).build();
        SimpleAsyncHttpClient derived = client.derive().build();

        client.close();

        try {
            derived.get().get();
            fail("Expected closed AHC");
        } catch (IOException e) {
            // expected
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testMultiPartPut() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl() + "/multipart").build();
        
        Response response = client.put(new ByteArrayPart("baPart", "fileName", "testMultiPart".getBytes("utf-8"), "application/test", "utf-8")).get();
        
        String body = response.getResponseBody();
        String contentType = response.getHeader("X-Content-Type");
        
        assertTrue(contentType.contains("multipart/form-data"));
        
        String boundary = contentType.substring(contentType.lastIndexOf("=") + 1);
        
        assertTrue(body.startsWith("--" + boundary));
        assertTrue(body.trim().endsWith("--" + boundary + "--"));
        assertTrue(body.contains("Content-Disposition:"));
        assertTrue(body.contains("Content-Type: application/test"));
        assertTrue(body.contains("name=\"baPart"));
        assertTrue(body.contains("filename=\"fileName"));
        
    }
    
    @Test(groups = { "standalone", "default_provider" })
    public void testMultiPartPost() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl() + "/multipart").build();
        
        Response response = client.post(new ByteArrayPart("baPart", "fileName", "testMultiPart".getBytes("utf-8"), "application/test", "utf-8")).get();
        
        String body = response.getResponseBody();
        String contentType = response.getHeader("X-Content-Type");
        
        assertTrue(contentType.contains("multipart/form-data"));
        
        String boundary = contentType.substring(contentType.lastIndexOf("=") + 1);
        
        assertTrue(body.startsWith("--" + boundary));
        assertTrue(body.trim().endsWith("--" + boundary + "--"));
        assertTrue(body.contains("Content-Disposition:"));
        assertTrue(body.contains("Content-Type: application/test"));
        assertTrue(body.contains("name=\"baPart"));
        assertTrue(body.contains("filename=\"fileName"));
        
    }

}
