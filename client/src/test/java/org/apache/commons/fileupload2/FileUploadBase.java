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
package org.apache.commons.fileupload2;

import org.apache.commons.fileupload2.impl.FileItemIteratorImpl;
import org.apache.commons.fileupload2.pub.FileUploadIOException;
import org.apache.commons.fileupload2.pub.IOFileUploadException;
import org.apache.commons.fileupload2.util.FileItemHeadersImpl;
import org.apache.commons.fileupload2.util.Streams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.lang.String.format;

/**
 * <p>High level API for processing file uploads.</p>
 *
 * <p>This class handles multiple files per single HTML widget, sent using
 * {@code multipart/mixed} encoding type, as specified by
 * <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>.  Use {@link
 * #parseRequest(RequestContext)} to acquire a list of {@link
 * FileItem}s associated with a given HTML
 * widget.</p>
 *
 * <p>How the data for individual parts is stored is determined by the factory
 * used to create them; a given part may be in memory, on disk, or somewhere
 * else.</p>
 */
public abstract class FileUploadBase {

    // ---------------------------------------------------------- Class methods

    /**
     * <p>Utility method that determines whether the request contains multipart
     * content.</p>
     *
     * <p><strong>NOTE:</strong>This method will be moved to the
     * {@code ServletFileUpload} class after the FileUpload 1.1 release.
     * Unfortunately, since this method is static, it is not possible to
     * provide its replacement until this method is removed.</p>
     *
     * @param ctx The request context to be evaluated. Must be non-null.
     * @return {@code true} if the request is multipart;
     * {@code false} otherwise.
     */
    public static final boolean isMultipartContent(final RequestContext ctx) {
        final String contentType = ctx.getContentType();
        if (contentType == null) {
            return false;
        }
        return contentType.toLowerCase(Locale.ENGLISH).startsWith(MULTIPART);
    }

    // ----------------------------------------------------- Manifest constants

    /**
     * HTTP content type header name.
     */
    public static final String CONTENT_TYPE = "Content-type";

    /**
     * HTTP content disposition header name.
     */
    public static final String CONTENT_DISPOSITION = "Content-disposition";

    /**
     * HTTP content length header name.
     */
    public static final String CONTENT_LENGTH = "Content-length";

    /**
     * Content-disposition value for form data.
     */
    public static final String FORM_DATA = "form-data";

    /**
     * Content-disposition value for file attachment.
     */
    public static final String ATTACHMENT = "attachment";

    /**
     * Part of HTTP content type header.
     */
    public static final String MULTIPART = "multipart/";

    /**
     * HTTP content type header for multipart forms.
     */
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";

    /**
     * HTTP content type header for multiple uploads.
     */
    public static final String MULTIPART_MIXED = "multipart/mixed";

    /**
     * The maximum length of a single header line that will be parsed
     * (1024 bytes).
     *
     * @deprecated This constant is no longer used. As of commons-fileupload
     * 1.2, the only applicable limit is the total size of a parts headers,
     * {@link MultipartStream#HEADER_PART_SIZE_MAX}.
     */
    @Deprecated
    public static final int MAX_HEADER_SIZE = 1024;

    // ----------------------------------------------------------- Data members

    /**
     * The maximum size permitted for the complete request, as opposed to
     * {@link #fileSizeMax}. A value of -1 indicates no maximum.
     */
    private long sizeMax = -1;

    /**
     * The maximum size permitted for a single uploaded file, as opposed
     * to {@link #sizeMax}. A value of -1 indicates no maximum.
     */
    private long fileSizeMax = -1;

    /**
     * The content encoding to use when reading part headers.
     */
    private String headerEncoding;

    /**
     * The progress listener.
     */
    private ProgressListener listener;

    // ----------------------------------------------------- Property accessors

    /**
     * Returns the factory class used when creating file items.
     *
     * @return The factory class for new file items.
     */
    public abstract FileItemFactory getFileItemFactory();

