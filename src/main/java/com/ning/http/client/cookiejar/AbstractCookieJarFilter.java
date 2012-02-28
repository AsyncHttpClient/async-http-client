package com.ning.http.client.cookiejar;

abstract class AbstractCookieJarFilter
{

    protected static final String HOST = "Host";

    protected final AbstractCookieJar cookieJar;

    public AbstractCookieJarFilter(AbstractCookieJar cookieJar)
    {
        this.cookieJar = cookieJar;
    }

}
