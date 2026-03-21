package com.notification.config;

import com.notification.event.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${notification.kafka.topic.send:notification-send}")
    private String sendTopic;

    @Value("${notification.kafka.topic.retry:notification-retry}")
    private String retryTopic;

    @Value("${notification.kafka.consumer.group-id:notification-group}")
    private String groupId;

    @Value("${notification.kafka.consumer.concurrency:3}")
    private int consumerConcurrency;

    // ==================== TOPICS ====================

    @Bean
    public NewTopic notificationSendTopic() {
        return TopicBuilder.name(sendTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationRetryTopic() {
        return TopicBuilder.name(retryTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationSendDltTopic() {
        return TopicBuilder.name(sendTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationRetryDltTopic() {
        return TopicBuilder.name(retryTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    // ==================== PRODUCER ====================

    @Bean
    public ProducerFactory<String, NotificationEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, NotificationEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ==================== CONSUMER ====================

    @Bean
    public ConsumerFactory<String, NotificationEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.notification.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, NotificationEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(consumerConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Dead Letter Topic: after 3 failed attempts, send to .DLT topic
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(
                        new DeadLetterPublishingRecoverer(kafkaTemplate()),
                        new FixedBackOff(1000L, 2)  // 2 retries with 1s interval → 3 total attempts
                )
        );

        log.info("Kafka consumer factory initialized: concurrency={}, group={}", consumerConcurrency, groupId);
        return factory;
    }
}
