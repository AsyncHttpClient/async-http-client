package com.ning.http.client.cookiejar;

import static java.lang.String.format;
import static com.ning.http.util.AsyncHttpProviderUtils.parseCookie;

import java.util.List;

import com.ning.http.client.Cookie;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.ResponseFilter;

final class CookieJarResponseFilter
    extends AbstractCookieJarFilter
    implements ResponseFilter
{

    private static final String SET_COOKIE = "Set-Cookie";

    public CookieJarResponseFilter(AbstractCookieJar cookieJar)
    {
        super( cookieJar );
    }

    @SuppressWarnings( "rawtypes" )
    public FilterContext filter( FilterContext ctx )
        throws FilterException
    {
        final List<String> cookiesString = ctx.getResponseHeaders().getHeaders().get( SET_COOKIE );

        if ( cookiesString != null && !cookiesString.isEmpty() )
        {
            String host = ctx.getRequest().getHeaders().getFirstValue( HOST );

            for ( String cookieValue : cookiesString )
            {
                Cookie currentCookie = parseCookie( cookieValue );

                try
                {
                    cookieJar.store( host, currentCookie );
                }
                catch ( Exception e )
                {
                    throw new FilterException( format( "Impossible to store cookie %s: %s", cookieValue, e.getMessage() ),
                                               e );
                }
            }
        }

        return ctx;
    }

}
