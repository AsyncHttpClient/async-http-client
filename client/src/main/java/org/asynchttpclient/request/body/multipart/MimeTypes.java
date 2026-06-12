/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps file extensions to content types using the bundled {@code ahc-mime.types} resource.
 *
 * <p>This is a self-contained replacement for {@code jakarta.activation.MimetypesFileTypeMap}, which was
 * previously used solely to resolve the content type of {@link FileLikePart}s. The lookup mirrors the
 * {@code MimetypesFileTypeMap} contract: the extension is the substring after the last {@code '.'}, and an
 * unknown or missing extension resolves to {@value #DEFAULT_CONTENT_TYPE}. Unlike the original, the lookup is
 * case-insensitive and relies exclusively on the bundled resource, so detection is deterministic across
 * machines and classpaths.
 */
final class MimeTypes {

    static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Lower-cased file extension to content type. Built once at class load from {@code ahc-mime.types}.
     */
    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE;

    static {
        Map<String, String> map = new HashMap<>();
        // The MimetypesFileTypeMap format: '#' comments, blank lines, and whitespace-delimited
        // "type ext1 ext2 ..." entries. A later mapping for the same extension wins, matching the
        // original Hashtable-based implementation.
        try (InputStream is = MimeTypes.class.getResourceAsStream("ahc-mime.types");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] tokens = line.split("\\s+");
                for (int i = 1; i < tokens.length; i++) {
                    map.put(tokens[i].toLowerCase(Locale.ROOT), tokens[0]);
                }
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        EXTENSION_TO_CONTENT_TYPE = Collections.unmodifiableMap(map);
    }

    private MimeTypes() {
        // Prevent outside initialization
    }

    /**
     * Resolves the content type for the given file name based on its extension.
     *
     * @param fileName the file name (may include a path; only the part after the last {@code '.'} is used)
     * @return the mapped content type, or {@value #DEFAULT_CONTENT_TYPE} if the extension is absent or unknown
     */
    static String getContentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return DEFAULT_CONTENT_TYPE;
        }
        String extension = fileName.substring(dot + 1);
        if (extension.isEmpty()) {
            return DEFAULT_CONTENT_TYPE;
        }
        return EXTENSION_TO_CONTENT_TYPE.getOrDefault(extension.toLowerCase(Locale.ROOT), DEFAULT_CONTENT_TYPE);
    }
}
