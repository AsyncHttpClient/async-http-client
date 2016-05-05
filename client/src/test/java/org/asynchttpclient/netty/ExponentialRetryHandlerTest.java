package org.asynchttpclient.netty;

import org.asynchttpclient.handler.ExponentialRetryHandler;
import org.asynchttpclient.handler.RetryHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Created by charlie.chang on 5/5/16.
 */
public class ExponentialRetryHandlerTest {

    @Test
    public void testExponentialRetryBackoffHandler() {
        int initialValue = 100;
        int maxValue = 500;
        float multiplier = 2.0f;

        RetryHandler retryHandler = new ExponentialRetryHandler(initialValue,maxValue,multiplier);

        Assert.assertEquals(retryHandler.nextRetryMillis(),initialValue);
        Assert.assertEquals(retryHandler.nextRetryMillis(),(int)(initialValue * multiplier));
        Assert.assertEquals(retryHandler.nextRetryMillis(),(int)(initialValue * multiplier * multiplier));
        Assert.assertEquals(retryHandler.nextRetryMillis(),maxValue);
    }

}
