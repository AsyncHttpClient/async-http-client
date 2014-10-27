package com.ning.http.client.async;

import org.testng.Assert;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EventCollectingHandler extends AsyncCompletionHandlerBase implements AsyncHandlerExtensions {
    public Queue<String> firedEvents = new ConcurrentLinkedQueue<String>();
    private CountDownLatch completionLatch = new CountDownLatch(1);

    public void waitForCompletion() throws InterruptedException {
        if (!completionLatch.await(AbstractBasicTest.TIMEOUT, TimeUnit.SECONDS)) {
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
    public STATE onStatusReceived(HttpResponseStatus status) throws Exception {
        firedEvents.add("StatusReceived");
        return super.onStatusReceived(status);
    }

    @Override
    public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        firedEvents.add("HeadersReceived");
        return super.onHeadersReceived(headers);
    }

    @Override
    public STATE onHeaderWriteCompleted() {
        firedEvents.add("HeaderWriteCompleted");
        return super.onHeaderWriteCompleted();
    }

    @Override
    public STATE onContentWriteCompleted() {
        firedEvents.add("ContentWriteCompleted");
        return super.onContentWriteCompleted();
    }

    @Override
    public void onOpenConnection() {
        firedEvents.add("OpenConnection");
    }

    @Override
    public void onConnectionOpen() {
        firedEvents.add("ConnectionOpen");
    }

    @Override
    public void onPoolConnection() {
        firedEvents.add("PoolConnection");
    }

    @Override
    public void onConnectionPooled() {
        firedEvents.add("ConnectionPooled");
    }

    @Override
    public void onSendRequest(Object request) {
        firedEvents.add("SendRequest");
    }

    @Override
    public void onRetry() {
        firedEvents.add("Retry");
    }

    @Override
    public void onDnsResolved() {
        firedEvents.add("DnsResolved");
    }

    @Override
    public void onSslHandshakeCompleted() {
        firedEvents.add("SslHandshakeCompleted");
    }
}
