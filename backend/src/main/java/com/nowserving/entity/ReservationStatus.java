package com.nowserving.entity;

public enum ReservationStatus {
    /** Booked and waiting for the customer to arrive. */
    BOOKED,
    /** The customer showed up and was placed into the live queue. */
    REDEEMED,
    /** Cancelled by the customer or the business — frees the seat. */
    CANCELLED
}
