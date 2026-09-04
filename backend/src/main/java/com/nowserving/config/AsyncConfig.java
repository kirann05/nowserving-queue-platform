package com.nowserving.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A SEPARATE thread pool for notifications.
 *
 * This is the pattern called a BULKHEAD — named after ship compartments: if
 * one floods, the others keep the vessel afloat.
 *
 * Without it, notification work runs on the same Tomcat threads that serve
 * HTTP requests. A slow push provider would then consume the threads that
 * staff need to tap "Next", and the queue would stop working because a
 * notification was slow. With it, the worst case is that notifications back
 * up in their own small pool while queueing carries on untouched.
 *
 * Bulkhead (isolate) and circuit breaker (fail fast) solve different halves
 * of the same problem, which is why the PRD lists both.
 */
@Configuration
@EnableAsync
// Sprint 5: turns on @Scheduled, which drives the Leave-Now sweeps.
@EnableScheduling
public class AsyncConfig {

    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notify-");
        // If the queue fills, DROP notifications rather than blocking the
        // caller. A missed "you're next" is bad; a frozen app is worse. This
        // is LOAD SHEDDING — choosing what to sacrifice before you're forced to.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
