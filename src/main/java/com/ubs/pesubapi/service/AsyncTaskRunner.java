package com.ubs.pesubapi.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs a task on the bounded {@code extractionExecutor} so the HTTP request thread returns
 * immediately. Lives in its own bean so Spring's {@code @Async} proxy applies — a self-invoked
 * {@code @Async} method on the calling bean would run synchronously.
 */
@Service
public class AsyncTaskRunner {

    @Async("extractionExecutor")
    public void run(Runnable task) {
        task.run();
    }
}
