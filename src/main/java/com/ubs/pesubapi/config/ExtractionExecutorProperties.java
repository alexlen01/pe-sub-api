package com.ubs.pesubapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sizing for the {@code extractionExecutor} pool that runs the upload → parse → ingest pipeline.
 *
 * <p>Concurrency here is a heap-pressure trade-off: each in-flight task holds a fully parsed POI
 * workbook, so the right pool size depends on the container's memory and the size of the Agent BB
 * files an environment actually receives. Externalised so it can be retuned per environment
 * without a rebuild — defaults live in {@code application.yml}, never in the annotations or the
 * configuration class.
 */
@ConfigurationProperties(prefix = "app.extraction-executor")
public class ExtractionExecutorProperties {

    /** Threads kept alive; the number of workbooks parsed concurrently under normal load. */
    private int corePoolSize;

    /** Upper bound on threads once the queue is full. */
    private int maxPoolSize;

    /** Uploads that wait for a thread before backpressure (CallerRuns) kicks in. */
    private int queueCapacity;

    /** How long shutdown waits for in-flight parses to finish before interrupting them. */
    private int awaitTerminationSeconds;

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int v) { this.corePoolSize = v; }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int v) { this.maxPoolSize = v; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { this.queueCapacity = v; }

    public int getAwaitTerminationSeconds() { return awaitTerminationSeconds; }
    public void setAwaitTerminationSeconds(int v) { this.awaitTerminationSeconds = v; }
}
