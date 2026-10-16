package cl.barriodigital.rabbitmqadmin.config;

import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitConfig {
    @Bean
    RabbitAdmin rabbitAdmin(RabbitTemplate template,
            @Value("${spring.rabbitmq.username}") String username,
            @Value("${spring.rabbitmq.password}") String password) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Define RABBITMQ_USERNAME y RABBITMQ_PASSWORD.");
        }
        var admin = new RabbitAdmin(template);
        admin.setAutoStartup(false);
        admin.setRedeclareManualDeclarations(false);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }
}
