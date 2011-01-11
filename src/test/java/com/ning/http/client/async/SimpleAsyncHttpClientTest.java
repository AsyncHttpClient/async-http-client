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

import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.consumers.OutputStreamBodyConsumer;
import com.ning.http.client.consumers.StringBuilderBodyConsumer;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;

public abstract class SimpleAsyncHttpClientTest extends AbstractBasicTest {

    private final static String MY_MESSAGE = "my message";

    @Test(groups = {"standalone", "default_provider"})
    public void inpuStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setIdleConnectionInPoolTimeoutInMs(100)
                .setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000)
                .setUrl(getTargetUrl())
                .setHeader("Content-Type", "text/html").build();

        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getResponseBody(), MY_MESSAGE);

        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void StringBufferBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setIdleConnectionInPoolTimeoutInMs(100)
                .setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000)
                .setUrl(getTargetUrl())
                .setHeader("Content-Type", "text/html").build();

        StringBuilder s = new StringBuilder();
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new StringBuilderBodyConsumer(s));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(s.toString(), MY_MESSAGE);

        client.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void ByteArrayOutputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
                .setIdleConnectionInPoolTimeoutInMs(100)
                .setMaximumConnectionsTotal(50)
                .setRequestTimeoutInMs(5 * 60 * 1000)
                .setUrl(getTargetUrl())
                .setHeader("Content-Type", "text/html").build();

        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(o.toString(), MY_MESSAGE);

        client.close();
    }
}
