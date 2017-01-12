package org.asynchttpclient.ws;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * A listener for result of WebSocket write operations.
 */
public interface WebSocketWriteCompleteListener extends FutureListener<Void> {

    /**
     * Is called when a write operation completes, either successful or failing with an exception.
     * @param result contains the result of the write operation
     */
    void onComplete(WriteCompleteResult result);

    @Override
    default void operationComplete(Future<Void> future) throws Exception {
        if (future.isSuccess()) {
            onComplete(WriteCompleteResult.SUCCEEDED);
        } else {
            onComplete(WriteCompleteResult.failed(future.cause()));
        }
    }

    /**
     * The result of a write operation.
     */
    interface WriteCompleteResult {

        /**
         * Constant for succeeded result.
         */
        WriteCompleteResult SUCCEEDED = new WriteCompleteResult() {
            @Override public Throwable getFailure() {
                return null;
            }

            @Override public boolean isSuccess() {
                return true;
            }

            @Override public boolean isFailed() {
                return false;
            }
        };

        /**
         * @param t the exception that caused the failure.
         * @return a failed result
         */
        static WriteCompleteResult failed(Throwable t)
        {
            return new WriteCompleteResult() {
                @Override public Throwable getFailure() {
                    return t;
                }

                @Override public boolean isSuccess() {
                    return false;
                }

                @Override public boolean isFailed() {
                    return true;
                }
            };
        }

        /**
         * Return the exception in case the write operation failed, @{@code null} otherwise.
         * @return the exception
         */
        Throwable getFailure();

        /**
         * Return @{@code true} if the operation succeeded, {@code false} otherwise.
         * @return true if success.
         */
        boolean isSuccess();

        /**
         * Return @{@code true} if the operation failed, {@code false} otherwise.
         * @return true if failed.
         */
        boolean isFailed();
    }
}
