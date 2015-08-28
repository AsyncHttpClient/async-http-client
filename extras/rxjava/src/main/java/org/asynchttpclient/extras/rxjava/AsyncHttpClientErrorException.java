package org.asynchttpclient.extras.rxjava;

/**
 *
 */
public class AsyncHttpClientErrorException extends RuntimeException {

    public AsyncHttpClientErrorException(String message) {
        super(message);
    }

    public AsyncHttpClientErrorException(String message, Throwable t) {
        super(message, t);
    }
}