    /**
     * Sets the factory class to use when creating file items.
     *
     * @param factory The factory class for new file items.
     */
    public abstract void setFileItemFactory(FileItemFactory factory);

    /**
     * Returns the maximum allowed size of a complete request, as opposed
     * to {@link #getFileSizeMax()}.
     *
     * @return The maximum allowed size, in bytes. The default value of
     * -1 indicates, that there is no limit.
     * @see #setSizeMax(long)
     */
    public long getSizeMax() {
        return sizeMax;
    }

    /**
     * Sets the maximum allowed size of a complete request, as opposed
     * to {@link #setFileSizeMax(long)}.
     *
     * @param sizeMax The maximum allowed size, in bytes. The default value of
     *                -1 indicates, that there is no limit.
     * @see #getSizeMax()
     */
    public void setSizeMax(final long sizeMax) {
        this.sizeMax = sizeMax;
    }

    /**
     * Returns the maximum allowed size of a single uploaded file,
     * as opposed to {@link #getSizeMax()}.
     *
     * @return Maximum size of a single uploaded file.
     * @see #setFileSizeMax(long)
     */
    public long getFileSizeMax() {
        return fileSizeMax;
    }

    /**
     * Sets the maximum allowed size of a single uploaded file,
     * as opposed to {@link #getSizeMax()}.
     *
     * @param fileSizeMax Maximum size of a single uploaded file.
     * @see #getFileSizeMax()
     */
    public void setFileSizeMax(final long fileSizeMax) {
        this.fileSizeMax = fileSizeMax;
    }

    /**
     * Retrieves the character encoding used when reading the headers of an
     * individual part. When not specified, or {@code null}, the request
     * encoding is used. If that is also not specified, or {@code null},
     * the platform default encoding is used.
     *
     * @return The encoding used to read part headers.
     */
    public String getHeaderEncoding() {
        return headerEncoding;
    }

    /**
     * Specifies the character encoding to be used when reading the headers of
     * individual part. When not specified, or {@code null}, the request
     * encoding is used. If that is also not specified, or {@code null},
     * the platform default encoding is used.
     *
     * @param encoding The encoding used to read part headers.
     */
    public void setHeaderEncoding(final String encoding) {
        headerEncoding = encoding;
    }

    // --------------------------------------------------------- Public methods

    /**
     * Processes an <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>
     * compliant {@code multipart/form-data} stream.
     *
     * @param ctx The context for the request to be parsed.
     * @return An iterator to instances of {@code FileItemStream}
     * parsed from the request, in the order that they were
     * transmitted.
     * @throws FileUploadException if there are problems reading/parsing
     *                             the request or storing files.
     * @throws IOException         An I/O error occurred. This may be a network
     *                             error while communicating with the client or a problem while
     *                             storing the uploaded content.
     */
    public FileItemIterator getItemIterator(final RequestContext ctx)
            throws FileUploadException, IOException {
        try {
            return new FileItemIteratorImpl(this, ctx);
        } catch (final FileUploadIOException e) {
            // unwrap encapsulated SizeException
            throw (FileUploadException) e.getCause();
        }
    }

