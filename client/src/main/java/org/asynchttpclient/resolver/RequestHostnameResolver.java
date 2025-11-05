/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.resolver;

import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Hostname resolver that coordinates DNS resolution with AsyncHandler callbacks.
 * <p>
 * This singleton resolver wraps Netty's NameResolver and provides hooks for
 * AsyncHandlers to be notified of resolution attempts, successes, and failures.
 * It converts resolved InetAddresses to InetSocketAddresses with the appropriate port.
 * </p>
 */
public enum RequestHostnameResolver {

  /** Singleton instance */
  INSTANCE;

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestHostnameResolver.class);

  /**
   * Resolves a hostname to a list of socket addresses, notifying the handler of progress.
   * <p>
   * This method performs asynchronous DNS resolution using the provided name resolver,
   * and invokes the appropriate callbacks on the async handler for resolution attempts,
   * successes, and failures.
   * </p>
   *
   * @param nameResolver the Netty name resolver to use for DNS resolution
   * @param unresolvedAddress the unresolved socket address containing the hostname and port
   * @param asyncHandler the handler to notify of resolution events
   * @return a future that will be completed with the list of resolved socket addresses,
   *         or failed with an exception if resolution fails
   */
  public Future<List<InetSocketAddress>> resolve(NameResolver<InetAddress> nameResolver, InetSocketAddress unresolvedAddress, AsyncHandler<?> asyncHandler) {

    final String hostname = unresolvedAddress.getHostString();
    final int port = unresolvedAddress.getPort();
    final Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();

    try {
      asyncHandler.onHostnameResolutionAttempt(hostname);
    } catch (Exception e) {
      LOGGER.error("onHostnameResolutionAttempt crashed", e);
      promise.tryFailure(e);
      return promise;
    }

    final Future<List<InetAddress>> whenResolved = nameResolver.resolveAll(hostname);

    whenResolved.addListener(new SimpleFutureListener<List<InetAddress>>() {

      @Override
      protected void onSuccess(List<InetAddress> value) {
        ArrayList<InetSocketAddress> socketAddresses = new ArrayList<>(value.size());
        for (InetAddress a : value) {
          socketAddresses.add(new InetSocketAddress(a, port));
        }
        try {
          asyncHandler.onHostnameResolutionSuccess(hostname, socketAddresses);
        } catch (Exception e) {
          LOGGER.error("onHostnameResolutionSuccess crashed", e);
          promise.tryFailure(e);
          return;
        }
        promise.trySuccess(socketAddresses);
      }

      @Override
      protected void onFailure(Throwable t) {
        try {
          asyncHandler.onHostnameResolutionFailure(hostname, t);
        } catch (Exception e) {
          LOGGER.error("onHostnameResolutionFailure crashed", e);
          promise.tryFailure(e);
          return;
        }
        promise.tryFailure(t);
      }
    });

    return promise;
  }
}
