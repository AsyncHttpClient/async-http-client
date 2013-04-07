package com.ning.http.client.extra;

import java.io.IOException;

import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;

import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.Response;

public class AsyncHttpDeferredObject extends DeferredObject<Response, Throwable, HttpProgress> {
	public AsyncHttpDeferredObject(BoundRequestBuilder builder) throws IOException {
		builder.execute(new AsyncCompletionHandler<Void>() {
			@Override
			public Void onCompleted(Response response) throws Exception {
				AsyncHttpDeferredObject.this.resolve(response);
				return null;
			}
			
			@Override
			public void onThrowable(Throwable t) {
				AsyncHttpDeferredObject.this.reject(t);
			}
			
			@Override
			public com.ning.http.client.AsyncHandler.STATE onContentWriteProgress(
					long amount, long current, long total) {
				AsyncHttpDeferredObject.this.notify(new ContentWriteProgress(amount, current, total));
				return super.onContentWriteProgress(amount, current, total);
			}
			
			@Override
			public com.ning.http.client.AsyncHandler.STATE onBodyPartReceived(
					HttpResponseBodyPart content) throws Exception {
				AsyncHttpDeferredObject.this.notify(new HttpResponseBodyPartProgress(content));
				return super.onBodyPartReceived(content);
			}
		});
	}
	
	public static Promise<Response, Throwable, HttpProgress> promise(final BoundRequestBuilder builder) throws IOException {
		return new AsyncHttpDeferredObject(builder).promise();
	}
}