    /**
     * Processes an <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>
     * compliant {@code multipart/form-data} stream.
     *
     * @param ctx The context for the request to be parsed.
     * @return A list of {@code FileItem} instances parsed from the
     * request, in the order that they were transmitted.
     * @throws FileUploadException if there are problems reading/parsing
     *                             the request or storing files.
     */
    public List<FileItem> parseRequest(final RequestContext ctx)
            throws FileUploadException {
        final List<FileItem> items = new ArrayList<>();
        boolean successful = false;
        try {
            final FileItemIterator iter = getItemIterator(ctx);
            final FileItemFactory fileItemFactory = Objects.requireNonNull(getFileItemFactory(),
                    "No FileItemFactory has been set.");
            final byte[] buffer = new byte[Streams.DEFAULT_BUFFER_SIZE];
            while (iter.hasNext()) {
                final FileItemStream item = iter.next();
                // Don't use getName() here to prevent an InvalidFileNameException.
                final String fileName = item.getName();
                final FileItem fileItem = fileItemFactory.createItem(item.getFieldName(), item.getContentType(),
                        item.isFormField(), fileName);
                items.add(fileItem);
                try {
                    Streams.copy(item.openStream(), fileItem.getOutputStream(), true, buffer);
                } catch (final FileUploadIOException e) {
                    throw (FileUploadException) e.getCause();
                } catch (final IOException e) {
                    throw new IOFileUploadException(format("Processing of %s request failed. %s",
                            MULTIPART_FORM_DATA, e.getMessage()), e);
                }
                final FileItemHeaders fih = item.getHeaders();
                fileItem.setHeaders(fih);
            }
            successful = true;
            return items;
        } catch (final FileUploadException e) {
            throw e;
        } catch (final IOException e) {
            throw new FileUploadException(e.getMessage(), e);
        } finally {
            if (!successful) {
                for (final FileItem fileItem : items) {
                    try {
                        fileItem.delete();
                    } catch (final Exception ignored) {
                        // ignored TODO perhaps add to tracker delete failure list somehow?
                    }
                }
            }
        }
    }

    /**
     * Processes an <a href="http://www.ietf.org/rfc/rfc1867.txt">RFC 1867</a>
     * compliant {@code multipart/form-data} stream.
     *
     * @param ctx The context for the request to be parsed.
     * @return A map of {@code FileItem} instances parsed from the request.
     * @throws FileUploadException if there are problems reading/parsing
     *                             the request or storing files.
     * @since 1.3
     */
    public Map<String, List<FileItem>> parseParameterMap(final RequestContext ctx)
            throws FileUploadException {
        final List<FileItem> items = parseRequest(ctx);
        final Map<String, List<FileItem>> itemsMap = new HashMap<>(items.size());

        for (final FileItem fileItem : items) {
            final String fieldName = fileItem.getFieldName();
            final List<FileItem> mappedItems = itemsMap.computeIfAbsent(fieldName, k -> new ArrayList<>());

            mappedItems.add(fileItem);
        }

        return itemsMap;
    }

    // ------------------------------------------------------ Protected methods

    /**
     * Retrieves the boundary from the {@code Content-type} header.
     *
     * @param contentType The value of the content type header from which to
     *                    extract the boundary value.
     * @return The boundary, as a byte array.
     */
    public byte[] getBoundary(final String contentType) {
        final ParameterParser parser = new ParameterParser();
        parser.setLowerCaseNames(true);
        // Parameter parser can handle null input
        final Map<String, String> params = parser.parse(contentType, new char[]{';', ','});
        final String boundaryStr = params.get("boundary");

        if (boundaryStr == null) {
            return null;
        }
        final byte[] boundary;
        boundary = boundaryStr.getBytes(StandardCharsets.ISO_8859_1);
        return boundary;
    }

    /**
     * Retrieves the file name from the {@code Content-disposition}
     * header.
     *
     * @param headers A {@code Map} containing the HTTP request headers.
     * @return The file name for the current {@code encapsulation}.
     * @deprecated 1.2.1 Use {@link #getFileName(FileItemHeaders)}.
     */
    @Deprecated
    protected String getFileName(final Map<String, String> headers) {
        return getFileName(getHeader(headers, CONTENT_DISPOSITION));
    }

    /**
     * Retrieves the file name from the {@code Content-disposition}
     * header.
     *
     * @param headers The HTTP headers object.
     * @return The file name for the current {@code encapsulation}.
     */
    public String getFileName(final FileItemHeaders headers) {
        return getFileName(headers.getHeader(CONTENT_DISPOSITION));
    }

