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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Response;
import org.testng.annotations.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Tests case where response doesn't have body.
 *
 * @author Hubert Iwaniuk
 */
public abstract class ListenableFutureTest extends AbstractBasicTest {

    @Test(groups = {"standalone", "default_provider"})
    public void testPutEmptyBody() throws Throwable {
        final AtomicBoolean executed = new AtomicBoolean(false);
        AsyncHttpClient ahc = getAsyncHttpClient(null);
        Response response = ((ListenableFuture<Response>)ahc.prepareGet(getTargetUrl()).execute()).addListener(new Runnable(){


            public void run() {
                executed.set(true);
            }
        }, Executors.newFixedThreadPool(1)).get();

        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);
        assertTrue(executed.get());
        ahc.close();
    }
}
