package com.ning.http.client.cookiejar;

import static java.lang.String.format;

import java.util.Collection;

import com.ning.http.client.Cookie;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.RequestFilter;

final class CookieJarRequestFilter
    extends AbstractCookieJarFilter
    implements RequestFilter
{

    public CookieJarRequestFilter(AbstractCookieJar cookieJar)
    {
        super( cookieJar );
    }

    @SuppressWarnings( "rawtypes" )
    public final FilterContext filter( FilterContext ctx )
        throws FilterException
    {
        String domain = "";

        Collection<Cookie> cookies = null;
        try
        {
            cookies = cookieJar.retrieve( domain );
        }
        catch ( Exception e )
        {
            throw new FilterException( format( "Impossible to retrieve cookies from domain %s: %s",
                                               domain, e.getMessage() ),
                                       e );
        }

        if ( cookies == null || cookies.isEmpty() )
        {
            return ctx;
        }

        final RequestBuilder requestBuilder = new RequestBuilder( ctx.getRequest() );

        for ( Cookie cookie : cookies )
        {
            requestBuilder.addCookie( cookie );
        }

        return new FilterContext.FilterContextBuilder( ctx )
                                .request( requestBuilder.build() )
                                .build();
    }

}
