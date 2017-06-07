/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.extras.retrofit;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.testng.Assert;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.util.Collection;

/**
 * All tests in this test suite are disabled, because they call functionality of github service that is
 * rate-limited.
 */
@Slf4j
public class AsyncHttpRetrofitIntegrationTest {
    private static final String OWNER = "AsyncHttpClient";
    private static final String REPO = "async-http-client";

    protected static AsyncHttpClient httpClient = createHttpClient();

    private static AsyncHttpClient createHttpClient() {
        val config = new DefaultAsyncHttpClientConfig.Builder()
                .setCompressionEnforced(true)
                .setTcpNoDelay(true)
                .setKeepAlive(true)
                .setPooledConnectionIdleTimeout(120_000)
                .setFollowRedirect(true)
                .setMaxRedirects(5)
                .build();

        return new DefaultAsyncHttpClient(config);
    }

    @AfterSuite
    void cleanup() throws IOException {
        httpClient.close();
    }

    @Test(enabled = false)
    public void testSynchronousService() throws IOException {
        // given
        val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
        val retrofit = createRetrofitBuilder()
                .callFactory(callFactory)
                .build();
        val service = retrofit.create(TestServices.GithubSync.class);

        // when:
        val contributors = service.contributors(OWNER, REPO).execute().body();

        // then
        assertContributors(contributors);
    }

    @Test(enabled = false, dataProvider = "testRxJava1Service")
    public void testRxJava1Service(RxJavaCallAdapterFactory rxJavaCallAdapterFactory) {
        // given
        val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
        val retrofit = createRetrofitBuilder()
                .addCallAdapterFactory(rxJavaCallAdapterFactory)
                .callFactory(callFactory)
                .build();
        val service = retrofit.create(TestServices.GithubRxJava1.class);

        // when
        val contributors = service.contributors(OWNER, REPO)
                .subscribeOn(Schedulers.io())
                .toBlocking()
                .first();

        // then
        assertContributors(contributors);
    }

    @DataProvider(name = "testRxJava1Service")
    Object[][] testRxJava1ServiceDataProvider() {
        return new Object[][]{
                {RxJavaCallAdapterFactory.create()},
                {RxJavaCallAdapterFactory.createAsync()},
                {RxJavaCallAdapterFactory.createWithScheduler(Schedulers.io())},
                {RxJavaCallAdapterFactory.createWithScheduler(Schedulers.computation())},
                {RxJavaCallAdapterFactory.createWithScheduler(Schedulers.trampoline())},
        };
    }

    @Test(enabled = false, dataProvider = "testRxJava2Service")
    public void testRxJava2Service(RxJava2CallAdapterFactory rxJava2CallAdapterFactory) {
        // given
        val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
        val retrofit = createRetrofitBuilder()
                .addCallAdapterFactory(rxJava2CallAdapterFactory)
                .callFactory(callFactory)
                .build();
        val service = retrofit.create(TestServices.GithubRxJava2.class);

        // when
        val contributors = service.contributors(OWNER, REPO)
                .subscribeOn(io.reactivex.schedulers.Schedulers.computation())
                .blockingGet();

        // then
        assertContributors(contributors);
    }

    @DataProvider(name = "testRxJava2Service")
    Object[][] testRxJava2ServiceDataProvider() {
        return new Object[][]{
                {RxJava2CallAdapterFactory.create()},
                {RxJava2CallAdapterFactory.createAsync()},
                {RxJava2CallAdapterFactory.createWithScheduler(io.reactivex.schedulers.Schedulers.io())},
                {RxJava2CallAdapterFactory.createWithScheduler(io.reactivex.schedulers.Schedulers.computation())},
                {RxJava2CallAdapterFactory.createWithScheduler(io.reactivex.schedulers.Schedulers.trampoline())},
        };
    }

    @Test(enabled = false)
    void testGoogle() throws IOException {
        // given
        val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
        val retrofit = createRetrofitBuilder()
                .callFactory(callFactory)
                .baseUrl("http://www.google.com/")
                .build();
        val service = retrofit.create(TestServices.Google.class);

        // when
        val result = service.getHomePage().execute().body();
        log.debug("Received: {} bytes of output", result.length());

        // then
        Assert.assertNotNull(result, "Response body should not be null.");
        Assert.assertTrue(result.length() > 1_000, "Response body is too short.");
    }

    private Retrofit.Builder createRetrofitBuilder() {
        return new Retrofit.Builder()
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create())
                .validateEagerly(true)
                .baseUrl("https://api.github.com/");
    }

    private void assertContributors(Collection<TestServices.Contributor> contributors) {
        Assert.assertNotNull(contributors, "Contributors should not be null.");
        log.info("Contributors: {} ->\n  {}", contributors.size(), contributors);
        Assert.assertTrue(contributors.size() >= 30, "There should be at least 30 contributors.");
        contributors.forEach(e -> {
            Assert.assertNotNull(e, "Contributor element should not be null");
        });
    }
}
