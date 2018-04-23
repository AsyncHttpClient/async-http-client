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
package org.asynchttpclient.netty.timeout;

import io.netty.util.TimerTask;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class TimeoutTimerTask implements TimerTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutTimerTask.class);

  protected final AtomicBoolean done = new AtomicBoolean();
  protected final NettyRequestSender requestSender;
  final TimeoutsHolder timeoutsHolder;
  volatile NettyResponseFuture<?> nettyResponseFuture;

  TimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, TimeoutsHolder timeoutsHolder) {
    this.nettyResponseFuture = nettyResponseFuture;
    this.requestSender = requestSender;
    this.timeoutsHolder = timeoutsHolder;
  }

  void expire(String message, long time) {
    LOGGER.debug("{} for {} after {} ms", message, nettyResponseFuture, time);
    requestSender.abort(nettyResponseFuture.channel(), nettyResponseFuture, new TimeoutException(message));
  }

  /**
   * When the timeout is cancelled, it could still be referenced for quite some time in the Timer. Holding a reference to the future might mean holding a reference to the
   * channel, and heavy objects such as SslEngines
   */
  public void clean() {
    if (done.compareAndSet(false, true)) {
      nettyResponseFuture = null;
    }
  }

  void appendRemoteAddress(StringBuilder sb) {
    InetSocketAddress remoteAddress = timeoutsHolder.remoteAddress();
    sb.append(remoteAddress.getHostName());
    if (!remoteAddress.isUnresolved()) {
      sb.append('/').append(remoteAddress.getAddress().getHostAddress());
    }
    sb.append(':').append(remoteAddress.getPort());
  }
}
