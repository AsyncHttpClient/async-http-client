package com.ning.http.client.cookiejar;

import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;

public interface CookieJar
{

    RequestFilter getRequestFilter();

    ResponseFilter getResponseFilter();

}
