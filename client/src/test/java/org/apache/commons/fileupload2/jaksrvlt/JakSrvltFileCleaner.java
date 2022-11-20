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
package org.apache.commons.fileupload2.jaksrvlt;


import org.apache.commons.io.FileCleaningTracker;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * A servlet context listener, which ensures that the
 * {@link FileCleaningTracker}'s reaper thread is terminated,
 * when the web application is destroyed.
 */
public class JakSrvltFileCleaner implements ServletContextListener {

    /**
     * Attribute name, which is used for storing an instance of
     * {@link FileCleaningTracker} in the web application.
     */
    public static final String FILE_CLEANING_TRACKER_ATTRIBUTE
        = JakSrvltFileCleaner.class.getName() + ".FileCleaningTracker";

    /**
     * Returns the instance of {@link FileCleaningTracker}, which is
     * associated with the given {@link ServletContext}.
     *
     * @param pServletContext The servlet context to query
     * @return The contexts tracker
     */
    public static FileCleaningTracker
            getFileCleaningTracker(final ServletContext pServletContext) {
        return (FileCleaningTracker)
            pServletContext.getAttribute(FILE_CLEANING_TRACKER_ATTRIBUTE);
    }

    /**
     * Sets the instance of {@link FileCleaningTracker}, which is
     * associated with the given {@link ServletContext}.
     *
     * @param pServletContext The servlet context to modify
     * @param pTracker The tracker to set
     */
    public static void setFileCleaningTracker(final ServletContext pServletContext,
            final FileCleaningTracker pTracker) {
        pServletContext.setAttribute(FILE_CLEANING_TRACKER_ATTRIBUTE, pTracker);
    }

    /**
     * Called when the web application is initialized. Does
     * nothing.
     *
     * @param sce The servlet context, used for calling
     *   {@link #setFileCleaningTracker(ServletContext, FileCleaningTracker)}.
     */
    @Override
    public void contextInitialized(final ServletContextEvent sce) {
        setFileCleaningTracker(sce.getServletContext(),
                new FileCleaningTracker());
    }

    /**
     * Called when the web application is being destroyed.
     * Calls {@link FileCleaningTracker#exitWhenFinished()}.
     *
     * @param sce The servlet context, used for calling
     *     {@link #getFileCleaningTracker(ServletContext)}.
     */
    @Override
    public void contextDestroyed(final ServletContextEvent sce) {
        getFileCleaningTracker(sce.getServletContext()).exitWhenFinished();
    }
}
