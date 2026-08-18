package com.ubs.pesubapi.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async infrastructure for the extraction handoff.
 *
 * <p>Parsing an Agent BB workbook (POI + recognition + ingest) is CPU- and memory-heavy and must
 * not run on the HTTP request thread — otherwise the upload call blocks and the user's UI hangs.
 * The upload endpoint persists the submission as {@code Processing}, hands the pipeline to this
 * bounded executor, and returns immediately; the UI polls submission status until it flips to
 * {@code Review} (or {@code Error}).
 *
 * <p>Concurrency is capped to protect the heap from many simultaneous large-workbook parses;
 * excess work queues, and only under sustained overload does the submitting thread run the task
 * (CallerRuns backpressure) rather than dropping it. The sizing is environment-dependent — it
 * trades throughput against heap — so it comes from {@link ExtractionExecutorProperties}
 * ({@code app.extraction-executor.*}) rather than being fixed here.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("extractionExecutor")
    public ThreadPoolTaskExecutor extractionExecutor(ExtractionExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix("extraction-");
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(props.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    private TaskDecorator mdcTaskDecorator() {
        return task -> () -> {
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (callerContext != null) MDC.setContextMap(callerContext);
                else MDC.clear();
                task.run();
            } finally {
                if (previous != null) MDC.setContextMap(previous);
                else MDC.clear();
            }
        };
    }
}
