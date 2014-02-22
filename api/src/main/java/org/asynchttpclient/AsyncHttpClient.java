package org.asynchttpclient;

import java.io.IOException;
import java.util.concurrent.Future;

import org.asynchttpclient.AsyncHttpClientImpl.BoundRequestBuilder;

public interface AsyncHttpClient {

	/**
	 * Return the asynchronous {@link AsyncHttpProvider}
	 *
	 * @return an {@link AsyncHttpProvider}
	 */
	public  AsyncHttpProvider getProvider();

	/**
	 * Close the underlying connections.
	 */
	public  void close();

	/**
	 * Asynchronous close the {@link AsyncHttpProvider} by spawning a thread and avoid blocking.
	 */
	public  void closeAsynchronously();

	/**
	 * Return true if closed
	 *
	 * @return true if closed
	 */
	public  boolean isClosed();

	/**
	 * Return the {@link AsyncHttpClientConfig}
	 *
	 * @return {@link AsyncHttpClientConfig}
	 */
	public  AsyncHttpClientConfig getConfig();

	/**
	 * Set default signature calculator to use for requests build by this client instance
	 */
	public  AsyncHttpClient setSignatureCalculator(
			SignatureCalculator signatureCalculator);

	/**
	 * Prepare an HTTP client GET request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareGet(String url);

	/**
	 * Prepare an HTTP client CONNECT request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareConnect(String url);

	/**
	 * Prepare an HTTP client OPTIONS request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareOptions(String url);

	/**
	 * Prepare an HTTP client HEAD request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareHead(String url);

	/**
	 * Prepare an HTTP client POST request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder preparePost(String url);

	/**
	 * Prepare an HTTP client PUT request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder preparePut(String url);

	/**
	 * Prepare an HTTP client DELETE request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareDelete(String url);

	/**
	 * Prepare an HTTP client PATCH request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder preparePatch(String url);

	/**
	 * Prepare an HTTP client TRACE request.
	 *
	 * @param url A well formed URL.
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareTrace(String url);

	/**
	 * Construct a {@link RequestBuilder} using a {@link Request}
	 *
	 * @param request a {@link Request}
	 * @return {@link RequestBuilder}
	 */
	public  BoundRequestBuilder prepareRequest(Request request);

	/**
	 * Execute an HTTP request.
	 *
	 * @param request {@link Request}
	 * @param handler an instance of {@link AsyncHandler}
	 * @param <T>     Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
	 * @return a {@link Future} of type T
	 * @throws IOException
	 */
	public  <T> ListenableFuture<T> executeRequest(Request request,
			AsyncHandler<T> handler) throws IOException;

	/**
	 * Execute an HTTP request.
	 *
	 * @param request {@link Request}
	 * @return a {@link Future} of type Response
	 * @throws IOException
	 */
	public  ListenableFuture<Response> executeRequest(Request request)
			throws IOException;

}