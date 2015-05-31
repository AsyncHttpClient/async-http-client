/*
 * Copyright 2013 Ray Tsang
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient.extras.jdeferred;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.Response;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;

import java.io.IOException;

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
            public AsyncHandler.State onContentWriteProgress(long amount, long current, long total) {
                AsyncHttpDeferredObject.this.notify(new ContentWriteProgress(amount, current, total));
                return super.onContentWriteProgress(amount, current, total);
            }

            @Override
            public AsyncHandler.State onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                AsyncHttpDeferredObject.this.notify(new HttpResponseBodyPartProgress(content));
                return super.onBodyPartReceived(content);
            }
        });
    }

    public static Promise<Response, Throwable, HttpProgress> promise(final BoundRequestBuilder builder) throws IOException {
        return new AsyncHttpDeferredObject(builder).promise();
    }
}