    /**
     * Returns the given content-disposition headers file name.
     *
     * @param pContentDisposition The content-disposition headers value.
     * @return The file name
     */
    private String getFileName(final String pContentDisposition) {
        String fileName = null;
        if (pContentDisposition != null) {
            final String cdl = pContentDisposition.toLowerCase(Locale.ENGLISH);
            if (cdl.startsWith(FORM_DATA) || cdl.startsWith(ATTACHMENT)) {
                final ParameterParser parser = new ParameterParser();
                parser.setLowerCaseNames(true);
                // Parameter parser can handle null input
                final Map<String, String> params = parser.parse(pContentDisposition, ';');
                if (params.containsKey("filename")) {
                    fileName = params.get("filename");
                    if (fileName != null) {
                        fileName = fileName.trim();
                    } else {
                        // Even if there is no value, the parameter is present,
                        // so we return an empty file name rather than no file
                        // name.
                        fileName = "";
                    }
                }
            }
        }
        return fileName;
    }

    /**
     * Retrieves the field name from the {@code Content-disposition}
     * header.
     *
     * @param headers A {@code Map} containing the HTTP request headers.
     * @return The field name for the current {@code encapsulation}.
     */
    public String getFieldName(final FileItemHeaders headers) {
        return getFieldName(headers.getHeader(CONTENT_DISPOSITION));
    }

    /**
     * Returns the field name, which is given by the content-disposition
     * header.
     *
     * @param pContentDisposition The content-dispositions header value.
     * @return The field jake
     */
    private String getFieldName(final String pContentDisposition) {
        String fieldName = null;
        if (pContentDisposition != null
                && pContentDisposition.toLowerCase(Locale.ENGLISH).startsWith(FORM_DATA)) {
            final ParameterParser parser = new ParameterParser();
            parser.setLowerCaseNames(true);
            // Parameter parser can handle null input
            final Map<String, String> params = parser.parse(pContentDisposition, ';');
            fieldName = params.get("name");
            if (fieldName != null) {
                fieldName = fieldName.trim();
            }
        }
        return fieldName;
    }

    /**
     * Retrieves the field name from the {@code Content-disposition}
     * header.
     *
     * @param headers A {@code Map} containing the HTTP request headers.
     * @return The field name for the current {@code encapsulation}.
     * @deprecated 1.2.1 Use {@link #getFieldName(FileItemHeaders)}.
     */
    @Deprecated
    protected String getFieldName(final Map<String, String> headers) {
        return getFieldName(getHeader(headers, CONTENT_DISPOSITION));
    }

    /**
     * Creates a new {@link FileItem} instance.
     *
     * @param headers     A {@code Map} containing the HTTP request
     *                    headers.
     * @param isFormField Whether or not this item is a form field, as
     *                    opposed to a file.
     * @return A newly created {@code FileItem} instance.
     * @throws FileUploadException if an error occurs.
     * @deprecated 1.2 This method is no longer used in favour of
     * internally created instances of {@link FileItem}.
     */
    @Deprecated
    protected FileItem createItem(final Map<String, String> headers,
                                  final boolean isFormField)
            throws FileUploadException {
        return getFileItemFactory().createItem(getFieldName(headers),
                getHeader(headers, CONTENT_TYPE),
                isFormField,
                getFileName(headers));
    }

    /**
     * <p> Parses the {@code header-part} and returns as key/value
     * pairs.
     *
     * <p> If there are multiple headers of the same names, the name
     * will map to a comma-separated list containing the values.
     *
     * @param headerPart The {@code header-part} of the current
     *                   {@code encapsulation}.
     * @return A {@code Map} containing the parsed HTTP request headers.
     */
    public FileItemHeaders getParsedHeaders(final String headerPart) {
        final int len = headerPart.length();
        final FileItemHeadersImpl headers = newFileItemHeaders();
        int start = 0;
        for (; ; ) {
            int end = parseEndOfLine(headerPart, start);
            if (start == end) {
                break;
            }
            final StringBuilder header = new StringBuilder(headerPart.substring(start, end));
            start = end + 2;
            while (start < len) {
                int nonWs = start;
                while (nonWs < len) {
                    final char c = headerPart.charAt(nonWs);
                    if (c != ' ' && c != '\t') {
                        break;
                    }
                    ++nonWs;
                }
                if (nonWs == start) {
                    break;
                }
                // Continuation line found
                end = parseEndOfLine(headerPart, nonWs);
                header.append(' ').append(headerPart, nonWs, end);
                start = end + 2;
            }
            parseHeaderLine(headers, header.toString());
        }
        return headers;
    }

