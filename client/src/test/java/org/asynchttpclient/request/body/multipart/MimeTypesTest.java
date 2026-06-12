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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeTypesTest {

    @Test
    void resolvesKnownExtensions() {
        assertEquals("image/png", MimeTypes.getContentType("image.png"));
        assertEquals("text/html", MimeTypes.getContentType("data.html"));
        assertEquals("image/jpeg", MimeTypes.getContentType("photo.jpeg"));
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertEquals("image/png", MimeTypes.getContentType("IMAGE.PNG"));
        assertEquals("text/html", MimeTypes.getContentType("Data.Html"));
    }

    @Test
    void usesExtensionAfterLastDot() {
        assertEquals("image/png", MimeTypes.getContentType("my.archive.png"));
    }

    @Test
    void resolvesModernExtensions() {
        // Added by the upstream Apache httpd mime.types refresh.
        assertEquals("application/wasm", MimeTypes.getContentType("module.wasm"));
        assertEquals("image/avif", MimeTypes.getContentType("picture.avif"));
        assertEquals("image/jxl", MimeTypes.getContentType("picture.jxl"));
        assertEquals("font/woff2", MimeTypes.getContentType("font.woff2"));
    }

    @Test
    void usesModernizedContentTypes() {
        // IANA-preferred types replacing the legacy mappings (RFC 9239, RFC 8081).
        assertEquals("text/javascript", MimeTypes.getContentType("app.js"));
        assertEquals("font/woff", MimeTypes.getContentType("font.woff"));
        assertEquals("font/ttf", MimeTypes.getContentType("font.ttf"));
        assertEquals("font/otf", MimeTypes.getContentType("font.otf"));
    }

    @Test
    void unknownExtensionFallsBackToDefault() {
        assertEquals(MimeTypes.DEFAULT_CONTENT_TYPE, MimeTypes.getContentType("file.zzz"));
    }

    @Test
    void missingExtensionFallsBackToDefault() {
        assertEquals(MimeTypes.DEFAULT_CONTENT_TYPE, MimeTypes.getContentType("noextension"));
        assertEquals(MimeTypes.DEFAULT_CONTENT_TYPE, MimeTypes.getContentType("trailingdot."));
        assertEquals(MimeTypes.DEFAULT_CONTENT_TYPE, MimeTypes.getContentType(""));
    }

    @Test
    void fileLikePartDerivesContentTypeFromFileName() {
        // The wiring formerly provided by jakarta.activation: with no explicit content type a part derives
        // one from its file name, and falls back to the default when the file name carries no usable extension.
        assertEquals("image/png", new ByteArrayPart("p", new byte[0], null, null, "x.png").getContentType());
        assertEquals(MimeTypes.DEFAULT_CONTENT_TYPE, new ByteArrayPart("p", new byte[0], null, null, null).getContentType());
        // An explicit content type is preserved and bypasses detection entirely.
        assertEquals("application/custom", new ByteArrayPart("p", new byte[0], "application/custom", null, "x.png").getContentType());
    }
}
