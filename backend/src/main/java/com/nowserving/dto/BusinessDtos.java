package com.nowserving.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Owner settings payloads. */
public class BusinessDtos {

    /**
     * Rename the restaurant.
     *
     * @NotBlank (not @NotNull) so "   " is rejected before it reaches the
     * service — a whitespace-only name would pass a null check, trim to
     * empty, and leave every public card headed by nothing. Size mirrors the
     * businesses.name column (varchar 255).
     */
    public record UpdateBusinessRequest(@NotBlank @Size(max = 255) String name) {}
}
