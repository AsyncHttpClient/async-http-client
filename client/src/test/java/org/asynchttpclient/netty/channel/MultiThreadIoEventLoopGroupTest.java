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

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for Netty 4.2+ MultiThreadIoEventLoopGroup support.
 */
class MultiThreadIoEventLoopGroupTest {

    @Test
    void testMultiThreadIoEventLoopGroupWithNioHandler() {
        // Create a Netty 4.2 style event loop group with NIO handler
        MultiThreadIoEventLoopGroup eventLoopGroup = new MultiThreadIoEventLoopGroup(
                2,
                new DefaultThreadFactory("test-nio"),
                NioIoHandler.newFactory()
        );

        try {
            // Should not throw IllegalArgumentException for unknown event loop group
            DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                    .setEventLoopGroup(eventLoopGroup)
                    .build();

            AsyncHttpClient client = assertDoesNotThrow(
                    () -> Dsl.asyncHttpClient(config),
                    "Should accept MultiThreadIoEventLoopGroup with NIO handler"
            );

            assertNotNull(client, "Client should be created successfully");

            try {
                client.close();
            } catch (Exception e) {
                fail("Failed to close client: " + e.getMessage());
            }
        } finally {
            eventLoopGroup.shutdownGracefully();
        }
    }

    @Test
    void testMultiThreadIoEventLoopGroupWithIoUringHandler() {
        // Skip test if io_uring is not available on this platform
        if (!IoUring.isAvailable()) {
            return;
        }

        // Create a Netty 4.2 style event loop group with io_uring handler
        MultiThreadIoEventLoopGroup eventLoopGroup = new MultiThreadIoEventLoopGroup(
                2,
                new DefaultThreadFactory("test-iouring"),
                IoUringIoHandler.newFactory()
        );

        try {
            // Should not throw IllegalArgumentException for unknown event loop group
            DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                    .setEventLoopGroup(eventLoopGroup)
                    .build();

            AsyncHttpClient client = assertDoesNotThrow(
                    () -> Dsl.asyncHttpClient(config),
                    "Should accept MultiThreadIoEventLoopGroup with io_uring handler"
            );

            assertNotNull(client, "Client should be created successfully");

            try {
                client.close();
            } catch (Exception e) {
                fail("Failed to close client: " + e.getMessage());
            }
        } finally {
            eventLoopGroup.shutdownGracefully();
        }
    }

    @Test
    void testMultiThreadIoEventLoopGroupWithUseNativeTransportFalse() {
        // Create event loop group with NIO handler
        MultiThreadIoEventLoopGroup eventLoopGroup = new MultiThreadIoEventLoopGroup(
                2,
                new DefaultThreadFactory("test-nio-nonative"),
                NioIoHandler.newFactory()
        );

        try {
            // Explicitly disable native transport
            DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                    .setEventLoopGroup(eventLoopGroup)
                    .setUseNativeTransport(false)
                    .build();

            AsyncHttpClient client = assertDoesNotThrow(
                    () -> Dsl.asyncHttpClient(config),
                    "Should accept MultiThreadIoEventLoopGroup even with useNativeTransport=false"
            );

            assertNotNull(client, "Client should be created successfully");

            try {
                client.close();
            } catch (Exception e) {
                fail("Failed to close client: " + e.getMessage());
            }
        } finally {
            eventLoopGroup.shutdownGracefully();
        }
    }

    @Test
    void testSharedMultiThreadIoEventLoopGroupWithIoUring() {
        // Skip test if io_uring is not available on this platform
        if (!IoUring.isAvailable()) {
            return;
        }

        MultiThreadIoEventLoopGroup sharedEventLoopGroup = new MultiThreadIoEventLoopGroup(
                Runtime.getRuntime().availableProcessors() * 2,
                new DefaultThreadFactory("shared-iouring"),
                IoUringIoHandler.newFactory()
        );

        try {
            DefaultAsyncHttpClientConfig config1 = new DefaultAsyncHttpClientConfig.Builder()
                    .setEventLoopGroup(sharedEventLoopGroup)
                    .setThreadPoolName("Client1-Thread-Pool")
                    .build();

            DefaultAsyncHttpClientConfig config2 = new DefaultAsyncHttpClientConfig.Builder()
                    .setEventLoopGroup(sharedEventLoopGroup)
                    .setThreadPoolName("Client2-Thread-Pool")
                    .build();

            DefaultAsyncHttpClientConfig config3 = new DefaultAsyncHttpClientConfig.Builder()
                    .setEventLoopGroup(sharedEventLoopGroup)
                    .setThreadPoolName("Client3-Thread-Pool")
                    .build();

            // All clients should be created successfully without IllegalArgumentException
            AsyncHttpClient client1 = assertDoesNotThrow(
                    () -> Dsl.asyncHttpClient(config1),
                    "First client should accept shared MultiThreadIoEventLoopGroup with io_uring"
            );

            AsyncHttpClient client2 = assertDoesNotThrow(
                    () -> Dsl.asyncHttpClient(config2),
                    "Second client should accept shared MultiThreadIoEventLoopGroup with io_uring"
            );

            AsyncHttpClient client3 = assertDoesNotThrow(
                    () -> Dsl.asyncHttpClient(config3),
                    "Third client should accept shared MultiThreadIoEventLoopGroup with io_uring"
            );

            assertNotNull(client1, "Client 1 should be created successfully");
            assertNotNull(client2, "Client 2 should be created successfully");
            assertNotNull(client3, "Client 3 should be created successfully");

            try {
                client1.close();
                client2.close();
                client3.close();
            } catch (Exception e) {
                fail("Failed to close clients: " + e.getMessage());
            }
        } finally {
            sharedEventLoopGroup.shutdownGracefully();
        }
    }
}
