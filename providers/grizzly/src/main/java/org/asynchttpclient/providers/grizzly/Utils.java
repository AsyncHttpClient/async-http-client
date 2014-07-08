/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.uri.UriComponents;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeStorage;

import java.util.concurrent.atomic.AtomicInteger;

public final class Utils {

    private static final Attribute<Boolean> IGNORE = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(Utils.class.getName() + "-IGNORE");
    private static final Attribute<AtomicInteger> REQUEST_IN_FLIGHT = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(Utils.class
            .getName() + "-IN-FLIGHT");
    private static final Attribute<Boolean> SPDY = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(Utils.class.getName()
            + "-SPDY-CONNECTION");

    // ------------------------------------------------------------ Constructors

    private Utils() {
    }

    // ---------------------------------------------------------- Public Methods

    public static boolean isSecure(final UriComponents uri) {
        final String scheme = uri.getScheme();
        return ("https".equals(scheme) || "wss".equals(scheme));
    }

    public static void connectionIgnored(final Connection c, boolean ignored) {
        if (ignored) {
            IGNORE.set(c, true);
        } else {
            IGNORE.remove(c);
        }
    }

    public static boolean isIgnored(final Connection c) {
        Boolean result = IGNORE.get(c);
        return (result != null && result);
    }

    public static void addRequestInFlight(final AttributeStorage storage) {
        AtomicInteger counter = REQUEST_IN_FLIGHT.get(storage);
        if (counter == null) {
            counter = new AtomicInteger(1);
            REQUEST_IN_FLIGHT.set(storage, counter);
        } else {
            counter.incrementAndGet();
        }
    }

    public static void removeRequestInFlight(final AttributeStorage storage) {
        AtomicInteger counter = REQUEST_IN_FLIGHT.get(storage);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    public static int getRequestInFlightCount(final AttributeStorage storage) {
        AtomicInteger counter = REQUEST_IN_FLIGHT.get(storage);
        return counter != null ? counter.get() : 0;
    }

    public static void setSpdyConnection(final Connection c) {
        SPDY.set(c, Boolean.TRUE);
    }

    public static boolean isSpdyConnection(final Connection c) {
        Boolean result = SPDY.get(c);
        return result != null ? result : false;
    }
}
