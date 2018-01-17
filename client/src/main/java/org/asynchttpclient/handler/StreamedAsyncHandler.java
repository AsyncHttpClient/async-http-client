/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.handler;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.reactivestreams.Publisher;

/**
 * AsyncHandler that uses reactive streams to handle the request.
 */
public interface StreamedAsyncHandler<T> extends AsyncHandler<T> {

  /**
   * Called when the body is received. May not be called if there's no body.
   *
   * @param publisher The publisher of response body parts.
   * @return Whether to continue or abort.
   */
  State onStream(Publisher<HttpResponseBodyPart> publisher);
}
