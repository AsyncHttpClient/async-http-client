/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.filter;

/**
 * A Filter interface that gets invoked before making an actual request.
 */
public interface RequestFilter {

  /**
   * An {@link org.asynchttpclient.AsyncHttpClient} will invoke {@link RequestFilter#filter} and will use the
   * returned {@link FilterContext#getRequest()} and {@link FilterContext#getAsyncHandler()} to continue the request
   * processing.
   *
   * @param ctx a {@link FilterContext}
   * @param <T> the handler result type
   * @return {@link FilterContext}. The {@link FilterContext} instance may not the same as the original one.
   * @throws FilterException to interrupt the filter processing.
   */
  <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException;
}
