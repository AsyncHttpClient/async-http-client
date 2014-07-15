/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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

package com.ning.http.client.async.grizzly;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.BodyDeferringAsyncHandlerTest;
import com.ning.http.client.async.ProviderUtil;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class GrizzlyBodyDeferringAsyncHandlerTest extends BodyDeferringAsyncHandlerTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.grizzlyProvider(config);
    }

    @Test(enabled = false)
    public void deferredInputStreamTrick() throws IOException, ExecutionException, TimeoutException, InterruptedException {
    }

    @Test(enabled = false)
    public void deferredSimple() throws IOException, ExecutionException, TimeoutException, InterruptedException {
    }

    @Test(enabled = false)
    public void deferredInputStreamTrickWithFailure() throws IOException, ExecutionException, TimeoutException, InterruptedException {
    }

}
