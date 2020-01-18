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
package org.asynchttpclient.util;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;

public abstract class ReflectionUtil {

  public static void loadEpollClass() {
    try {
      Class.forName("io.netty.channel.epoll.Epoll");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("The epoll transport is not available");
    }
    if (!Epoll.isAvailable()) {
      throw new IllegalStateException("The epoll transport is not supported");
    }
  }

  public static void loadKQueueClass() {
    try {
      Class.forName("io.netty.channel.kqueue.KQueue");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("The kqueue transport is not available");
    }
    if (!KQueue.isAvailable()) {
      throw new IllegalStateException("The kqueue transport is not supported");
    }
  }
}
