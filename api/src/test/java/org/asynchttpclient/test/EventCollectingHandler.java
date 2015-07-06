/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.test;

import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.testng.Assert;

public class EventCollectingHandler extends AsyncCompletionHandlerBase implements AsyncHandlerExtensions {
    public Queue<String> firedEvents = new ConcurrentLinkedQueue<>();
    private CountDownLatch completionLatch = new CountDownLatch(1);

    public void waitForCompletion(int timeout, TimeUnit unit) throws InterruptedException {
        if (!completionLatch.await(timeout, unit)) {
            Assert.fail("Timeout out");
        }
    }

    @Override
    public Response onCompleted(Response response) throws Exception {
        firedEvents.add("Completed");
        try {
            return super.onCompleted(response);
        } finally {
            completionLatch.countDown();
        }
    }

    @Override
    public State onStatusReceived(HttpResponseStatus status) throws Exception {
        firedEvents.add("StatusReceived");
        return super.onStatusReceived(status);
    }

    @Override
    public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        firedEvents.add("HeadersReceived");
        return super.onHeadersReceived(headers);
    }

    @Override
    public State onHeadersWritten() {
        firedEvents.add("HeadersWritten");
        return super.onHeadersWritten();
    }

    @Override
    public State onContentWriten() {
        firedEvents.add("ContentWritten");
        return super.onContentWriten();
    }

    @Override
    public void onConnectionOpen() {
        firedEvents.add("ConnectionOpen");
    }

    @Override
    public void onConnectionOpened(Object connection) {
        firedEvents.add("ConnectionOpened");
    }

    @Override
    public void onConnectionPool() {
        firedEvents.add("ConnectionPool");
    }

    @Override
    public void onConnectionPooled(Object connection) {
        firedEvents.add("ConnectionPooled");
    }

    @Override
    public void onConnectionOffer(Object connection) {
        firedEvents.add("ConnectionOffer");
    }
    
    @Override
    public void onRequestSend(Object request) {
        firedEvents.add("RequestSend");
    }

    @Override
    public void onRetry() {
        firedEvents.add("Retry");
    }

    @Override
    public void onDnsResolved(InetAddress remoteAddress) {
        firedEvents.add("DnsResolved");
    }

    @Override
    public void onSslHandshakeCompleted() {
        firedEvents.add("SslHandshakeCompleted");
    }
}
