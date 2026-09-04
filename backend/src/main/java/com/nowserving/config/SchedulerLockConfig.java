package com.nowserving.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Makes scheduled jobs safe to run on more than one instance.
 *
 * WITHOUT THIS: @Scheduled fires on every replica. Two pods means the
 * Leave-Now sweep runs twice a minute — doubling the paid routing calls and
 * doubling the work, for nothing.
 *
 * WITH THIS: an instance may only run a job if it can claim that job's row in
 * the `shedlock` table. Exactly one wins; the others skip and try next tick.
 *
 * The two duration settings are the part people get wrong:
 *  - lockAtMostFor is a DEADLOCK ESCAPE. If the instance holding the lock is
 *    killed mid-job it can never release it, so the lock must expire by
 *    itself. Set it comfortably longer than the job's worst case, or a slow
 *    run gets a second instance racing it.
 *  - lockAtLeastFor stops a very fast job from running repeatedly when
 *    several instances have slightly different clocks.
 */
@Configuration
@EnableSchedulerLock(
        defaultLockAtMostFor = "PT5M",   // no job here should ever take 5 minutes
        defaultLockAtLeastFor = "PT10S")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // Store times in UTC rather than the DB server's local
                        // zone: instances in different regions must agree on
                        // when a lock expires.
                        .usingDbTime()
                        .build());
    }
}
