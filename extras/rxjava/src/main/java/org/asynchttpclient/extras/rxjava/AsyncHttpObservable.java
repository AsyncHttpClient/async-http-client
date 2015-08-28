package org.asynchttpclient.extras.rxjava;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func0;
import rx.subjects.ReplaySubject;

/**
 * Provide RxJava support for executing requests. Request can be subscribed to and manipulated as needed.
 * @See <a href="https://github.com/ReactiveX/RxJava" />
 */
public class AsyncHttpObservable {

    /**
     * Observe a request execution and emit the full response no matter what.
     *
     * @param supplier
     * @return The cold observable (must be subscribed to in order to execute).
     */
    public static Observable<Response> toObservable(final Func0<BoundRequestBuilder> supplier) {
        return toObservable(false, supplier);
    }

    /**
     * Observe a request execution and emit an error for http status error codes >= 400.
     *
     * @param abortOnErrorStatus
     * @param supplier
     * @return The cold observable (must be subscribed to in order to execute).
     */
    public static Observable<Response> toObservable(final Boolean abortOnErrorStatus, final Func0<BoundRequestBuilder> supplier) {

        //Get the builder from the function
        final BoundRequestBuilder builder = supplier.call();

        //create the observable from scratch
        return Observable.create(new Observable.OnSubscribe<Response>() {

            @Override
            public void call(final Subscriber<? super Response> subscriber) {
                try {
                    AsyncCompletionHandler<Void> handler = new AsyncCompletionHandler<Void>() {
                        @Override
                        public State onStatusReceived(HttpResponseStatus status) throws Exception {
                            State state = super.onStatusReceived(status);
                            if (abortOnErrorStatus) {
                                int code = status.getStatusCode();
                                if (code >= 400) {
                                    state = State.ABORT;
                                    subscriber.onError(new AsyncHttpClientErrorException(String.format("Client error status code: %s", code)));
                                }
                            }
                            return state;
                        }

                        @Override
                        public Void onCompleted(Response response) throws Exception {
                            subscriber.onNext(response);
                            subscriber.onCompleted();
                            return null;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            subscriber.onError(t);
                        }
                    };
                    //execute the request
                    builder.execute(handler);
                } catch (Throwable t) {
                    subscriber.onError(t);
                }
            }

        });

    }

    /**
     * Observe a request execution and emit the full response no matter what.
     *
     * @param supplier
     * @return The hot observable (eagerly executes).
     */
    public static Observable<Response> observe(final Func0<BoundRequestBuilder> supplier) {
        return observe(false, supplier);
    }

    /**
     * Observe a request execution and emit an error for http status error codes >= 400.
     *
     * @param abortOnErrorStatus
     * @param supplier
     * @return The hot observable (eagerly executes).
     */
    public static Observable<Response> observe(final Boolean abortOnErrorStatus, final Func0<BoundRequestBuilder> supplier) {
        //use a ReplaySubject to buffer the eagerly subscribed-to Observable
        ReplaySubject<Response> subject = ReplaySubject.create();
        //eagerly kick off subscription
        toObservable(abortOnErrorStatus, supplier).subscribe(subject);
        //return the subject that can be subscribed to later while the execution has already started
        return subject;
    }

}
