/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.retrofit;

import lombok.*;
import okhttp3.Call;
import okhttp3.Request;
import org.asynchttpclient.AsyncHttpClient;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.asynchttpclient.extras.retrofit.AsyncHttpClientCall.runConsumers;

/**
 * {@link AsyncHttpClient} implementation of <a href="http://square.github.io/retrofit/">Retrofit2</a>
 * {@link Call.Factory}.
 */
@Value
@Builder(toBuilder = true)
public class AsyncHttpClientCallFactory implements Call.Factory {
  /**
   * Supplier of {@link AsyncHttpClient}.
   */
  @NonNull
  @Getter(AccessLevel.NONE)
  Supplier<AsyncHttpClient> httpClientSupplier;

  /**
   * List of {@link Call} builder customizers that are invoked just before creating it.
   */
  @Singular("callCustomizer")
  @Getter(AccessLevel.PACKAGE)
  List<Consumer<AsyncHttpClientCall.AsyncHttpClientCallBuilder>> callCustomizers;

  @Override
  public Call newCall(Request request) {
    val callBuilder = AsyncHttpClientCall.builder()
            .httpClientSupplier(httpClientSupplier)
            .request(request);

    // customize builder before creating a call
    runConsumers(this.callCustomizers, callBuilder);

    // create a call
    return callBuilder.build();
  }

  /**
   * Returns {@link AsyncHttpClient} from {@link #httpClientSupplier}.
   *
   * @return http client.
   */
  AsyncHttpClient getHttpClient() {
    return httpClientSupplier.get();
  }

  /**
   * Builder for {@link AsyncHttpClientCallFactory}.
   */
  public static class AsyncHttpClientCallFactoryBuilder {
    /**
     * {@link AsyncHttpClient} supplier that returns http client to be used to execute HTTP requests.
     */
    private Supplier<AsyncHttpClient> httpClientSupplier;

    /**
     * Sets concrete http client to be used by the factory to execute HTTP requests. Invocation of this method
     * overrides any previous http client supplier set by {@link #httpClientSupplier(Supplier)}!
     *
     * @param httpClient http client
     * @return reference to itself.
     * @see #httpClientSupplier(Supplier)
     */
    public AsyncHttpClientCallFactoryBuilder httpClient(@NonNull AsyncHttpClient httpClient) {
      return httpClientSupplier(() -> httpClient);
    }
  }
}