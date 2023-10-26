package com.bosch.pipeliner

/**
 * Provides Logging functionality
 * In addition to the standard streams, the logs can be published to Jenkins console.
 * This class supports resuming after Jenkins restart.
 */

public class LoggerDynamic implements Serializable {
    // Jenkins scripted environment reference
    // The actual class here is org.jenkinsci.plugins.workflow.cps.CpsScript, but it's hard to mock it
    private script

    /**
     * Initializes logger
     *
     * @param handler either Jenkins scripted environment reference or
     * Output Stream
     */
    LoggerDynamic(script) {
        this.script = script
    }

    /**
     * Provides reference to Jenkins steps or stream as per initialization
     * Throws NullPointerException if the setup is not done
     *
     * @return script reference or output stream
     */
    private getStream() {
        if (script != null) {
            return script
        }

        throw new NullPointerException("LoggerDynamic should be initialized prior to usage")
    }

    /**
     * Log messages with info label
     *
     * @param string message
     */
    public void info(String message) {
        getStream().echo("INFO: " + message)
    }

    /**
     * Log messages with warn label
     *
     * @param string message
     */
    public void warn(String message) {
        getStream().echo("WARN: " + message)
    }

    /**
     * Log messages with error label
     *
     * @param string message
     */
    public void error(String message) {
       getStream().echo("ERROR: " + message)
    }
}
