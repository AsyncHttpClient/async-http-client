package org.asynchttpclient;

import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.util.Timer;
import org.asynchttpclient.cookie.CookieEvictionTask;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class DefaultAsyncHttpClientTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testNativeTransportWithEpollOnly() throws Exception {
        AsyncHttpClientConfig config = config().setUseNativeTransport(true).setUseOnlyEpollNativeTransport(true).build();

        try (AsyncHttpClient ignored = asyncHttpClient(config)) {
            assertInstanceOf(EpollEventLoopGroup.class, config.getEventLoopGroup());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    public void testNativeTransportWithoutEpollOnly() throws Exception {
        AsyncHttpClientConfig config = config().setUseNativeTransport(true).setUseOnlyEpollNativeTransport(false).build();
        try (AsyncHttpClient ignored = asyncHttpClient(config)) {
            assertInstanceOf(IOUringEventLoopGroup.class, config.getEventLoopGroup());
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void testNativeTransportKQueueOnMacOs() throws Exception {
        AsyncHttpClientConfig config = config().setUseNativeTransport(true).build();
        try (AsyncHttpClient ignored = asyncHttpClient(config)) {
            assertInstanceOf(KQueueEventLoopGroup.class, config.getEventLoopGroup());
        }
    }

    @Test
    public void testUseOnlyEpollNativeTransportButNativeTransportIsDisabled() {
        assertThrows(IllegalArgumentException.class, () -> config().setUseNativeTransport(false).setUseOnlyEpollNativeTransport(true).build());
    }

    @Test
    public void testUseOnlyEpollNativeTransportAndNativeTransportIsEnabled() {
        assertDoesNotThrow(() -> config().setUseNativeTransport(true).setUseOnlyEpollNativeTransport(true).build());
    }

    @Test
    public void testWithSharedNettyTimerShouldScheduleCookieEvictionOnlyOnce() throws IOException {
        Timer nettyTimerMock = mock(Timer.class);
        CookieStore cookieStore = new ThreadSafeCookieStore();
        AsyncHttpClientConfig config = config().setNettyTimer(nettyTimerMock).setCookieStore(cookieStore).build();

        try (AsyncHttpClient client1 = asyncHttpClient(config)) {
            try (AsyncHttpClient client2 = asyncHttpClient(config)) {
                assertEquals(cookieStore.count(), 2);
                verify(nettyTimerMock, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
            }
        }
    }

    @Test
    public void testWitDefaultConfigShouldScheduleCookieEvictionForEachAHC() throws IOException {
        AsyncHttpClientConfig config1 = config().build();
        try (AsyncHttpClient client1 = asyncHttpClient(config1)) {
            AsyncHttpClientConfig config2 = config().build();
            try (AsyncHttpClient client2 = asyncHttpClient(config2)) {
                assertEquals(config1.getCookieStore().count(), 1);
                assertEquals(config2.getCookieStore().count(), 1);
            }
        }
    }

    @Test
    public void testWithSharedCookieStoreButNonSharedTimerShouldScheduleCookieEvictionForFirstAHC() throws IOException {
        CookieStore cookieStore = new ThreadSafeCookieStore();
        Timer nettyTimerMock1 = mock(Timer.class);
        AsyncHttpClientConfig config1 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock1).build();

        try (AsyncHttpClient client1 = asyncHttpClient(config1)) {
            Timer nettyTimerMock2 = mock(Timer.class);
            AsyncHttpClientConfig config2 = config()
                    .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock2).build();
            try (AsyncHttpClient client2 = asyncHttpClient(config2)) {
                assertEquals(config1.getCookieStore().count(), 2);
                verify(nettyTimerMock1, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
                verify(nettyTimerMock2, never()).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
            }
        }

        Timer nettyTimerMock3 = mock(Timer.class);
        AsyncHttpClientConfig config3 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock3).build();

        try (AsyncHttpClient client2 = asyncHttpClient(config3)) {
            assertEquals(config1.getCookieStore().count(), 1);
            verify(nettyTimerMock3, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    public void testWithSharedCookieStoreButNonSharedTimerShouldReScheduleCookieEvictionWhenFirstInstanceGetClosed() throws IOException {
        CookieStore cookieStore = new ThreadSafeCookieStore();
        Timer nettyTimerMock1 = mock(Timer.class);
        AsyncHttpClientConfig config1 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock1).build();

        try (AsyncHttpClient client1 = asyncHttpClient(config1)) {
            assertEquals(config1.getCookieStore().count(), 1);
            verify(nettyTimerMock1, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
        }

        assertEquals(config1.getCookieStore().count(), 0);

        Timer nettyTimerMock2 = mock(Timer.class);
        AsyncHttpClientConfig config2 = config()
                .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock2).build();

        try (AsyncHttpClient client2 = asyncHttpClient(config2)) {
            assertEquals(config1.getCookieStore().count(), 1);
            verify(nettyTimerMock2, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    public void testDisablingCookieStore() throws IOException {
        AsyncHttpClientConfig config = config()
                .setCookieStore(null).build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            //
        }
    }
}
