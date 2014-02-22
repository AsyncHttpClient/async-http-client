package org.asynchttpclient;

public class AsyncHttpClientImplException extends RuntimeException {

    public AsyncHttpClientImplException(String msg) {
        super(msg);
    }

    public AsyncHttpClientImplException(String msg, Exception e) {
        super(msg, e);
    }

}
