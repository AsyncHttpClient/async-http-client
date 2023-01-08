/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.request.body;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_FILE;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class InputStreamPartLargeFileTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse resp) throws IOException {

                ServletInputStream in = req.getInputStream();
                byte[] b = new byte[8192];

                int count;
                int total = 0;
                while ((count = in.read(b)) != -1) {
                    b = new byte[8192];
                    total += count;
                }
                resp.setStatus(200);
                resp.addHeader("X-TRANSFERRED", String.valueOf(total));
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                baseRequest.setHandled(true);
            }
        };
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutImageFile() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            InputStream inputStream = new BufferedInputStream(new FileInputStream(LARGE_IMAGE_FILE));
            Response response = client.preparePut(getTargetUrl()).addBodyPart(new InputStreamPart("test", inputStream, LARGE_IMAGE_FILE.getName(),
                    LARGE_IMAGE_FILE.length(), "application/octet-stream", UTF_8)).execute().get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutImageFileUnknownSize() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            InputStream inputStream = new BufferedInputStream(new FileInputStream(LARGE_IMAGE_FILE));
            Response response = client.preparePut(getTargetUrl()).addBodyPart(new InputStreamPart("test", inputStream, LARGE_IMAGE_FILE.getName(),
                    -1, "application/octet-stream", UTF_8)).execute().get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutLargeTextFile() throws Exception {
        File file = createTempFile(1024 * 1024);
        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));

        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl())
                    .addBodyPart(new InputStreamPart("test", inputStream, file.getName(), file.length(),
                            "application/octet-stream", UTF_8)).execute().get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutLargeTextFileUnknownSize() throws Exception {
        File file = createTempFile(1024 * 1024);
        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));

        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
            Response response = client.preparePut(getTargetUrl())
                    .addBodyPart(new InputStreamPart("test", inputStream, file.getName(), -1,
                            "application/octet-stream", UTF_8)).execute().get();
            assertEquals(200, response.getStatusCode());
        }
    }
}
