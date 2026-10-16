package cl.barriodigital.rabbitmqadmin.service;

import java.util.Map;
import java.util.function.Supplier;
import cl.barriodigital.rabbitmqadmin.dto.BindingRequest;
import cl.barriodigital.rabbitmqadmin.dto.CreateExchangeRequest;
import cl.barriodigital.rabbitmqadmin.dto.CreateQueueRequest;
import cl.barriodigital.rabbitmqadmin.dto.QueueInfoResponse;
import cl.barriodigital.rabbitmqadmin.exception.AdminOperationException;
import cl.barriodigital.rabbitmqadmin.validation.ResourcePolicy;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RabbitAdminService {
    public record Declaration<T>(boolean created, T resource) { }
    private static final Logger LOG = LoggerFactory.getLogger(RabbitAdminService.class);
    private final RabbitAdmin admin;
    private final RabbitTemplate template;
    private final ResourcePolicy policy;

    public RabbitAdminService(RabbitAdmin admin, RabbitTemplate template, ResourcePolicy policy) {
        this.admin = admin;
        this.template = template;
        this.policy = policy;
    }

    public Declaration<CreateQueueRequest> createQueue(CreateQueueRequest request) {
        policy.requireMutable(request.name());
        boolean existed = exists(() -> queueInfo(request.name()));
        return broker("queue.declare", () -> {
            // No user-supplied arguments: DLX policies and queue types remain centrally managed.
            String declared = admin.declareQueue(new Queue(request.name(), request.durable(), request.exclusive(), request.autoDelete()));
            if (declared == null) {
                throw new AdminOperationException(HttpStatus.SERVICE_UNAVAILABLE, "El broker no confirmo la declaracion.");
            }
            return new Declaration<>(!existed, request);
        });
    }

    public void deleteQueue(String name) {
        policy.requireMutable(name);
        broker("queue.delete", () -> template.execute(channel -> {
            channel.queueDeclarePassive(name);
            channel.queueDelete(name, true, true);
            return null;
        }));
    }

    public QueueInfoResponse queueInfo(String name) {
        policy.validateName(name);
        return broker("queue.inspect", () -> template.execute(channel -> {
            var result = channel.queueDeclarePassive(name);
            return new QueueInfoResponse(name, result.getMessageCount(), result.getConsumerCount());
        }));
    }

    public Declaration<CreateExchangeRequest> createExchange(CreateExchangeRequest request) {
        policy.requireMutable(request.name());
        boolean existed = exists(() -> broker("exchange.inspect", () -> template.execute(channel -> {
            channel.exchangeDeclarePassive(request.name());
            return true;
        })));
        return broker("exchange.declare", () -> {
            admin.declareExchange(new CustomExchange(request.name(), request.type(), request.durable(), request.autoDelete()));
            return new Declaration<>(!existed, request);
        });
    }

    public void deleteExchange(String name) {
        policy.requireMutable(name);
        broker("exchange.delete", () -> template.execute(channel -> {
            channel.exchangeDeclarePassive(name);
            channel.exchangeDelete(name, true);
            return null;
        }));
    }

    public BindingRequest createBinding(BindingRequest request) {
        checkBindingResources(request);
        return broker("binding.declare", () -> {
            admin.declareBinding(binding(request));
            return request;
        });
    }

    public void deleteBinding(BindingRequest request) {
        checkBindingResources(request);
        broker("binding.delete", () -> {
            admin.removeBinding(binding(request));
            return null;
        });
    }

    private Binding binding(BindingRequest request) {
        return new Binding(request.queue(), Binding.DestinationType.QUEUE, request.exchange(), request.routingKey(), Map.of());
    }

    private void checkBindingResources(BindingRequest request) {
        policy.requireMutable(request.exchange());
        policy.requireMutable(request.queue());
        broker("binding.inspect", () -> template.execute(channel -> {
            channel.exchangeDeclarePassive(request.exchange());
            channel.queueDeclarePassive(request.queue());
            return null;
        }));
    }

    private boolean exists(Supplier<?> probe) {
        try {
            probe.get();
            return true;
        } catch (AdminOperationException exception) {
            if (exception.status() == HttpStatus.NOT_FOUND) { return false; }
            throw exception;
        }
    }

    private <T> T broker(String operation, Supplier<T> action) {
        try {
            T result = action.get();
            LOG.info("RabbitMQ operation={} result=ok", operation);
            return result;
        } catch (AdminOperationException exception) {
            throw exception;
        } catch (AmqpException exception) {
            int code = replyCode(exception);
            LOG.warn("RabbitMQ operation={} result=failed replyCode={}", operation, code);
            throw switch (code) {
                case 403 -> new AdminOperationException(HttpStatus.FORBIDDEN, "El usuario del broker no tiene permiso para esta operacion.");
                case 404 -> new AdminOperationException(HttpStatus.NOT_FOUND, "La cola o el exchange solicitado no existe.");
                case 405, 406 -> new AdminOperationException(HttpStatus.CONFLICT,
                        "El recurso tiene una configuracion incompatible, esta en uso o no esta vacio.");
                case 530 -> new AdminOperationException(HttpStatus.BAD_REQUEST, "El broker no permite esta configuracion.");
                default -> new AdminOperationException(HttpStatus.SERVICE_UNAVAILABLE,
                        "RabbitMQ no esta disponible o no pudo confirmar la operacion.");
            };
        }
    }

    private int replyCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException signal) {
                if (signal.getReason() instanceof AMQP.Channel.Close close) { return close.getReplyCode(); }
                if (signal.getReason() instanceof AMQP.Connection.Close close) { return close.getReplyCode(); }
            }
        }
        return 0;
    }
}
