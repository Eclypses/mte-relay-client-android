// The MIT License (MIT)
//
// Copyright (c) Eclypses, Inc.
//
// All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.mte.relay;


import android.util.Log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.function.Supplier;

public class LogHelper {

    private static boolean isEnabled = true;
    private static boolean isFileLoggingEnabled = false;

    private static Logger getLogger(String tag) {
        return LoggerFactory.getLogger(tag);
    }

    public static void trace(String tag, String message) {
        if (isEnabled) {
            getLogger(tag).trace(message);
        }
    }

    public static void debug(String tag, String message) {
        if (isEnabled) {
            getLogger(tag).debug(message);
        }
    }

    public static void info(String tag, String message) {
        if (isEnabled) {
            getLogger(tag).info(message);
        }
    }

    public static void warn(String tag, String message) {
        if (isEnabled) {
            getLogger(tag).warn(message);
        }
    }

    public static void error(String tag, String message) {
        if (isEnabled) {
            getLogger(tag).error(message);
        }
    }

    public static void error(String tag, String message, Throwable t) {
        if (isEnabled) {
            getLogger(tag).error(message, t);
        }
    }

    // ---- Lazy overloads -----------------------------------------------------
    // The message is only built when the level is actually enabled, so calls on
    // a disabled level cost nothing beyond the level check. Prefer these for
    // debug/trace and for anything whose message is expensive to assemble.
    // Reminder: NEVER log secrets — see dev_docs/LOGGING_CONVENTION.md §0.

    public static void trace(String tag, Supplier<String> message) {
        Logger logger = getLogger(tag);
        if (isEnabled && logger.isTraceEnabled()) {
            logger.trace(message.get());
        }
    }

    public static void debug(String tag, Supplier<String> message) {
        Logger logger = getLogger(tag);
        if (isEnabled && logger.isDebugEnabled()) {
            logger.debug(message.get());
        }
    }

    public static void info(String tag, Supplier<String> message) {
        Logger logger = getLogger(tag);
        if (isEnabled && logger.isInfoEnabled()) {
            logger.info(message.get());
        }
    }

    public static void warn(String tag, Supplier<String> message) {
        Logger logger = getLogger(tag);
        if (isEnabled && logger.isWarnEnabled()) {
            logger.warn(message.get());
        }
    }

    public static void error(String tag, Supplier<String> message) {
        Logger logger = getLogger(tag);
        if (isEnabled && logger.isErrorEnabled()) {
            logger.error(message.get());
        }
    }

    // ---- Level gates --------------------------------------------------------
    // Use when a caller needs to skip work (not just string building) for a
    // disabled level, e.g. computing diagnostics.

    public static boolean isTraceEnabled(String tag) {
        return isEnabled && getLogger(tag).isTraceEnabled();
    }

    public static boolean isDebugEnabled(String tag) {
        return isEnabled && getLogger(tag).isDebugEnabled();
    }

    /**
     * Raises or lowers the minimum level emitted at runtime (field diagnostics).
     * Default is INFO (see logback.xml); call with "DEBUG" or "TRACE" to get
     * verbose output while reproducing an issue, then set it back.
     */
    public static void setMinimumLevel(String level) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.toLevel(level, Level.INFO));
    }

    public static void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public static boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Enables or disables file logging.
     * <p>
     * Note: Log file rotation and maximum file size (e.g., 1MB) should be configured
     * in logback.xml using a RollingFileAppender and appropriate policies.
     * This method only starts or stops the file appender named "FILE".
     * Do not manually implement file rotation or max file size here.
     * See logback.xml for log rotation configuration.
     */
    public static void enableFileLogging(boolean enabled) {
        isFileLoggingEnabled = enabled;

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> fileAppender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE");

        if (fileAppender != null) {
            if (enabled) {
                fileAppender.start();
            } else {
                fileAppender.stop();
            }
        }
    }

    public static boolean isFileLoggingEnabled() {
        return isFileLoggingEnabled;
    }

    public static String readLogFileContents() {
        Log.d("MTE", "Reading log file contents");
        StringBuilder sb = new StringBuilder();
        try {
            File logFile = new File(System.getProperty("LOG_DIR") + "/logs/relay.log");
            if (logFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(logFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
            }
        } catch (IOException e) {
            return "Error reading log file: " + e.getMessage();
        }
        return sb.toString();
    }

    public static void clearLogFileContents() {
        Log.d("MTE", "Clearing log file");
        try {
            File logFile = new File(System.getProperty("LOG_DIR") + "/logs/relay.log");
            if (logFile.exists()) {
                FileWriter writer = new FileWriter(logFile, false);
                writer.write("");
                writer.close();
                Log.d("MTE", "Log file cleared of previous log entries");
            }
        } catch (IOException e) {
            // LogHelper not usable here due to circular dependency risk
            Log.d("MTE", "Error clearing log file: " + e.getMessage());
        }
    }
}
