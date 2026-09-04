package com.nowserving.support;

import com.nowserving.travel.TravelTimeProvider;

import java.util.Optional;

/**
 * A routing provider that costs nothing and always says what the test needs.
 *
 * The PRD is blunt about why this exists (§4.2): *"lets you inject a fake
 * provider in tests so your test suite never makes a paid API call. That last
 * point is not optional."* A CI pipeline that hits a metered routing API bills
 * you for every push and breaks whenever the vendor has a bad day.
 */
public class FakeTravelTimeProvider implements TravelTimeProvider {

    private volatile int minutes = 20;
    private volatile boolean available = true;

    public void willReturnMinutes(int minutes) {
        this.minutes = minutes;
    }

    /** Simulate the provider being down / out of quota. */
    public void willBeUnavailable() {
        this.available = false;
    }

    @Override
    public Optional<TravelEstimate> travelMinutes(double a, double b, double c, double d) {
        return available ? Optional.of(new TravelEstimate(minutes, true, 0)) : Optional.empty();
    }

    @Override
    public String name() {
        return "fake";
    }
}
