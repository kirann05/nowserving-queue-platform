package com.nowserving.notification;

/**
 * The SMS half of the PRD's notification story — a SECOND port rather than a
 * second implementation of {@link NotificationChannel}.
 *
 * WHY NOT REUSE NotificationChannel: its send() takes a PushSubscription — a
 * browser endpoint plus two encryption keys. SMS needs a phone number and
 * nothing else. Forcing one interface to serve both would mean passing a fake
 * subscription around, or widening the signature until neither implementation
 * uses half its arguments. Two small honest ports beat one dishonest one.
 *
 * What they share is {@link PushMessage} — the channel-agnostic "what to
 * say" — which is the part that genuinely IS the same.
 */
public interface SmsSender {

    /**
     * Same contract as NotificationChannel: never throw for ordinary
     * failures. An unreachable phone is a normal Tuesday.
     */
    Result send(String phoneNumber, PushMessage message);

    /**
     * Whether this sender could actually deliver right now.
     *
     * Exists so the UI can stop offering "Text me instead" on a deployment
     * with no SMS credentials — a button whose only possible outcome is
     * NOT_CONFIGURED is a promise the product cannot keep.
     */
    boolean isAvailable();

    enum Result {
        SENT,
        /** Provider rejected the number — it's not worth retrying. */
        INVALID_NUMBER,
        /** Transient provider failure. */
        FAILED,
        /** No SMS provider configured on this server — the default state. */
        NOT_CONFIGURED
    }
}
