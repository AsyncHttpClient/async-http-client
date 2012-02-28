package com.ning.http.client.cookiejar;

import static org.testng.Assert.assertNotNull;

import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

abstract class AbstractCookieJarTestCase
{

    private CookieJar cookieJar;

    @BeforeSuite
    public final void setUp()
    {
        cookieJar = createCookieJar();
    }

    protected abstract CookieJar createCookieJar();

    @AfterSuite
    public final void tearDown()
    {
        cookieJar = null;
    }

    @Test
    public void myCharmingCookieJarTest()
    {
        assertNotNull( cookieJar );
    }

}
