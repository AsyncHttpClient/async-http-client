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
 */
package org.asynchttpclient;

import static org.asynchttpclient.util.HttpConstants.Methods.GET;

/**
 * Builder for a {@link Request}. Warning: mutable and not thread-safe! Beware that it holds a reference to the Request instance it builds, so modifying the builder will modify the
 * request even after it has been built.
 */
public class RequestBuilder extends RequestBuilderBase<RequestBuilder> {

  public RequestBuilder() {
    this(GET);
  }

  public RequestBuilder(String method) {
    this(method, false);
  }

  public RequestBuilder(String method, boolean disableUrlEncoding) {
    super(method, disableUrlEncoding);
  }

  public RequestBuilder(String method, boolean disableUrlEncoding, boolean validateHeaders) {
    super(method, disableUrlEncoding, validateHeaders);
  }

  public RequestBuilder(Request prototype) {
    super(prototype);
  }

  public RequestBuilder(Request prototype, boolean disableUrlEncoding, boolean validateHeaders) {
    super(prototype, disableUrlEncoding, validateHeaders);
  }
}
