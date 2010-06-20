package com.ning.http.client.async;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.testng.annotations.Test;

import com.ning.http.client.Headers;

public class HeadersTest
{
    @Test
    public void emptyHeadersTest() {
        Headers headers = new Headers();

        assertTrue(headers.getHeaderNames().isEmpty());
    }

    @Test
    public void normalHeadersTest() {
        Headers headers = new Headers();

        headers.add("foo", "bar");
        headers.add("baz", Arrays.asList("foo", "bar"));

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
    }

    // same header multiple times, once with comma and once without
    // header with empty value
    // header with null value
    // headers as map
    // copy constructor
    // remove undefined header
    // remove header with two values
    // replace undefined header
    // replace header with two values
    // unmodifiable headers for null
    // unmodifiable headers for multiple headers
}