    /**
     * Creates a new instance of {@link FileItemHeaders}.
     *
     * @return The new instance.
     */
    protected FileItemHeadersImpl newFileItemHeaders() {
        return new FileItemHeadersImpl();
    }

    /**
     * <p> Parses the {@code header-part} and returns as key/value
     * pairs.
     *
     * <p> If there are multiple headers of the same names, the name
     * will map to a comma-separated list containing the values.
     *
     * @param headerPart The {@code header-part} of the current
     *                   {@code encapsulation}.
     * @return A {@code Map} containing the parsed HTTP request headers.
     * @deprecated 1.2.1 Use {@link #getParsedHeaders(String)}
     */
    @Deprecated
    protected Map<String, String> parseHeaders(final String headerPart) {
        final FileItemHeaders headers = getParsedHeaders(headerPart);
        final Map<String, String> result = new HashMap<>();
        for (final Iterator<String> iter = headers.getHeaderNames(); iter.hasNext(); ) {
            final String headerName = iter.next();
            final Iterator<String> iter2 = headers.getHeaders(headerName);
            final StringBuilder headerValue = new StringBuilder(iter2.next());
            while (iter2.hasNext()) {
                headerValue.append(",").append(iter2.next());
            }
            result.put(headerName, headerValue.toString());
        }
        return result;
    }

    /**
     * Skips bytes until the end of the current line.
     *
     * @param headerPart The headers, which are being parsed.
     * @param end        Index of the last byte, which has yet been
     *                   processed.
     * @return Index of the \r\n sequence, which indicates
     * end of line.
     */
    private int parseEndOfLine(final String headerPart, final int end) {
        int index = end;
        for (; ; ) {
            final int offset = headerPart.indexOf('\r', index);
            if (offset == -1 || offset + 1 >= headerPart.length()) {
                throw new IllegalStateException(
                        "Expected headers to be terminated by an empty line.");
            }
            if (headerPart.charAt(offset + 1) == '\n') {
                return offset;
            }
            index = offset + 1;
        }
    }

    /**
     * Reads the next header line.
     *
     * @param headers String with all headers.
     * @param header  Map where to store the current header.
     */
    private void parseHeaderLine(final FileItemHeadersImpl headers, final String header) {
        final int colonOffset = header.indexOf(':');
        if (colonOffset == -1) {
            // This header line is malformed, skip it.
            return;
        }
        final String headerName = header.substring(0, colonOffset).trim();
        final String headerValue =
                header.substring(colonOffset + 1).trim();
        headers.addHeader(headerName, headerValue);
    }

    /**
     * Returns the header with the specified name from the supplied map. The
     * header lookup is case-insensitive.
     *
     * @param headers A {@code Map} containing the HTTP request headers.
     * @param name    The name of the header to return.
     * @return The value of specified header, or a comma-separated list if
     * there were multiple headers of that name.
     * @deprecated 1.2.1 Use {@link FileItemHeaders#getHeader(String)}.
     */
    @Deprecated
    protected final String getHeader(final Map<String, String> headers,
                                     final String name) {
        return headers.get(name.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Returns the progress listener.
     *
     * @return The progress listener, if any, or null.
     */
    public ProgressListener getProgressListener() {
        return listener;
    }

    /**
     * Sets the progress listener.
     *
     * @param pListener The progress listener, if any. Defaults to null.
     */
    public void setProgressListener(final ProgressListener pListener) {
        listener = pListener;
    }

}
