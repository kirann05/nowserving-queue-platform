package com.nowserving.notification;

/**
 * What the customer's phone will show. Channel-agnostic on purpose: the same
 * message must make sense as a browser notification today and as an SMS when
 * a Twilio adapter arrives.
 *
 * @param title short — phones truncate hard
 * @param body  one sentence
 * @param url   where tapping the notification takes them (their ticket page)
 */
public record PushMessage(String title, String body, String url) {
}
