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
package org.asynchttpclient.handler.resumable;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers how the resumable index store is created in the shared temp directory.
 */
public class PropertiesBasedResumableProcessorStoreTest {

    private static final Path STORE = Paths.get(System.getProperty("java.io.tmpdir"), "ahc", "ResumableAsyncHandler.properties");

    private static void deleteStore() throws IOException {
        Files.deleteIfExists(STORE);
    }

    @Test
    public void storeIsCreatedOwnerOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        deleteStore();

        PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
        processor.put("http://localhost/owner-only.url", 15L);
        processor.save(null);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(STORE)));
    }

    @Test
    public void saveDoesNotWriteThroughASymlink() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        deleteStore();
        Files.createDirectories(STORE.getParent());

        Path target = Files.createTempFile("ahc-symlink-target", ".txt");
        try {
            Files.write(target, "untouched".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(STORE, target);

            PropertiesBasedResumableProcessor processor = new PropertiesBasedResumableProcessor();
            processor.put("http://localhost/symlink.url", 15L);
            processor.save(null);

            assertEquals("untouched", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(target);
            deleteStore();
        }
    }
}
