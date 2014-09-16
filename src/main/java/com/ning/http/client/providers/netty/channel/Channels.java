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
package com.ning.http.client.providers.netty.channel;

import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.providers.netty.DiscardEvent;

public final class Channels {

    private static final Logger LOGGER = LoggerFactory.getLogger(Channels.class);

    private Channels() {
    }

    public static void setAttribute(Channel channel, Object attribute) {
        channel.setAttachment(attribute);
    }

    public static Object getAttribute(Channel channel) {
        return channel.getAttachment();
    }

    public static void setDiscard(Channel channel) {
        setAttribute(channel, DiscardEvent.INSTANCE);
    }

    public static boolean isChannelValid(Channel channel) {
        return channel != null && channel.isConnected();
    }

    public static void silentlyCloseChannel(Channel channel) {
        try {
            if (channel != null && channel.isOpen())
                channel.close();
        } catch (Throwable t) {
            LOGGER.debug("Failed to close channel", t);
        }
    }
}
