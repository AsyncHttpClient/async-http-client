package com.ning.http.client.cookiejar;

import java.util.Collection;

import com.ning.http.client.Cookie;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;

public interface CookieJar
{

    RequestFilter getRequestFilter();

    ResponseFilter getResponseFilter();

    void persist( String host, Cookie cookie )
        throws Exception;

    Collection<Cookie> retrieve( String host )
        throws Exception;

    void remove( String host, Cookie cookie )
        throws Exception;

}
