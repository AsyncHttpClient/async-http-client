package com.ning.http.client.async;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

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

    @Test
    public void nameCaseTest() {
        Headers headers = new Headers();

        headers.add("fOO", "bAr");
        headers.add("Baz", Arrays.asList("fOo", "bar"));

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("fOO", "Baz")));

        assertEquals(headers.getFirstHeaderValue("fOO"), "bAr");
        assertEquals(headers.getHeaderValue("fOO"), "bAr");
        assertEquals(headers.getHeaderValues("fOO"), Arrays.asList("bAr"));
        assertEquals(headers.getFirstHeaderValue("foo"), "bAr");
        assertEquals(headers.getHeaderValue("foo"), "bAr");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bAr"));
        assertEquals(headers.getFirstHeaderValue("FOO"), "bAr");
        assertEquals(headers.getHeaderValue("FOO"), "bAr");
        assertEquals(headers.getHeaderValues("FOO"), Arrays.asList("bAr"));

        assertEquals(headers.getFirstHeaderValue("Baz"), "fOo");
        assertEquals(headers.getHeaderValue("Baz"), "fOo, bar");
        assertEquals(headers.getHeaderValues("Baz"), Arrays.asList("fOo", "bar"));
        assertEquals(headers.getFirstHeaderValue("baz"), "fOo");
        assertEquals(headers.getHeaderValue("baz"), "fOo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("fOo", "bar"));
        assertEquals(headers.getFirstHeaderValue("BAZ"), "fOo");
        assertEquals(headers.getHeaderValue("BAZ"), "fOo, bar");
        assertEquals(headers.getHeaderValues("BAZ"), Arrays.asList("fOo", "bar"));
    }

    @Test
    public void sameHeaderMultipleTimesTest() {
        Headers headers = new Headers();

        headers.add("foo", "baz,foo");
        headers.add("Foo", Arrays.asList("bar"));
        headers.add("fOO", "bla", "blubb");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo")));

        assertEquals(headers.getFirstHeaderValue("foo"), "baz,foo");
        assertEquals(headers.getHeaderValue("foo"), "baz,foo, bar, bla, blubb");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("baz,foo", "bar", "bla", "blubb"));
        assertEquals(headers.getFirstHeaderValue("Foo"), "baz,foo");
        assertEquals(headers.getHeaderValue("Foo"), "baz,foo, bar, bla, blubb");
        assertEquals(headers.getHeaderValues("Foo"), Arrays.asList("baz,foo", "bar", "bla", "blubb"));
        assertEquals(headers.getFirstHeaderValue("fOO"), "baz,foo");
        assertEquals(headers.getHeaderValue("fOO"), "baz,foo, bar, bla, blubb");
        assertEquals(headers.getHeaderValues("fOO"), Arrays.asList("baz,foo", "bar", "bla", "blubb"));
    }

    @Test
    public void emptyHeaderTest() {
        Headers headers = new Headers();

        headers.add("foo", "");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo")));
        assertEquals(headers.getFirstHeaderValue("foo"), "");
        assertEquals(headers.getHeaderValue("foo"), "");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList(""));
    }

    @Test
    public void nullHeaderTest() {
        Headers headers = new Headers();

        headers.add("foo", (String)null);

        assertTrue(headers.getHeaderNames().isEmpty());
        assertNull(headers.getFirstHeaderValue("foo"));
        assertNull(headers.getHeaderValue("foo"));
        assertNull(headers.getHeaderValues("foo"));
    }

    @Test
    public void headerMapConstructorTest() {
        Map<String, Collection<String>> headerMap = new LinkedHashMap<String, Collection<String>>();

        headerMap.put("foo", Arrays.asList("baz,foo"));
        headerMap.put("baz", Arrays.asList("bar"));
        headerMap.put("bar", Arrays.asList("bla", "blubb"));

        Headers headers = new Headers(headerMap);

        headerMap.remove("foo");
        headerMap.remove("bar");
        headerMap.remove("baz");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "baz", "bar")));
        assertEquals(headers.getFirstHeaderValue("foo"), "baz,foo");
        assertEquals(headers.getHeaderValue("foo"), "baz,foo");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("baz,foo"));
        assertEquals(headers.getFirstHeaderValue("baz"), "bar");
        assertEquals(headers.getHeaderValue("baz"), "bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("bar"), "bla");
        assertEquals(headers.getHeaderValue("bar"), "bla, blubb");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("bla", "blubb"));
    }

    @Test
    public void copyConstructorTest() {
        Headers srcHeaders = new Headers();

        srcHeaders.add("foo", "baz,foo");
        srcHeaders.add("baz", Arrays.asList("bar"));
        srcHeaders.add("bar", "bla", "blubb");

        Headers headers = new Headers(srcHeaders);

        srcHeaders.remove("foo");
        srcHeaders.remove("bar");
        srcHeaders.remove("baz");
        assertTrue(srcHeaders.getHeaderNames().isEmpty());

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "baz", "bar")));
        assertEquals(headers.getFirstHeaderValue("foo"), "baz,foo");
        assertEquals(headers.getHeaderValue("foo"), "baz,foo");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("baz,foo"));
        assertEquals(headers.getFirstHeaderValue("baz"), "bar");
        assertEquals(headers.getHeaderValue("baz"), "bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("bar"), "bla");
        assertEquals(headers.getHeaderValue("bar"), "bla, blubb");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("bla", "blubb"));
    }

    @Test
    public void removeHeaderTest() {
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

        headers.remove("bAz");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertNull(headers.getFirstHeaderValue("baz"));
        assertNull(headers.getHeaderValue("baz"));
        assertNull(headers.getHeaderValues("baz"));
    }

    @Test
    public void removeHeadersTest1() {
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

        headers.removeAll("bAz", "Boo");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertNull(headers.getFirstHeaderValue("baz"));
        assertNull(headers.getHeaderValue("baz"));
        assertNull(headers.getHeaderValues("baz"));
    }

    @Test
    public void removeHeadersTest2() {
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

        headers.removeAll(Arrays.asList("bAz", "fOO"));

        assertEquals(headers.getHeaderNames(), Collections.<String>emptyList());
        assertNull(headers.getFirstHeaderValue("foo"));
        assertNull(headers.getHeaderValue("foo"));
        assertNull(headers.getHeaderValues("foo"));
        assertNull(headers.getFirstHeaderValue("baz"));
        assertNull(headers.getHeaderValue("baz"));
        assertNull(headers.getHeaderValues("baz"));
    }

    @Test
    public void removeUndefinedHeaderTest() {
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

        headers.remove("bar");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
    }

    @Test
    public void replaceHeaderTest() {
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

        headers.replace("Foo", "blub", "bla");

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("Foo", "baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "blub");
        assertEquals(headers.getHeaderValue("foo"), "blub, bla");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("blub", "bla"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
    }

    @Test
    public void replaceUndefinedHeaderTest() {
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

        headers.replace("bar", Arrays.asList("blub"));

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "baz", "bar")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
        assertEquals(headers.getFirstHeaderValue("bar"), "blub");
        assertEquals(headers.getHeaderValue("bar"), "blub");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("blub"));
    }

    @Test
    public void replaceHeaderWithNullTest() {
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

        headers.replace("baZ", (Collection<String>)null);

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertNull(headers.getFirstHeaderValue("baz"));
        assertNull(headers.getHeaderValue("baz"));
        assertNull(headers.getHeaderValues("baz"));
    }

    @Test
    public void replaceHeadersTest1() {
        Headers headers = new Headers();

        headers.add("foo", "bar");
        headers.add("bar", "foo, bar", "baz");
        headers.add("baz", Arrays.asList("foo", "bar"));

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "bar", "baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("bar"), "foo, bar");
        assertEquals(headers.getHeaderValue("bar"), "foo, bar, baz");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("foo, bar", "baz"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));

        headers.replaceAll(new Headers().add("Bar", "baz").add("Boo", "blub", "bla"));

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "Bar", "baz", "Boo")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("bar"), "baz");
        assertEquals(headers.getHeaderValue("bar"), "baz");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("baz"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
        assertEquals(headers.getFirstHeaderValue("Boo"), "blub");
        assertEquals(headers.getHeaderValue("Boo"), "blub, bla");
        assertEquals(headers.getHeaderValues("Boo"), Arrays.asList("blub", "bla"));
    }

    @Test
    public void replaceHeadersTest2() {
        Headers headers = new Headers();

        headers.add("foo", "bar");
        headers.add("bar", "foo, bar", "baz");
        headers.add("baz", Arrays.asList("foo", "bar"));

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "bar", "baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("bar"), "foo, bar");
        assertEquals(headers.getHeaderValue("bar"), "foo, bar, baz");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("foo, bar", "baz"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));

        LinkedHashMap<String, Collection<String>> newValues = new LinkedHashMap<String, Collection<String>>();

        newValues.put("Bar", Arrays.asList("baz"));
        newValues.put("Foo", null);
        headers.replaceAll(newValues);

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("Bar", "baz")));
        assertNull(headers.getFirstHeaderValue("foo"));
        assertNull(headers.getHeaderValue("foo"));
        assertNull(headers.getHeaderValues("foo"));
        assertEquals(headers.getFirstHeaderValue("bar"), "baz");
        assertEquals(headers.getHeaderValue("bar"), "baz");
        assertEquals(headers.getHeaderValues("bar"), Arrays.asList("baz"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
    }

    @Test
    public void unmodifiableHeadersTest() {
        Headers srcHeaders = new Headers();

        srcHeaders.add("foo", "bar");
        srcHeaders.add("Baz", Arrays.asList("foo", "bar"));

        Headers headers = Headers.unmodifiableHeaders(srcHeaders);

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "Baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));

        try {
            headers.add("bar", "bla");
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            headers.add("bar", Arrays.asList("bla"));
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            headers.add("bar", Arrays.asList("bla"));
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            headers.addAll(new Headers().add("foo", "bar"));
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            LinkedHashMap<String, Collection<String>> map = new LinkedHashMap<String, Collection<String>>();

            map.put("foo", Arrays.asList("bar"));
            headers.addAll(map);
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            headers.remove("foo");
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            headers.replace("foo", "bla");
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }
        try {
            headers.replace("foo", Arrays.asList("bla"));
            fail("Expected UnsupportedOperationException");
        }
        catch (UnsupportedOperationException ex) {
            // expected
        }

        assertEquals(headers.getHeaderNames(), new LinkedHashSet<String>(Arrays.asList("foo", "Baz")));
        assertEquals(headers.getFirstHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValue("foo"), "bar");
        assertEquals(headers.getHeaderValues("foo"), Arrays.asList("bar"));
        assertEquals(headers.getFirstHeaderValue("baz"), "foo");
        assertEquals(headers.getHeaderValue("baz"), "foo, bar");
        assertEquals(headers.getHeaderValues("baz"), Arrays.asList("foo", "bar"));
    }
}
