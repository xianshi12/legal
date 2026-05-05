package com.fatongai.legalassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    @Bean
    public DirectExchange lawSyncExchange(@Value("${app.law.sync.rabbit.exchange:legal.law.sync.exchange}") String name) {
        return new DirectExchange(name, true, false);
    }

    @Bean
    public Queue lawSyncQueue(@Value("${app.law.sync.rabbit.queue:legal.law.sync.queue}") String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    public Binding lawSyncBinding(Queue lawSyncQueue,
                                  DirectExchange lawSyncExchange,
                                  @Value("${app.law.sync.rabbit.routing-key:legal.law.sync}") String routingKey) {
        return BindingBuilder.bind(lawSyncQueue).to(lawSyncExchange).with(routingKey);
    }

    @Bean
    public MessageConverter rabbitJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter rabbitJsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(rabbitJsonMessageConverter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter rabbitJsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(rabbitJsonMessageConverter);
        return factory;
    }
}
