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
package org.asynchttpclient.netty.channel;

/**
 * Represents the lifecycle state of a Netty channel in the connection pool.
 * <p>
 * Channels transition through various states from creation to closure, with
 * pooling and reconnection states in between. This enum tracks these states
 * for connection management and debugging purposes.
 * </p>
 */
public enum ChannelState {
  /**
   * Indicates a newly created channel that has not been used yet.
   */
  NEW,

  /**
   * Indicates a channel that has been returned to the connection pool
   * and is available for reuse.
   */
  POOLED,

  /**
   * Indicates a channel that was taken from the pool and reconnected
   * for a new request.
   */
  RECONNECTED,

  /**
   * Indicates a channel that has been closed and is no longer usable.
   */
  CLOSED,
}