package dev.finpay.platform;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfiguration {
    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(PaymentEvent.COMMANDS).partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.RESULTS).partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.JOURNAL).partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.COMMANDS + ".DLT").partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.RESULTS + ".DLT").partitions(3).replicas(1).build(),
                TopicBuilder.name(PaymentEvent.JOURNAL + ".DLT").partitions(3).replicas(1).build());
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        // Retry transient failures for at most 30 seconds before durable quarantine.
        var backOff = new ExponentialBackOff(1000, 2);
        backOff.setMaxInterval(8000);
        backOff.setMaxElapsedTime(30000);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
