package com.acme;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.amqp.inbound.AmqpInboundChannelAdapter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.function.Function;

@SpringBootApplication
@EnableConfigurationProperties(RabbitSourceProperties.class)
public class RabbitSource {

    private ExecutorSubscribableChannel output = new ExecutorSubscribableChannel();

    public static void main(String[] args) {
        SpringApplication.run(RabbitSource.class, args);
    }

    @Bean
    public Function<Flux<byte[]>, Flux<String>> source() {
        return o -> {
            Flux<String> resultFlux = Flux.create(sink -> {
                MessageHandler handler = message -> sink.next(new String(((byte[]) message.getPayload()), StandardCharsets.UTF_8));
                output.subscribe(handler);
                o.doOnComplete(() -> {
                    sink.complete();
                    output.unsubscribe(handler);
                });
            })/*.doOnNext(System.out::println).cast(String.class).doOnSubscribe(s -> System.out.println("Subscribed via " + s))*/;
            return resultFlux;
        };
    }


    private static final MessagePropertiesConverter inboundMessagePropertiesConverter =
            new DefaultMessagePropertiesConverter() {

                @Override
                public MessageProperties toMessageProperties(AMQP.BasicProperties source, Envelope envelope,
                                                             String charset) {
                    MessageProperties properties = super.toMessageProperties(source, envelope, charset);
                    properties.setDeliveryMode(null);
                    return properties;
                }

            };

    @Autowired
    private RabbitProperties rabbitProperties;

    @Autowired
    private RabbitSourceProperties properties;

    @Autowired
    private ConnectionFactory rabbitConnectionFactory;

    @Autowired
    private ObjectProvider<ConnectionNameStrategy> connectionNameStrategy;

    private CachingConnectionFactory ownConnectionFactory;


    @Bean
    public AmqpInboundChannelAdapter adapter() throws Exception {
        return Amqp.inboundAdapter(container()).outputChannel(output)
                .mappedRequestHeaders(properties.getMappedRequestHeaders())
                .get();
    }

    private ConnectionFactory buildLocalConnectionFactory() throws Exception {
        this.ownConnectionFactory = new AutoConfig.Creator().rabbitConnectionFactory(this.rabbitProperties,
                this.connectionNameStrategy);
        return this.ownConnectionFactory;
    }

    @Bean
    public SimpleMessageListenerContainer container() throws Exception {
        ConnectionFactory connectionFactory = this.properties.isOwnConnection()
                ? buildLocalConnectionFactory()
                : this.rabbitConnectionFactory;
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        RabbitProperties.SimpleContainer simpleContainer = this.rabbitProperties.getListener().getSimple();

        AcknowledgeMode acknowledgeMode = simpleContainer.getAcknowledgeMode();
        if (acknowledgeMode != null) {
            container.setAcknowledgeMode(acknowledgeMode);
        }
        Integer concurrency = simpleContainer.getConcurrency();
        if (concurrency != null) {
            container.setConcurrentConsumers(concurrency);
        }
        Integer maxConcurrency = simpleContainer.getMaxConcurrency();
        if (maxConcurrency != null) {
            container.setMaxConcurrentConsumers(maxConcurrency);
        }
        Integer prefetch = simpleContainer.getPrefetch();
        if (prefetch != null) {
            container.setPrefetchCount(prefetch);
        }
        Integer transactionSize = simpleContainer.getTransactionSize();
        if (transactionSize != null) {
            container.setTxSize(transactionSize);
        }
        container.setDefaultRequeueRejected(this.properties.getRequeue());
        container.setChannelTransacted(this.properties.getTransacted());
        String[] queues = this.properties.getQueues();
        Assert.state(queues.length > 0, "At least one queue is required");
        Assert.noNullElements(queues, "queues cannot have null elements");
        container.setQueueNames(queues);
        if (this.properties.isEnableRetry()) {
            container.setAdviceChain(rabbitSourceRetryInterceptor());
        }
        container.setMessagePropertiesConverter(inboundMessagePropertiesConverter);
        return container;
    }

    @Bean
    public RetryOperationsInterceptor rabbitSourceRetryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(this.properties.getMaxAttempts())
                .backOffOptions(this.properties.getInitialRetryInterval(), this.properties.getRetryMultiplier(),
                        this.properties.getMaxRetryInterval())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}

class AutoConfig extends RabbitAutoConfiguration {

    static class Creator extends RabbitConnectionFactoryCreator {

        @Override
        public CachingConnectionFactory rabbitConnectionFactory(RabbitProperties config,
                                                                ObjectProvider<ConnectionNameStrategy> connectionNameStrategy) throws Exception {
            CachingConnectionFactory cf = super.rabbitConnectionFactory(config, connectionNameStrategy);
            cf.setConnectionNameStrategy(new ConnectionNameStrategy() {

                @Override
                public String obtainNewConnectionName(ConnectionFactory connectionFactory) {
                    return "rabbit.source.own.connection";
                }

            });
            cf.afterPropertiesSet();
            return cf;
        }

    }

}
