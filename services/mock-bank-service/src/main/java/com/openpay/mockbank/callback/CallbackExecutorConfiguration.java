package com.openpay.mockbank.callback;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes a full callback queue slow the acquirer down instead of dropping callbacks.
 *
 * <p>The pool itself is sized in {@code application.yml}; this exists only for the one thing that
 * cannot be expressed there. {@code ThreadPoolTaskExecutor} defaults to
 * {@link ThreadPoolExecutor.AbortPolicy}, and Spring Boot exposes no property to change it — so
 * once the bounded queue fills, {@code @Async} submissions are rejected outright.
 *
 * <p>For this component that failure mode is the worst available one. A dropped callback is not a
 * dropped request: the payment has already been dispatched to the acquirer and is sitting in
 * {@code PENDING_PROVIDER} waiting to hear back. Nothing retries it, because from the platform's
 * point of view the acquirer simply never answered. The payment is stuck, and a load test would
 * report it as a perfectly healthy run — every HTTP call returned 201.
 *
 * <p>{@link ThreadPoolExecutor.CallerRunsPolicy} instead: the submitting request thread delivers
 * the callback itself. That applies back-pressure to the acquirer under load, which is exactly what
 * a real one would do, and it means a callback is always eventually sent.
 */
@Configuration
public class CallbackExecutorConfiguration {

    @Bean
    public ThreadPoolTaskExecutorCustomizer callbackExecutorRejectionPolicy() {
        return executor -> executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
