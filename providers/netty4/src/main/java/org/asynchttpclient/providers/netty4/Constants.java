package org.asynchttpclient.providers.netty4;

import java.nio.charset.Charset;

public class Constants {

    // FIXME move into a state class along with isClose
    public static final ThreadLocal<Boolean> IN_IO_THREAD = new ThreadLocalBoolean();
    
    // FIXME what to do with this???
    public final static int MAX_BUFFERED_BYTES = 8192;
    
    // FIXME move to API module
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private Constants() {
    }
}
