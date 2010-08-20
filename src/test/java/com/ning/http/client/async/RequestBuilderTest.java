package com.ning.http.client.async;

import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.RequestType;
import org.testng.annotations.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import static java.lang.String.format;
import static org.testng.Assert.assertEquals;

public class RequestBuilderTest {
    @Test(groups = "standalone")
    public void testEncodesQueryParameters() throws UnsupportedEncodingException
    {
        String value = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKQLMNOPQRSTUVWXYZ1234567809`~!@#$%^&*()_+-=,.<>/?;:'\"[]{}\\| ";
        RequestBuilder builder = new RequestBuilder(RequestType.GET).
                setUrl("http://example.com/").
                addQueryParameter("name", value);

        Request request = builder.build();
        assertEquals(request.getUrl(), format("http://example.com/?name=%s", URLEncoder.encode(value, "UTF-8").replace("+", "%20")));
    }
}
