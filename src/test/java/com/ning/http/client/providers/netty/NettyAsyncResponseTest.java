package com.ning.http.client.providers.netty;

// TODO license

import static org.testng.Assert.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.testng.annotations.Test;

import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseHeaders;

/**
 * @author Benjamin Hanzelmann
 */
public class NettyAsyncResponseTest  {
    
    @Test(groups="standalone")
    public void testCookieParseExpires() {
        // e.g. "Sun, 06-Feb-2011 03:45:24 GMT";
        SimpleDateFormat sdf = new SimpleDateFormat( "EEE, dd-MMM-yyyy HH:mm:ss z", Locale.US );
        sdf.setTimeZone( TimeZone.getTimeZone( "GMT" ) );
        
        Date date = new Date(System.currentTimeMillis() + 60000); // sdf.parse( dateString );
	    final String cookieDef = String.format("efmembercheck=true; expires=%s; path=/; domain=.eclipse.org", sdf.format( date ));
	    
	    NettyAsyncResponse response = new NettyAsyncResponse( new ResponseStatus(null, null, null), new HttpResponseHeaders(null, null, false)
        {
            @Override
            public FluentCaseInsensitiveStringsMap getHeaders()
            {
                return new FluentCaseInsensitiveStringsMap().add( "Set-Cookie", cookieDef );
            }
        }, null );
	    
	    List<Cookie> cookies = response.getCookies();
	    assertEquals(cookies.size(), 1);
	    
	    Cookie cookie = cookies.get(0);
	    assertTrue(cookie.getMaxAge() > 55 && cookie.getMaxAge() < 61, "");
    }
    
    @Test(groups="standalone")
    public void testCookieParseMaxAge() {
        final String cookieDef = "efmembercheck=true; max-age=60; path=/; domain=.eclipse.org";
        NettyAsyncResponse response = new NettyAsyncResponse( new ResponseStatus(null, null, null), new HttpResponseHeaders(null, null, false)
        {
            @Override
            public FluentCaseInsensitiveStringsMap getHeaders()
            {
                return new FluentCaseInsensitiveStringsMap().add( "Set-Cookie", cookieDef );
            }
        }, null );
	    List<Cookie> cookies = response.getCookies();
	    assertEquals(cookies.size(), 1);
	    
	    Cookie cookie = cookies.get(0);
	    assertEquals(cookie.getMaxAge(), 60);
    }
    
    @Test(groups="standalone")
    public void testCookieParseWeirdExpiresValue() {
        final String cookieDef = "efmembercheck=true; expires=60; path=/; domain=.eclipse.org";
        NettyAsyncResponse response = new NettyAsyncResponse( new ResponseStatus(null, null, null), new HttpResponseHeaders(null, null, false)
        {
            @Override
            public FluentCaseInsensitiveStringsMap getHeaders()
            {
                return new FluentCaseInsensitiveStringsMap().add( "Set-Cookie", cookieDef );
            }
        }, null );
        
	    List<Cookie> cookies = response.getCookies();
	    assertEquals(cookies.size(), 1);
	    
	    Cookie cookie = cookies.get(0);
	    assertEquals(cookie.getMaxAge(), 60);
    }
    
}
