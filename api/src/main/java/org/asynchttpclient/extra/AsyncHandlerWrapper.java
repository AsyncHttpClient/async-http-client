package org.asynchttpclient.extra;

import java.util.concurrent.Semaphore;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncHandlerWrapper<T> implements AsyncHandler<T> {

	private final static Logger logger = LoggerFactory.getLogger(AsyncHandlerWrapper.class);
	private final AsyncHandler<T> asyncHandler;
	private final Semaphore available;

	public AsyncHandlerWrapper(AsyncHandler<T> asyncHandler, Semaphore available) {
		this.asyncHandler = asyncHandler;
		this.available = available;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onThrowable(Throwable t) {
		try {
			asyncHandler.onThrowable(t);
		} finally {
			available.release();
			if (logger.isDebugEnabled()) {
				logger.debug("Current Throttling Status after onThrowable {}", available.availablePermits());
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
		return asyncHandler.onBodyPartReceived(bodyPart);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
		return asyncHandler.onStatusReceived(responseStatus);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
		return asyncHandler.onHeadersReceived(headers);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public T onCompleted() throws Exception {
		available.release();
		if (logger.isDebugEnabled()) {
			logger.debug("Current Throttling Status {}", available.availablePermits());
		}
		return asyncHandler.onCompleted();
	}
}