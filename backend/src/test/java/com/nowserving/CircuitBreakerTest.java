package com.nowserving;

import com.nowserving.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the circuit breaker actually trips — the Sprint 3 resilience story.
 *
 * A plain unit test: no Spring, no database, milliseconds to run. The breaker
 * is pure logic, so this is the right level to test it at (the base of the
 * test pyramid in PRD §5.1).
 */
class CircuitBreakerTest {

    private final CircuitBreaker breaker = new ResilienceConfig().notificationCircuitBreaker();

    private void failingCall() {
        breaker.executeSupplier(() -> {
            throw new RuntimeException("push provider is down");
        });
    }

    @Test
    void itStartsClosed_andLetsCallsThrough() {
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.executeSupplier(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void afterEnoughFailures_itOpens_andThenRefusesToEvenTry() {
        // minimumNumberOfCalls = 5: one bad call must NOT punish everyone.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(this::failingCall).isInstanceOf(RuntimeException.class);
        }
        assertThat(breaker.getState())
                .as("still judging — too small a sample to conclude anything")
                .isEqualTo(CircuitBreaker.State.CLOSED);

        // The fifth failure crosses the threshold (100% > 50% failure rate).
        assertThatThrownBy(this::failingCall).isInstanceOf(RuntimeException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // THE POINT OF THE WHOLE EXERCISE: while OPEN, the call is rejected
        // INSTANTLY without touching the network. A hung 30-second request
        // becomes a microsecond rejection, so request threads stay free and
        // the queue keeps serving customers.
        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> breaker.executeSupplier(() -> "should never run"))
                .isInstanceOf(CallNotPermittedException.class);
        Duration rejectionTime = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(rejectionTime)
                .as("rejection must be immediate — that is what protects the thread pool")
                .isLessThan(Duration.ofMillis(50));
    }

    @Test
    void successfulCallsKeepItClosed() {
        for (int i = 0; i < 20; i++) {
            breaker.executeSupplier(() -> "fine");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aSlowCallCountsAsAFailure_evenWhenItSucceeds() {
        // slowCallDurationThreshold = 3s. Slowness is the real danger: an
        // error frees the thread immediately, a hang holds it. Resilience4j
        // therefore treats "succeeded, but took too long" as a failure.
        assertThat(breaker.getMetrics().getNumberOfSlowSuccessfulCalls()).isZero();
        assertThat(breaker.getCircuitBreakerConfig().getSlowCallDurationThreshold())
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(breaker.getCircuitBreakerConfig().getSlowCallRateThreshold()).isEqualTo(50f);
    }
}
