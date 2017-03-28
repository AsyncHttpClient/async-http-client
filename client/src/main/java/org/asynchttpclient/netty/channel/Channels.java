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

import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.netty.DiscardEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Channels {

    private static final Logger LOGGER = LoggerFactory.getLogger(Channels.class);

    private static final AttributeKey<Object> DEFAULT_ATTRIBUTE = AttributeKey.valueOf("default");
    private static final AttributeKey<Inactive> INACTIVE_TOKEN_ATTRIBUTE = AttributeKey.valueOf("inactiveToken");

    private enum Inactive { INSTANCE }

    public static Object getAttribute(Channel channel) {
        Attribute<Object> attr = channel.attr(DEFAULT_ATTRIBUTE);
        return attr != null ? attr.get() : null;
    }

    public static void setAttribute(Channel channel, Object o) {
        channel.attr(DEFAULT_ATTRIBUTE).set(o);
    }

    public static void setDiscard(Channel channel) {
        setAttribute(channel, DiscardEvent.INSTANCE);
    }

    public static boolean isChannelValid(Channel channel) {
        return channel != null && channel.isActive();
    }
    
    public static void setInactiveToken(Channel channel) {
        channel.attr(INACTIVE_TOKEN_ATTRIBUTE).set(Inactive.INSTANCE);
    }
    
    public static boolean getInactiveToken(Channel channel) {
        return channel != null && channel.attr(INACTIVE_TOKEN_ATTRIBUTE).getAndSet(null) != null;
    }

    public static void silentlyCloseChannel(Channel channel) {
        try {
            if (channel != null && channel.isActive())
                channel.close();
        } catch (Throwable t) {
            LOGGER.debug("Failed to close channel", t);
        }
    }
}
