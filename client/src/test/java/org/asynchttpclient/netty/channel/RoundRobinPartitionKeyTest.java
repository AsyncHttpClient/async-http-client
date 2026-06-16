/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.channel;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RoundRobinPartitionKeyTest {

    @Test
    void sameBaseAndAddressAreEqual() throws Exception {
        InetAddress ip = InetAddress.getByName("127.0.0.1");
        RoundRobinPartitionKey a = new RoundRobinPartitionKey("base", ip);
        RoundRobinPartitionKey b = new RoundRobinPartitionKey("base", InetAddress.getByName("127.0.0.1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentAddressIsNotEqual() throws Exception {
        RoundRobinPartitionKey a = new RoundRobinPartitionKey("base", InetAddress.getByName("127.0.0.1"));
        RoundRobinPartitionKey b = new RoundRobinPartitionKey("base", InetAddress.getByName("127.0.0.2"));
        assertNotEquals(a, b);
    }

    @Test
    void differentBaseIsNotEqual() throws Exception {
        InetAddress ip = InetAddress.getByName("127.0.0.1");
        RoundRobinPartitionKey a = new RoundRobinPartitionKey("base", ip);
        RoundRobinPartitionKey b = new RoundRobinPartitionKey("other", ip);
        assertNotEquals(a, b);
    }

    @Test
    void withAddressKeepsBaseButChangesIp() throws Exception {
        RoundRobinPartitionKey original = new RoundRobinPartitionKey("base", InetAddress.getByName("127.0.0.1"));
        RoundRobinPartitionKey repinned = original.withAddress(InetAddress.getByName("127.0.0.2"));
        assertEquals(new RoundRobinPartitionKey("base", InetAddress.getByName("127.0.0.2")), repinned);
        assertNotEquals(original, repinned);
    }
}
