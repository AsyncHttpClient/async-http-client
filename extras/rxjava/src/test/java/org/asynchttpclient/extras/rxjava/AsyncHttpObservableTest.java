package org.asynchttpclient.extras.rxjava;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.Response;
import org.testng.annotations.Test;
import rx.Observable;
import rx.functions.Func0;
import rx.observers.TestSubscriber;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 *
 */
public class AsyncHttpObservableTest {

    @Test(groups = "fast")
    public void testToObservableNoAbortNoError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.toObservable(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            tester.assertNoErrors();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 1);
            assertEquals(responses.get(0).getStatusCode(), 200);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testToObservableNoAbortError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.toObservable(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com/ttfn");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 1);
            assertEquals(responses.get(0).getStatusCode(), 404);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testToObservableAbortNoError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.toObservable(true, new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 1);
            assertEquals(responses.get(1).getStatusCode(), 200);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testToObservableAbortError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.toObservable(true, new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com/ttfn");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertError(AsyncHttpClientErrorException.class);
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 0);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testObserveNoAbortNoError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.observe(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            tester.assertNoErrors();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 1);
            assertEquals(responses.get(0).getStatusCode(), 200);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testObserveNoAbortError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.observe(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com/ttfn");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 1);
            assertEquals(responses.get(0).getStatusCode(), 404);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testObserveAbortNoError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.observe(true, new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 1);
            assertEquals(responses.get(1).getStatusCode(), 200);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testObserveAbortError() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.observe(true, new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com/ttfn");
                }
            });
            o1.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertError(AsyncHttpClientErrorException.class);
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 0);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test(groups = "fast")
    public void testObserveMultiple() {
        final TestSubscriber<Response> tester = new TestSubscriber<>();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Observable<Response> o1 = AsyncHttpObservable.observe(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.ning.com");
                }
            });
            Observable<Response> o2 = AsyncHttpObservable.observe(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.wisc.edu").setFollowRedirect(true);
                }
            });
            Observable<Response> o3 = AsyncHttpObservable.observe(new Func0<BoundRequestBuilder>() {
                @Override
                public BoundRequestBuilder call() {
                    return client.prepareGet("http://www.umn.edu").setFollowRedirect(true);
                }
            });
            Observable<Response> all = Observable.merge(o1, o2, o3);
            all.subscribe(tester);
            tester.awaitTerminalEvent();
            tester.assertTerminalEvent();
            tester.assertCompleted();
            tester.assertNoErrors();
            List<Response> responses = tester.getOnNextEvents();
            assertNotNull(responses);
            assertEquals(responses.size(), 3);
            for (Response response : responses) {
                assertEquals(response.getStatusCode(), 200);
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

}
