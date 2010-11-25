/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package com.ning.http.client.resumable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * A {@link com.ning.http.client.resumable.ResumableAsyncHandler.ResumableProcessor} which use a {@link Properties}
 * to store the download index information.
 */
public class PropertiesBasedResumableProcessor implements ResumableAsyncHandler.ResumableProcessor {
    private final static Logger log = LoggerFactory.getLogger(PropertiesBasedResumableProcessor.class);
    private final static File TMP = new File(System.getProperty("java.io.tmpdir"), "ahc");
    private final static String storeName = "ResumableAsyncHandler.properties";

    private final Properties properties = new Properties();

    public void put(String url, String transferredBytes) {
        properties.put(url, transferredBytes);
    }

    public void remove(String uri) {
        properties.remove(uri);
    }

    public void save(Properties properties) {
        log.debug("Saving current download state {}", properties);
        FileOutputStream os = null;
        try {

            TMP.mkdirs();
            File f = new File(TMP, storeName);
            f.createNewFile();
            if (!f.canWrite()) {
                throw new IllegalStateException();
            }

            os = new FileOutputStream(f);
            properties.store(os, "Resumable Index");
        } catch (Throwable e) {
            log.warn(e.getMessage(), e);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                }
            }
        }
    }

    public Properties load() {
        File f = new File(TMP, storeName);
        if (f.exists()) {
            FileInputStream is = null;
            try {
                is = new FileInputStream(f);
                properties.load(new FileInputStream(f));
                log.debug("Loading previous download state {}", properties);
            } catch (IOException e) {
                log.error(e.getMessage(),e);
            } finally {
                try {
                    if (is != null) is.close();
                } catch (IOException e) {
                }
            }
        }
        return properties;
    }
}
