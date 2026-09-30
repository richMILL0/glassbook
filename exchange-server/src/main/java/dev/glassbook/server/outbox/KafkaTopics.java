package dev.glassbook.server.outbox;

import dev.glassbook.server.config.GlassbookProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(prefix = "glassbook.kafka", name = "enabled", havingValue = "true")
public class KafkaTopics {

    @Bean
    NewTopic tradesTopic(GlassbookProperties properties) {
        return TopicBuilder.name(properties.kafka().tradesTopic()).partitions(3).replicas(1).build();
    }
}
