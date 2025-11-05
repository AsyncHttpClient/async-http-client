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
package org.asynchttpclient.netty.channel;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.asynchttpclient.netty.DiscardEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for managing Netty channel attributes and lifecycle operations.
 * <p>
 * This class provides helper methods for attaching state to channels, checking
 * channel activity, and performing safe channel operations. It uses Netty's
 * attribute system to store per-channel state information.
 * </p>
 */
public class Channels {

  private static final Logger LOGGER = LoggerFactory.getLogger(Channels.class);

  private static final AttributeKey<Object> DEFAULT_ATTRIBUTE = AttributeKey.valueOf("default");
  private static final AttributeKey<Active> ACTIVE_TOKEN_ATTRIBUTE = AttributeKey.valueOf("activeToken");

  /**
   * Retrieves the attribute attached to the channel.
   * <p>
   * The attribute typically contains state information such as a {@link org.asynchttpclient.netty.NettyResponseFuture},
   * {@link org.asynchttpclient.netty.handler.StreamedResponsePublisher}, or {@link org.asynchttpclient.netty.DiscardEvent}.
   * </p>
   *
   * @param channel the channel to query
   * @return the attribute object, or null if no attribute is set
   */
  public static Object getAttribute(Channel channel) {
    Attribute<Object> attr = channel.attr(DEFAULT_ATTRIBUTE);
    return attr != null ? attr.get() : null;
  }

  /**
   * Sets an attribute on the channel.
   * <p>
   * This method attaches state information to the channel for use by handlers
   * in the pipeline. Common attributes include response futures and callbacks.
   * </p>
   *
   * @param channel the channel to modify
   * @param o the attribute object to attach
   */
  public static void setAttribute(Channel channel, Object o) {
    channel.attr(DEFAULT_ATTRIBUTE).set(o);
  }

  /**
   * Marks the channel for discard by setting the DISCARD attribute.
   * <p>
   * When a channel is marked for discard, handlers will ignore incoming
   * messages and the channel will be closed rather than returned to the pool.
   * </p>
   *
   * @param channel the channel to mark for discard
   */
  public static void setDiscard(Channel channel) {
    setAttribute(channel, DiscardEvent.DISCARD);
  }

  /**
   * Checks if a channel is active and usable.
   *
   * @param channel the channel to check
   * @return true if the channel is non-null and active
   */
  public static boolean isChannelActive(Channel channel) {
    return channel != null && channel.isActive();
  }

  /**
   * Sets an active token on the channel.
   * <p>
   * This token is used to track channel activation state and prevent
   * duplicate connection attempts or race conditions.
   * </p>
   *
   * @param channel the channel to mark as active
   */
  public static void setActiveToken(Channel channel) {
    channel.attr(ACTIVE_TOKEN_ATTRIBUTE).set(Active.INSTANCE);
  }

  /**
   * Checks and removes the active token from the channel atomically.
   * <p>
   * This method returns true if the token was present (indicating the channel
   * was properly activated) and removes it in a single atomic operation.
   * </p>
   *
   * @param channel the channel to check
   * @return true if the active token was present and has been removed
   */
  public static boolean isActiveTokenSet(Channel channel) {
    return channel != null && channel.attr(ACTIVE_TOKEN_ATTRIBUTE).getAndSet(null) != null;
  }

  /**
   * Closes a channel while suppressing any exceptions.
   * <p>
   * This method attempts to close the channel if it is active, logging
   * any errors that occur during closure. It is safe to call on null
   * or already-closed channels.
   * </p>
   *
   * @param channel the channel to close (may be null)
   */
  public static void silentlyCloseChannel(Channel channel) {
    try {
      if (channel != null && channel.isActive())
        channel.close();
    } catch (Throwable t) {
      LOGGER.debug("Failed to close channel", t);
    }
  }

  private enum Active {INSTANCE}
}
