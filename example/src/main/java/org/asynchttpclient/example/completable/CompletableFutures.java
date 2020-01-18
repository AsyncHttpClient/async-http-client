/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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
package org.asynchttpclient.example.completable;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Response;

import java.io.IOException;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

public class CompletableFutures {
  public static void main(String[] args) throws IOException {
    try (AsyncHttpClient asyncHttpClient = asyncHttpClient()) {
      asyncHttpClient
              .prepareGet("http://www.example.com/")
              .execute()
              .toCompletableFuture()
              .thenApply(Response::getResponseBody)
              .thenAccept(System.out::println)
              .join();
    }
//    example of use unix domain socket
//    if (!isWindows()) {
//      // support unix domain socket
//      DefaultAsyncHttpClientConfig.Builder config = config();
//      config.setUnixSocket("/root/server.socket"); // when the unixSocket is set, the useNativeTransport will be true.
//      try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
//        asyncHttpClient
//                .prepareGet("http://www.example.com/")
//                .execute()
//                .toCompletableFuture()
//                .thenApply(Response::getResponseBody)
//                .thenAccept(System.out::println)
//                .join();
//      }
//    }

  }

  private static boolean isWindows(){
    return System.getProperty("os.name").toUpperCase().contains("WINDOW");
  }
}
