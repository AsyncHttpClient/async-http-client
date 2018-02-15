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
import io.netty.channel.ChannelId;
import io.netty.util.*;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.channel.ChannelPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

/**
 * A simple implementation of {@link ChannelPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public final class DefaultChannelPool implements ChannelPool {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);
  private static final AttributeKey<ChannelCreation> CHANNEL_CREATION_ATTRIBUTE_KEY = AttributeKey.valueOf("channelCreation");

  private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<IdleChannel>> partitions = new ConcurrentHashMap<>();
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  private final Timer nettyTimer;
  private final int connectionTtl;
  private final boolean connectionTtlEnabled;
  private final int maxIdleTime;
  private final boolean maxIdleTimeEnabled;
  private final long cleanerPeriod;
  private final PoolLeaseStrategy poolLeaseStrategy;

  public DefaultChannelPool(AsyncHttpClientConfig config, Timer hashedWheelTimer) {
    this(config.getPooledConnectionIdleTimeout(),
            config.getConnectionTtl(),
            hashedWheelTimer,
            config.getConnectionPoolCleanerPeriod());
  }

  public DefaultChannelPool(int maxIdleTime,
                            int connectionTtl,
                            Timer nettyTimer,
                            int cleanerPeriod) {
    this(maxIdleTime,
            connectionTtl,
            PoolLeaseStrategy.LIFO,
            nettyTimer,
            cleanerPeriod);
  }

  public DefaultChannelPool(int maxIdleTime,
                            int connectionTtl,
                            PoolLeaseStrategy poolLeaseStrategy,
                            Timer nettyTimer,
                            int cleanerPeriod) {
    this.maxIdleTime = maxIdleTime;
    this.connectionTtl = connectionTtl;
    connectionTtlEnabled = connectionTtl > 0;
    this.nettyTimer = nettyTimer;
    maxIdleTimeEnabled = maxIdleTime > 0;
    this.poolLeaseStrategy = poolLeaseStrategy;

    this.cleanerPeriod = Math.min(cleanerPeriod, Math.min(connectionTtlEnabled ? connectionTtl : Integer.MAX_VALUE, maxIdleTimeEnabled ? maxIdleTime : Integer.MAX_VALUE));

    if (connectionTtlEnabled || maxIdleTimeEnabled)
      scheduleNewIdleChannelDetector(new IdleChannelDetector());
  }

  private void scheduleNewIdleChannelDetector(TimerTask task) {
    nettyTimer.newTimeout(task, cleanerPeriod, TimeUnit.MILLISECONDS);
  }

  private boolean isTtlExpired(Channel channel, long now) {
    if (!connectionTtlEnabled)
      return false;

    ChannelCreation creation = channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY).get();
    return creation != null && now - creation.creationTime >= connectionTtl;
  }

  /**
   * {@inheritDoc}
   */
  public boolean offer(Channel channel, Object partitionKey) {
    if (isClosed.get())
      return false;

    long now = unpreciseMillisTime();

    if (isTtlExpired(channel, now))
      return false;

    boolean offered = offer0(channel, partitionKey, now);
    if (connectionTtlEnabled && offered) {
      registerChannelCreation(channel, partitionKey, now);
    }

    return offered;
  }

  private boolean offer0(Channel channel, Object partitionKey, long now) {
    ConcurrentLinkedDeque<IdleChannel> partition = partitions.get(partitionKey);
    if (partition == null) {
      partition = partitions.computeIfAbsent(partitionKey, pk -> new ConcurrentLinkedDeque<>());
    }
    return partition.offerFirst(new IdleChannel(channel, now));
  }

  private void registerChannelCreation(Channel channel, Object partitionKey, long now) {
    ChannelId id = channel.id();
    Attribute<ChannelCreation> channelCreationAttribute = channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY);
    if (channelCreationAttribute.get() == null) {
      channelCreationAttribute.set(new ChannelCreation(now, partitionKey));
    }
  }

  /**
   * {@inheritDoc}
   */
  public Channel poll(Object partitionKey) {

    IdleChannel idleChannel = null;
    ConcurrentLinkedDeque<IdleChannel> partition = partitions.get(partitionKey);
    if (partition != null) {
      while (idleChannel == null) {
        idleChannel = poolLeaseStrategy.lease(partition);

        if (idleChannel == null)
          // pool is empty
          break;
        else if (!Channels.isChannelActive(idleChannel.channel)) {
          idleChannel = null;
          LOGGER.trace("Channel is inactive, probably remotely closed!");
        } else if (!idleChannel.takeOwnership()) {
          idleChannel = null;
          LOGGER.trace("Couldn't take ownership of channel, probably in the process of being expired!");
        }
      }
    }
    return idleChannel != null ? idleChannel.channel : null;
  }

  /**
   * {@inheritDoc}
   */
  public boolean removeAll(Channel channel) {
    ChannelCreation creation = connectionTtlEnabled ? channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY).get() : null;
    return !isClosed.get() && creation != null && partitions.get(creation.partitionKey).remove(new IdleChannel(channel, Long.MIN_VALUE));
  }

  /**
   * {@inheritDoc}
   */
  public boolean isOpen() {
    return !isClosed.get();
  }

  /**
   * {@inheritDoc}
   */
  public void destroy() {
    if (isClosed.getAndSet(true))
      return;

    partitions.clear();
  }

  private void close(Channel channel) {
    // FIXME pity to have to do this here
    Channels.setDiscard(channel);
    Channels.silentlyCloseChannel(channel);
  }

  private void flushPartition(Object partitionKey, ConcurrentLinkedDeque<IdleChannel> partition) {
    if (partition != null) {
      partitions.remove(partitionKey);
      for (IdleChannel idleChannel : partition)
        close(idleChannel.channel);
    }
  }

  @Override
  public void flushPartitions(Predicate<Object> predicate) {
    for (Map.Entry<Object, ConcurrentLinkedDeque<IdleChannel>> partitionsEntry : partitions.entrySet()) {
      Object partitionKey = partitionsEntry.getKey();
      if (predicate.test(partitionKey))
        flushPartition(partitionKey, partitionsEntry.getValue());
    }
  }

  @Override
  public Map<String, Long> getIdleChannelCountPerHost() {
    return partitions
            .values()
            .stream()
            .flatMap(ConcurrentLinkedDeque::stream)
            .map(idle -> idle.getChannel().remoteAddress())
            .filter(a -> a.getClass() == InetSocketAddress.class)
            .map(a -> (InetSocketAddress) a)
            .map(InetSocketAddress::getHostName)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  public enum PoolLeaseStrategy {
    LIFO {
      public <E> E lease(Deque<E> d) {
        return d.pollFirst();
      }
    },
    FIFO {
      public <E> E lease(Deque<E> d) {
        return d.pollLast();
      }
    };

    abstract <E> E lease(Deque<E> d);
  }

  private static final class ChannelCreation {
    final long creationTime;
    final Object partitionKey;

    ChannelCreation(long creationTime, Object partitionKey) {
      this.creationTime = creationTime;
      this.partitionKey = partitionKey;
    }
  }

  private static final class IdleChannel {

    private static final AtomicIntegerFieldUpdater<IdleChannel> ownedField = AtomicIntegerFieldUpdater.newUpdater(IdleChannel.class, "owned");

    final Channel channel;
    final long start;
    @SuppressWarnings("unused")
    private volatile int owned = 0;

    IdleChannel(Channel channel, long start) {
      this.channel = assertNotNull(channel, "channel");
      this.start = start;
    }

    public boolean takeOwnership() {
      return ownedField.getAndSet(this, 1) == 0;
    }

    public Channel getChannel() {
      return channel;
    }

    @Override
    // only depends on channel
    public boolean equals(Object o) {
      return this == o || (o instanceof IdleChannel && channel.equals(IdleChannel.class.cast(o).channel));
    }

    @Override
    public int hashCode() {
      return channel.hashCode();
    }
  }

  private final class IdleChannelDetector implements TimerTask {

    private boolean isIdleTimeoutExpired(IdleChannel idleChannel, long now) {
      return maxIdleTimeEnabled && now - idleChannel.start >= maxIdleTime;
    }

    private List<IdleChannel> expiredChannels(ConcurrentLinkedDeque<IdleChannel> partition, long now) {
      // lazy create
      List<IdleChannel> idleTimeoutChannels = null;
      for (IdleChannel idleChannel : partition) {
        boolean isIdleTimeoutExpired = isIdleTimeoutExpired(idleChannel, now);
        boolean isRemotelyClosed = !Channels.isChannelActive(idleChannel.channel);
        boolean isTtlExpired = isTtlExpired(idleChannel.channel, now);
        if (isIdleTimeoutExpired || isRemotelyClosed || isTtlExpired) {
          LOGGER.debug("Adding Candidate expired Channel {} isIdleTimeoutExpired={} isRemotelyClosed={} isTtlExpired={}", idleChannel.channel, isIdleTimeoutExpired, isRemotelyClosed, isTtlExpired);
          if (idleTimeoutChannels == null)
            idleTimeoutChannels = new ArrayList<>(1);
          idleTimeoutChannels.add(idleChannel);
        }
      }

      return idleTimeoutChannels != null ? idleTimeoutChannels : Collections.emptyList();
    }

    private List<IdleChannel> closeChannels(List<IdleChannel> candidates) {

      // lazy create, only if we hit a non-closeable channel
      List<IdleChannel> closedChannels = null;
      for (int i = 0; i < candidates.size(); i++) {
        // We call takeOwnership here to avoid closing a channel that has just been taken out
        // of the pool, otherwise we risk closing an active connection.
        IdleChannel idleChannel = candidates.get(i);
        if (idleChannel.takeOwnership()) {
          LOGGER.debug("Closing Idle Channel {}", idleChannel.channel);
          close(idleChannel.channel);
          if (closedChannels != null) {
            closedChannels.add(idleChannel);
          }

        } else if (closedChannels == null) {
          // first non closeable to be skipped, copy all
          // previously skipped closeable channels
          closedChannels = new ArrayList<>(candidates.size());
          for (int j = 0; j < i; j++)
            closedChannels.add(candidates.get(j));
        }
      }

      return closedChannels != null ? closedChannels : candidates;
    }

    public void run(Timeout timeout) {

      if (isClosed.get())
        return;

      if (LOGGER.isDebugEnabled())
        for (Object key : partitions.keySet()) {
          int size = partitions.get(key).size();
          if (size > 0) {
            LOGGER.debug("Entry count for : {} : {}", key, size);
          }
        }

      long start = unpreciseMillisTime();
      int closedCount = 0;
      int totalCount = 0;

      for (ConcurrentLinkedDeque<IdleChannel> partition : partitions.values()) {

        // store in intermediate unsynchronized lists to minimize
        // the impact on the ConcurrentLinkedDeque
        if (LOGGER.isDebugEnabled())
          totalCount += partition.size();

        List<IdleChannel> closedChannels = closeChannels(expiredChannels(partition, start));

        if (!closedChannels.isEmpty()) {
          partition.removeAll(closedChannels);
          closedCount += closedChannels.size();
        }
      }

      if (LOGGER.isDebugEnabled()) {
        long duration = unpreciseMillisTime() - start;
        if (closedCount > 0) {
          LOGGER.debug("Closed {} connections out of {} in {} ms", closedCount, totalCount, duration);
        }
      }

      scheduleNewIdleChannelDetector(timeout.task());
    }
  }
}
