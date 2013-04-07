package com.ning.http.client.extra;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.jdeferred.DoneCallback;
import org.jdeferred.ProgressCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DefaultDeferredManager;
import org.jdeferred.multiple.MultipleResults;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;

public class AsyncHttpTest extends TestCase {
	protected DefaultDeferredManager deferredManager;

	protected void setUp() throws Exception {
		super.setUp();
		deferredManager = new DefaultDeferredManager();
	}

	protected void tearDown() throws Exception {
		super.tearDown();
	}

	public void testPromiseAdapter() throws IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicInteger successCount = new AtomicInteger();
		final AtomicInteger progressCount = new AtomicInteger();

		AsyncHttpClient client = new AsyncHttpClient();

		Promise<Response, Throwable, HttpProgress> p1 = AsyncHttpDeferredObject
				.promise(client.prepareGet("http://www.ning.com"));
		p1.done(new DoneCallback<Response>() {
			@Override
			public void onDone(Response response) {
				try {
					Assert.assertEquals(200, response.getStatusCode());
					successCount.incrementAndGet();
				} finally {
					latch.countDown();
				}
			}
		}).progress(new ProgressCallback<HttpProgress>() {

			@Override
			public void onProgress(HttpProgress progress) {
				progressCount.incrementAndGet();
			}
		});

		try {
			latch.await();
			Assert.assertTrue(progressCount.get() > 0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void testMultiplePromiseAdapter() throws IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicInteger successCount = new AtomicInteger();

		AsyncHttpClient client = new AsyncHttpClient();

		Promise<Response, Throwable, HttpProgress> p1 = AsyncHttpDeferredObject
				.promise(client.prepareGet("http://www.ning.com"));
		Promise<Response, Throwable, HttpProgress> p2 = AsyncHttpDeferredObject
				.promise(client.prepareGet("http://www.google.com"));
		AsyncHttpDeferredObject deferredRequest = new AsyncHttpDeferredObject(
				client.prepareGet("http://jdeferred.org"));

		deferredManager.when(p1, p2, deferredRequest).then(
				new DoneCallback<MultipleResults>() {
					@Override
					public void onDone(MultipleResults result) {
						try {
							Assert.assertEquals(3, result.size());
							Assert.assertEquals(200, ((Response) result.get(0)
									.getResult()).getStatusCode());
							Assert.assertEquals(200, ((Response) result.get(1)
									.getResult()).getStatusCode());
							Assert.assertEquals(200, ((Response) result.get(2)
									.getResult()).getStatusCode());
							successCount.incrementAndGet();
						} finally {
							latch.countDown();
						}
					}
				});

		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
