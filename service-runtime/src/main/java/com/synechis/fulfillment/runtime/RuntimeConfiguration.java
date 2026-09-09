package com.synechis.fulfillment.runtime;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
@Configuration @EnableScheduling
public class RuntimeConfiguration {
 @Bean org.springframework.kafka.support.ProducerListener<Object,Object> safeProducerLogging(){var listener=new org.springframework.kafka.support.LoggingProducerListener<Object,Object>();listener.setIncludeContents(false);return listener;}
 @Bean NewTopic eventsTopic(){return TopicBuilder.name("fulfillment.v1").partitions(6).replicas(1).config("retention.ms","604800000").build();}
 @Bean NewTopic deadLetters(){return TopicBuilder.name("fulfillment.dlt.v1").partitions(6).replicas(1).config("retention.ms","2592000000").build();}
 @Bean DefaultErrorHandler kafkaErrorHandler(){return new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));}
}
