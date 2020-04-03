package org.asynchttpclient;

import io.netty.util.Timer;
import org.asynchttpclient.DefaultAsyncHttpClientConfig.Builder;
import org.asynchttpclient.cookie.CookieEvictionTask;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.testng.annotations.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

public class DefaultAsyncHttpClientTest {

  @Test
  public void testWithSharedNettyTimerShouldScheduleCookieEvictionOnlyOnce() throws IOException {
    final Timer nettyTimerMock = mock(Timer.class);
    final CookieStore cookieStore = new ThreadSafeCookieStore();
    final DefaultAsyncHttpClientConfig config = new Builder().setNettyTimer(nettyTimerMock).setCookieStore(cookieStore).build();
    final AsyncHttpClient client1 = new DefaultAsyncHttpClient(config);
    final AsyncHttpClient client2 = new DefaultAsyncHttpClient(config);

    assertEquals(cookieStore.count(), 2);
    verify(nettyTimerMock, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));

    closeSilently(client1);
    closeSilently(client2);
  }

  @Test
  public void testWitDefaultConfigShouldScheduleCookieEvictionForEachAHC() throws IOException {
    final AsyncHttpClientConfig config1 = new DefaultAsyncHttpClientConfig.Builder().build();
    final DefaultAsyncHttpClient client1 = new DefaultAsyncHttpClient(config1);

    final AsyncHttpClientConfig config2 = new DefaultAsyncHttpClientConfig.Builder().build();
    final DefaultAsyncHttpClient client2 = new DefaultAsyncHttpClient(config2);

    assertEquals(config1.getCookieStore().count(), 1);
    assertEquals(config2.getCookieStore().count(), 1);

    closeSilently(client1);
    closeSilently(client2);
  }

  @Test
  public void testWithSharedCookieStoreButNonSharedTimerShouldScheduleCookieEvictionForFirstAHC() throws IOException {
    final CookieStore cookieStore = new ThreadSafeCookieStore();
    final Timer nettyTimerMock1 = mock(Timer.class);
    final AsyncHttpClientConfig config1 = new DefaultAsyncHttpClientConfig.Builder()
            .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock1).build();
    final DefaultAsyncHttpClient client1 = new DefaultAsyncHttpClient(config1);

    final Timer nettyTimerMock2 = mock(Timer.class);
    final AsyncHttpClientConfig config2 = new DefaultAsyncHttpClientConfig.Builder()
            .setCookieStore(cookieStore).setNettyTimer(nettyTimerMock2).build();
    final DefaultAsyncHttpClient client2 = new DefaultAsyncHttpClient(config2);

    assertEquals(config1.getCookieStore().count(), 2);
    verify(nettyTimerMock1, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
    verify(nettyTimerMock2, never()).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));

    closeSilently(client1);
    closeSilently(client2);
  }

  private static void closeSilently(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        // swallow
      }
    }
  }
}
