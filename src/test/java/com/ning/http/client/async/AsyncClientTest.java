package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;

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

}
