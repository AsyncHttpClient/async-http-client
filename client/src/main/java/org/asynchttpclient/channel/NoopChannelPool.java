/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.channel;

import io.netty.channel.Channel;

import java.util.Collections;
import java.util.Map;
import java.util.function.Predicate;

public enum NoopChannelPool implements ChannelPool {

  INSTANCE;

  @Override
  public boolean offer(Channel channel, Object partitionKey) {
    return false;
  }

  @Override
  public Channel poll(Object partitionKey) {
    return null;
  }

  @Override
  public boolean removeAll(Channel channel) {
    return false;
  }

  @Override
  public boolean isOpen() {
    return true;
  }

  @Override
  public void destroy() {
  }

  @Override
  public void flushPartitions(Predicate<Object> predicate) {
  }

  @Override
  public Map<String, Long> getIdleChannelCountPerHost() {
    return Collections.emptyMap();
  }
}
