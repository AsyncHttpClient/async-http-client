package org.asynchttpclient;

import java.io.IOException;

public class TestAsyncHttpClient implements AsyncHttpClient {

    public TestAsyncHttpClient() {

    }

    public TestAsyncHttpClient(AsyncHttpProvider provider) {

    }

    public TestAsyncHttpClient(AsyncHttpClientConfig config) {

    }

    public TestAsyncHttpClient(String providerClass, AsyncHttpClientConfig config) {

    }

    public TestAsyncHttpClient(AsyncHttpProvider httpProvider, AsyncHttpClientConfig config) {
    }

    @Override
    public AsyncHttpProvider getProvider() {
        return null;
    }

    @Override
    public void close() {

    }

    @Override
    public void closeAsynchronously() {

    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public AsyncHttpClientConfig getConfig() {
        return null;
    }

    @Override
    public AsyncHttpClient setSignatureCalculator(SignatureCalculator signatureCalculator) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareGet(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareConnect(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareOptions(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareHead(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder preparePost(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder preparePut(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareDelete(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder preparePatch(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareTrace(String url) {
        return null;
    }

    @Override
    public BoundRequestBuilder prepareRequest(Request request) {
        return null;
    }

    @Override
    public <T> ListenableFuture<T> executeRequest(Request request, AsyncHandler<T> handler) throws IOException {
        return null;
    }

    @Override
    public ListenableFuture<Response> executeRequest(Request request) throws IOException {
        return null;
    }

}
