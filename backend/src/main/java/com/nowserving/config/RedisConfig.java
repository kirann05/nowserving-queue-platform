package com.nowserving.config;

import com.nowserving.realtime.RedisRealtimePublisher;
import com.nowserving.realtime.RedisRealtimeSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to the shared realtime channel.
 *
 * The container owns a background thread that holds a Redis connection open
 * and calls our listener whenever a message arrives. It also reconnects on
 * its own if Redis restarts — which matters, because "the bus blipped and
 * never came back" would silently break live updates while everything else
 * looked healthy.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisRealtimeSubscriber subscriber) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // ChannelTopic = an exact channel name (as opposed to PatternTopic,
        // which would match wildcards like "nowserving:*").
        container.addMessageListener(subscriber, new ChannelTopic(RedisRealtimePublisher.CHANNEL));
        return container;
    }
}
