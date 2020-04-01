package org.asynchttpclient;

import io.netty.util.HashedWheelTimer;
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
    final Timer nettyTimer = mock(HashedWheelTimer.class);
    final CookieStore cookieStore = new ThreadSafeCookieStore();
    final DefaultAsyncHttpClientConfig config = new Builder().setNettyTimer(nettyTimer).setCookieStore(cookieStore).build();
    final AsyncHttpClient client1 = new DefaultAsyncHttpClient(config);
    final AsyncHttpClient client2 = new DefaultAsyncHttpClient(config);

    try {
      assertEquals(cookieStore.count(), 2);
      verify(nettyTimer, times(1)).newTimeout(any(CookieEvictionTask.class), anyLong(), any(TimeUnit.class));
    } finally {
      closeSilently(client1);
      closeSilently(client2);
    }
  }

  @Test
  public void testWithNonNettyTimerShouldScheduleCookieEvictionForEachAHC() throws IOException {
    final AsyncHttpClientConfig config1 = new DefaultAsyncHttpClientConfig.Builder().build();
    final DefaultAsyncHttpClient client1 = new DefaultAsyncHttpClient(config1);

    final AsyncHttpClientConfig config2 = new DefaultAsyncHttpClientConfig.Builder().build();
    final DefaultAsyncHttpClient client2 = new DefaultAsyncHttpClient(config2);

    try {
      assertEquals(config1.getCookieStore().count(), 1);
      assertEquals(config2.getCookieStore().count(), 1);
    } finally {
      closeSilently(client1);
      closeSilently(client2);
    }
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
