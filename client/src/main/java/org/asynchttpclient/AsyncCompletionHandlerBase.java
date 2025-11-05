/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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
 *
 */
package org.asynchttpclient;


/**
 * A simple {@link AsyncCompletionHandler} implementation that returns the complete {@link Response}.
 * <p>
 * This is the simplest way to execute an HTTP request and get the complete response back.
 * The response body will be fully buffered in memory.
 * </p>
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * Future<Response> future = client.prepareGet("http://example.com")
 *     .execute(new AsyncCompletionHandlerBase());
 * Response response = future.get();
 * System.out.println(response.getResponseBody());
 * }</pre>
 * <p>
 * Note: You can also use {@link AsyncHttpClient#executeRequest(Request)} directly,
 * which uses this handler internally.
 * </p>
 */
public class AsyncCompletionHandlerBase extends AsyncCompletionHandler<Response> {
  /**
   * Returns the complete response as-is.
   *
   * @param response the fully assembled HTTP response
   * @return the same response object
   * @throws Exception if an error occurs during processing
   */
  @Override
  public Response onCompleted(Response response) throws Exception {
    return response;
  }
}
