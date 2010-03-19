package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;


public class AsyncClientTest extends AbstractBasicTest {

    @Test
    public void multipleRequestsTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        String body = "hello there";

        // once
        Response response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(5, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

        // twice
        response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(5, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);
    }

    @Test
    public void multipleMaxConnectionOpenTest() throws Throwable {
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setKeepAlive(true)
                .setConnectionTimeoutInMs(1000).setMaximumConnectionsTotal(1).build();
        AsyncHttpClient c = new AsyncHttpClient(cg);

        String body = "hello there";

        // once
        Response response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(5, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

        // twice
        Exception exception = null;
        try{
            response = c.preparePost(TARGET_URL)
                    .setBody(body)
                    .execute().get(5, TimeUnit.SECONDS);
        } catch (Exception ex){
            ex.printStackTrace();
            exception = ex;
        }
        assertNotNull(exception);
        assertEquals(exception.getMessage(),"Too many connections");
    }
}
