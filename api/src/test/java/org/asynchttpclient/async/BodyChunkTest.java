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
package org.asynchttpclient.async;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.generators.InputStreamBodyGenerator;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;

public abstract class BodyChunkTest extends AbstractBasicTest {

    private static final String MY_MESSAGE = "my message";

    @Test(groups = { "standalone", "default_provider" })
    public void negativeContentTypeTest() throws Exception {

        AsyncHttpClientConfig.Builder confbuilder = new AsyncHttpClientConfig.Builder();
        confbuilder = confbuilder.setConnectionTimeoutInMs(100);
        confbuilder = confbuilder.setMaximumConnectionsTotal(50);
        confbuilder = confbuilder.setRequestTimeoutInMs(5 * 60 * 1000); // 5 minutes

        // Create client
        AsyncHttpClient client = getAsyncHttpClient(confbuilder.build());
        try {

            RequestBuilder requestBuilder = new RequestBuilder("POST").setUrl(getTargetUrl()).setHeader("Content-Type", "message/rfc822");

            requestBuilder.setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())));

            Future<Response> future = client.executeRequest(requestBuilder.build());

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBody(), MY_MESSAGE);
        } finally {
            client.close();
        }
    }
}
