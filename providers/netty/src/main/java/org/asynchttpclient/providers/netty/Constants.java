package org.asynchttpclient.providers.netty;

import java.nio.charset.Charset;

public class Constants {

    // FIXME what to do with this???
    public final static int MAX_BUFFERED_BYTES = 8192;
    
    // FIXME move to API module
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private Constants() {
    }
}
