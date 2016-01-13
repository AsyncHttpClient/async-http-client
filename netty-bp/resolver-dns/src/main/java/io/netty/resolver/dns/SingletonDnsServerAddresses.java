/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.resolver.dns;

import java.net.InetSocketAddress;

final class SingletonDnsServerAddresses extends DnsServerAddresses {

    private final InetSocketAddress address;
    private final String strVal;

    private final DnsServerAddressStream stream = new DnsServerAddressStream() {
        @Override
        public InetSocketAddress next() {
            return address;
        }

        @Override
        public String toString() {
            return SingletonDnsServerAddresses.this.toString();
        }
    };

    SingletonDnsServerAddresses(InetSocketAddress address) {
        this.address = address;
        strVal = new StringBuilder(32).append("singleton(").append(address).append(')').toString();
    }

    @Override
    public DnsServerAddressStream stream() {
        return stream;
    }

    @Override
    public String toString() {
        return strVal;
    }
}
