package com.ning.http.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.util.UTF8UrlEncoder;

public class TestUTF8UrlCodec
{
    @Test(groups="fast")
    public void testBasics()
    {
        Assert.assertEquals(UTF8UrlEncoder.encode("foobar"), "foobar");
        Assert.assertEquals(UTF8UrlEncoder.encode("a&b"), "a%26b");
        Assert.assertEquals(UTF8UrlEncoder.encode("a+b"), "a%2Bb");
    }
}
