package com.ning.http.client.cookiejar;

public final class InMemoryCookieJarTestcase
    extends AbstractCookieJarTestCase
{

    @Override
    protected CookieJar createCookieJar()
    {
        return new InMemoryCookieJar();
    }

}
