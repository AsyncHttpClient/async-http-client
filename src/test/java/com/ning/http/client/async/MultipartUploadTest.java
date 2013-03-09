/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ByteArrayPart;
import com.ning.http.client.FilePart;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.StringPart;
import com.ning.http.util.AsyncHttpProviderUtils;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * @author dominict
 */
public abstract class MultipartUploadTest extends AbstractBasicTest {
    private String BASE_URL;

    private String servletEndpointRedirectUrl;
    public static byte GZIPTEXT[] = new byte[] { 31, -117, 8, 8, 11, 43, 79, 75, 0, 3, 104, 101, 108, 108, 111, 46, 116, 120, 116, 0, -53, 72, -51, -55, -55, -25, 2, 0, 32, 48, 58, 54, 6, 0, 0, 0 };

    @BeforeClass
    public void setUp() throws Exception {
        server = new Server();

        port1 = findFreePort();

        Connector listener = new SelectChannelConnector();
        listener.setHost("localhost");
        listener.setPort(port1);

        server.addConnector(listener);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new MockMultipartUploadServlet()), "/upload/*");

        server.setHandler(context);
        server.start();

        servletEndpointRedirectUrl = "http://localhost" + ":" + port1;
    }

    @AfterClass
    public void stop() {
        try {

            if (server != null) {
                server.stop();
            }

        } catch (Exception e) {
            System.err.print("Error stopping servlet tester");
            e.printStackTrace();
        }

    }

    private File getClasspathFile(String file) throws FileNotFoundException {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
        }
        if (cl == null) {
            cl = MultipartUploadTest.class.getClassLoader();
        }
        URL resourceUrl = cl.getResource(file);

        try {
            return new File(new URI(resourceUrl.toString()).getSchemeSpecificPart());
        } catch (URISyntaxException e) {
            throw new FileNotFoundException(file);
        }
    }

    /**
     * Tests that the streaming of a file works.
     */
    @Test(enabled = true)
    public void testSendingSmallFilesAndByteArray() {
        String expectedContents = "filecontent: hello";
        String expectedContents2 = "gzipcontent: hello";
        String expectedContents3 = "filecontent: hello2";
        String testResource1 = "textfile.txt";
        String testResource2 = "gzip.txt.gz";
        String testResource3 = "textfile2.txt";

        File testResource1File = null;
        try {
            testResource1File = getClasspathFile(testResource1);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource1);
        }

        File testResource2File = null;
        try {
            testResource2File = getClasspathFile(testResource2);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource2);
        }

        File testResource3File = null;
        try {
            testResource3File = getClasspathFile(testResource3);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource3);
        }

        List<File> testFiles = new ArrayList<File>();
        testFiles.add(testResource1File);
        testFiles.add(testResource2File);
        testFiles.add(testResource3File);

        List<String> expected = new ArrayList<String>();
        expected.add(expectedContents);
        expected.add(expectedContents2);
        expected.add(expectedContents3);

        List<Boolean> gzipped = new ArrayList<Boolean>();
        gzipped.add(false);
        gzipped.add(true);
        gzipped.add(false);

        boolean tmpFileCreated = false;
        File tmpFile = null;
        FileOutputStream os = null;
        try {
            tmpFile = File.createTempFile("textbytearray", ".txt");
            os = new FileOutputStream(tmpFile);
            IOUtils.write(expectedContents.getBytes("UTF-8"), os);
            tmpFileCreated = true;

            testFiles.add(tmpFile);
            expected.add(expectedContents);
            gzipped.add(false);

        } catch (FileNotFoundException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } finally {
            if (os != null) {
                IOUtils.closeQuietly(os);
            }
        }

        if (!tmpFileCreated) {
            fail("Unable to test ByteArrayMultiPart, as unable to write to filesystem the tmp test content");
        }

        AsyncHttpClientConfig.Builder bc = new AsyncHttpClientConfig.Builder();

        bc.setFollowRedirects(true);

        AsyncHttpClient c = new AsyncHttpClient(bc.build());

        try {

            RequestBuilder builder = new RequestBuilder("POST");
            builder.setUrl(servletEndpointRedirectUrl + "/upload/bob");
            builder.addBodyPart(new FilePart("file1", testResource1File, "text/plain", "UTF-8"));
            builder.addBodyPart(new FilePart("file2", testResource2File, "application/x-gzip", null));
            builder.addBodyPart(new StringPart("Name", "Dominic"));
            builder.addBodyPart(new FilePart("file3", testResource3File, "text/plain", "UTF-8"));

            builder.addBodyPart(new StringPart("Age", "3", AsyncHttpProviderUtils.DEFAULT_CHARSET));
            builder.addBodyPart(new StringPart("Height", "shrimplike", AsyncHttpProviderUtils.DEFAULT_CHARSET));
            builder.addBodyPart(new StringPart("Hair", "ridiculous", AsyncHttpProviderUtils.DEFAULT_CHARSET));

            builder.addBodyPart(new ByteArrayPart("file4", "bytearray.txt", expectedContents.getBytes("UTF-8"), "text/plain", "UTF-8"));

            com.ning.http.client.Request r = builder.build();

            Response res = c.executeRequest(r).get();

            assertEquals(200, res.getStatusCode());

            testSentFile(expected, testFiles, res, gzipped);

        } catch (Exception e) {
            e.printStackTrace();
            fail("Download Exception");
        } finally {
            c.close();
            FileUtils.deleteQuietly(tmpFile);
        }
    }

    /**
     * Test that the files were sent, based on the response from the servlet
     * 
     * @param expectedContents
     * @param sourceFiles
     * @param r
     * @param deflate
     */
    private void testSentFile(List<String> expectedContents, List<File> sourceFiles, Response r, List<Boolean> deflate) {
        String content = null;
        try {
            content = r.getResponseBody();
            assertNotNull("===>" + content);
            System.out.println(content);
        } catch (IOException e) {
            fail("Unable to obtain content");
        }

        String[] contentArray = content.split("\\|\\|");
        // TODO: this fail on win32
        assertEquals(2, contentArray.length);

        String tmpFiles = contentArray[1];
        assertNotNull(tmpFiles);
        assertTrue(tmpFiles.trim().length() > 2);
        tmpFiles = tmpFiles.substring(1, tmpFiles.length() - 1);

        String[] responseFiles = tmpFiles.split(",");
        assertNotNull(responseFiles);
        assertEquals(sourceFiles.size(), responseFiles.length);

        System.out.println(Arrays.toString(responseFiles));
        // assertTrue("File should exist: " + tmpFile.getAbsolutePath(),tmpFile.exists());

        int i = 0;
        for (File sourceFile : sourceFiles) {

            FileInputStream instream = null;
            File tmp = null;
            try {

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] sourceBytes = null;
                try {
                    instream = new FileInputStream(sourceFile);
                    byte[] buf = new byte[8092];
                    int len = 0;
                    while ((len = instream.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                    System.out.println("================");
                    System.out.println("Length of file: " + baos.toByteArray().length);
                    System.out.println("Contents: " + Arrays.toString(baos.toByteArray()));
                    System.out.println("================");
                    System.out.flush();
                    sourceBytes = baos.toByteArray();
                } finally {
                    IOUtils.closeQuietly(instream);
                }

                tmp = new File(responseFiles[i].trim());
                System.out.println("==============================");
                System.out.println(tmp.getAbsolutePath());
                System.out.println("==============================");
                System.out.flush();
                assertTrue(tmp.exists());

                instream = new FileInputStream(tmp);
                ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
                byte[] buf = new byte[8092];
                int len = 0;
                while ((len = instream.read(buf)) > 0) {
                    baos2.write(buf, 0, len);
                }
                IOUtils.closeQuietly(instream);

                assertEquals(sourceBytes, baos2.toByteArray());

                if (!deflate.get(i)) {

                    String helloString = new String(baos2.toByteArray());
                    assertEquals(expectedContents.get(i), helloString);
                } else {
                    instream = new FileInputStream(tmp);

                    GZIPInputStream deflater = new GZIPInputStream(instream);
                    ByteArrayOutputStream baos3 = new ByteArrayOutputStream();
                    byte[] buf3 = new byte[8092];
                    int len3 = 0;
                    while ((len3 = deflater.read(buf3)) > 0) {
                        baos3.write(buf3, 0, len3);
                    }

                    String helloString = new String(baos3.toByteArray());

                    assertEquals(expectedContents.get(i), helloString);

                }
            } catch (Exception e) {
                e.printStackTrace();
                fail("Download Exception");
            } finally {
                if (tmp != null)
                    FileUtils.deleteQuietly(tmp);
                IOUtils.closeQuietly(instream);
                i++;
            }
        }
    }

    /**
     * Takes the content that is being passed to it, and streams to a file on disk
     * 
     * @author dominict
     */
    public static class MockMultipartUploadServlet extends HttpServlet {
        /**
         *
         */
        private static final long serialVersionUID = 1L;
        private int filesProcessed = 0;
        private int stringsProcessed = 0;

        public MockMultipartUploadServlet() {

        }

        public synchronized void resetFilesProcessed() {
            filesProcessed = 0;
        }

        private synchronized int incrementFilesProcessed() {
            return ++filesProcessed;

        }

        public int getFilesProcessed() {
            return filesProcessed;
        }

        public synchronized void resetStringsProcessed() {
            stringsProcessed = 0;
        }

        private synchronized int incrementStringsProcessed() {
            return ++stringsProcessed;

        }

        public int getStringsProcessed() {
            return stringsProcessed;
        }

        @Override
        public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            // Check that we have a file upload request
            boolean isMultipart = ServletFileUpload.isMultipartContent(request);
            if (isMultipart) {
                List<String> files = new ArrayList<String>();
                ServletFileUpload upload = new ServletFileUpload();
                // Parse the request
                FileItemIterator iter = null;
                try {
                    iter = upload.getItemIterator(request);
                    while (iter.hasNext()) {
                        FileItemStream item = iter.next();
                        String name = item.getFieldName();
                        InputStream stream = null;
                        try {
                            stream = item.openStream();

                            if (item.isFormField()) {
                                System.out.println("Form field " + name + " with value " + Streams.asString(stream) + " detected.");
                                incrementStringsProcessed();
                            } else {
                                System.out.println("File field " + name + " with file name " + item.getName() + " detected.");
                                // Process the input stream
                                OutputStream os = null;
                                try {
                                    File tmpFile = File.createTempFile(UUID.randomUUID().toString() + "_MockUploadServlet", ".tmp");
                                    tmpFile.deleteOnExit();
                                    os = new FileOutputStream(tmpFile);
                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    while ((bytesRead = stream.read(buffer)) != -1) {
                                        os.write(buffer, 0, bytesRead);
                                    }
                                    incrementFilesProcessed();
                                    files.add(tmpFile.getAbsolutePath());
                                } finally {
                                    IOUtils.closeQuietly(os);
                                }
                            }
                        } finally {
                            IOUtils.closeQuietly(stream);
                        }
                    }
                } catch (FileUploadException e) {

                }
                Writer w = response.getWriter();
                w.write(Integer.toString(getFilesProcessed()));
                resetFilesProcessed();
                resetStringsProcessed();
                w.write("||");
                w.write(files.toString());
                w.close();
            } else {
                Writer w = response.getWriter();
                w.write(Integer.toString(getFilesProcessed()));
                resetFilesProcessed();
                resetStringsProcessed();
                w.write("||");
                w.close();
            }

        }

    }

}
