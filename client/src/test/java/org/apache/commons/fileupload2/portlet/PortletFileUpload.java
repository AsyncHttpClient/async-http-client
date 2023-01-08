/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.fileupload2.portlet;

import org.apache.commons.fileupload2.FileItem;
import org.apache.commons.fileupload2.FileItemFactory;
import org.apache.commons.fileupload2.FileItemIterator;
import org.apache.commons.fileupload2.FileUpload;
import org.apache.commons.fileupload2.FileUploadBase;
import org.apache.commons.fileupload2.FileUploadException;

import javax.portlet.ActionRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * <p>High level API for processing file uploads.</p>
 *
 * <p>This class handles multiple files per single HTML widget, sent using
 * {@code multipart/mixed} encoding type, as specified by
 * <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>.  Use
 * {@link org.apache.commons.fileupload2.servlet.ServletFileUpload
 * #parseRequest(javax.servlet.http.HttpServletRequest)} to acquire a list
 * of {@link FileItem FileItems} associated
 * with a given HTML widget.</p>
 *
 * <p>How the data for individual parts is stored is determined by the factory
 * used to create them; a given part may be in memory, on disk, or somewhere
 * else.</p>
 *
 * @since 1.1
 */
public class PortletFileUpload extends FileUpload {

    // ---------------------------------------------------------- Class methods

    /**
     * Utility method that determines whether the request contains multipart
     * content.
     *
     * @param request The portlet request to be evaluated. Must be non-null.
     * @return {@code true} if the request is multipart;
     * {@code false} otherwise.
     */
    public static final boolean isMultipartContent(final ActionRequest request) {
        return FileUploadBase.isMultipartContent(new PortletRequestContext(request));
    }

    // ----------------------------------------------------------- Constructors

    /**
     * Constructs an uninitialized instance of this class. A factory must be
     * configured, using {@code setFileItemFactory()}, before attempting
     * to parse requests.
     *
     * @see FileUpload#FileUpload(FileItemFactory)
     */
    public PortletFileUpload() {
    }

    /**
     * Constructs an instance of this class which uses the supplied factory to
     * create {@code FileItem} instances.
     *
     * @param fileItemFactory The factory to use for creating file items.
     * @see FileUpload#FileUpload()
     */
    public PortletFileUpload(final FileItemFactory fileItemFactory) {
        super(fileItemFactory);
    }

    // --------------------------------------------------------- Public methods

    /**
     * Processes an <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>
     * compliant {@code multipart/form-data} stream.
     *
     * @param request The portlet request to be parsed.
     * @return A list of {@code FileItem} instances parsed from the
     * request, in the order that they were transmitted.
     * @throws FileUploadException if there are problems reading/parsing
     *                             the request or storing files.
     */
    public List<FileItem> parseRequest(final ActionRequest request) throws FileUploadException {
        return parseRequest(new PortletRequestContext(request));
    }

    /**
     * Processes an <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>
     * compliant {@code multipart/form-data} stream.
     *
     * @param request The portlet request to be parsed.
     * @return A map of {@code FileItem} instances parsed from the request.
     * @throws FileUploadException if there are problems reading/parsing
     *                             the request or storing files.
     * @since 1.3
     */
    public Map<String, List<FileItem>> parseParameterMap(final ActionRequest request) throws FileUploadException {
        return parseParameterMap(new PortletRequestContext(request));
    }

    /**
     * Processes an <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>
     * compliant {@code multipart/form-data} stream.
     *
     * @param request The portlet request to be parsed.
     * @return An iterator to instances of {@code FileItemStream}
     * parsed from the request, in the order that they were
     * transmitted.
     * @throws FileUploadException if there are problems reading/parsing
     *                             the request or storing files.
     * @throws IOException         An I/O error occurred. This may be a network
     *                             error while communicating with the client or a problem while
     *                             storing the uploaded content.
     */
    public FileItemIterator getItemIterator(final ActionRequest request) throws FileUploadException, IOException {
        return super.getItemIterator(new PortletRequestContext(request));
    }
}
