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
package org.asynchttpclient.handler.resumable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.util.MiscUtils.closeSilently;

/**
 * A {@link ResumableAsyncHandler.ResumableProcessor} which use a properties file
 * to store the download index information.
 */
public class PropertiesBasedResumableProcessor implements ResumableAsyncHandler.ResumableProcessor {
    private static final Logger log = LoggerFactory.getLogger(PropertiesBasedResumableProcessor.class);
    private static final File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc");
    private static final String storeName = "ResumableAsyncHandler.properties";

    private final ConcurrentHashMap<String, Long> properties = new ConcurrentHashMap<>();

    private static String append(Map.Entry<String, Long> e) {
        return e.getKey() + '=' + e.getValue() + '\n';
    }

    @Override
    public void put(String url, long transferredBytes) {
        properties.put(url, transferredBytes);
    }

    @Override
    public void remove(String uri) {
        if (uri != null) {
            properties.remove(uri);
        }
    }

    @Override
    public void save(Map<String, Long> map) {
        log.debug("Saving current download state {}", properties);
        OutputStream os = null;
        try {

            if (!TMP.exists() && !TMP.mkdirs()) {
                throw new IllegalStateException("Unable to create directory: " + TMP.getAbsolutePath());
            }
            File f = new File(TMP, storeName);
            if (!f.exists() && !f.createNewFile()) {
                throw new IllegalStateException("Unable to create temp file: " + f.getAbsolutePath());
            }
            if (!f.canWrite()) {
                throw new IllegalStateException();
            }

            os = Files.newOutputStream(f.toPath());
            for (Map.Entry<String, Long> e : properties.entrySet()) {
                os.write(append(e).getBytes(UTF_8));
            }
            os.flush();
        } catch (Throwable e) {
            log.warn(e.getMessage(), e);
        } finally {
            closeSilently(os);
        }
    }

    @Override
    public Map<String, Long> load() {
        Scanner scan = null;
        try {
            scan = new Scanner(new File(TMP, storeName), UTF_8);
            scan.useDelimiter("[=\n]");

            String key;
            String value;
            while (scan.hasNext()) {
                key = scan.next().trim();
                value = scan.next().trim();
                properties.put(key, Long.valueOf(value));
            }
            log.debug("Loading previous download state {}", properties);
        } catch (FileNotFoundException ex) {
            log.debug("Missing {}", storeName);
        } catch (Throwable ex) {
            // Survive any exceptions
            log.warn(ex.getMessage(), ex);
        } finally {
            if (scan != null) {
                scan.close();
            }
        }
        return Collections.unmodifiableMap(properties);
    }
}
