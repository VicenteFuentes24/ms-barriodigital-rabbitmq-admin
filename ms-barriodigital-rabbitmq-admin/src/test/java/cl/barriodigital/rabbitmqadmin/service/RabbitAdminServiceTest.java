package cl.barriodigital.rabbitmqadmin.service;

import java.io.IOException;
import java.net.ConnectException;
import cl.barriodigital.rabbitmqadmin.dto.*;
import cl.barriodigital.rabbitmqadmin.exception.AdminOperationException;
import cl.barriodigital.rabbitmqadmin.validation.ResourcePolicy;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitAdminServiceTest {
    RabbitAdmin admin;
    RabbitTemplate template;
    Channel channel;
    RabbitAdminService service;

    @BeforeEach
    void setup() throws Exception {
        admin = mock(RabbitAdmin.class);
        template = mock(RabbitTemplate.class);
        channel = mock(Channel.class);
        when(template.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Object>>any())).thenAnswer(invocation -> {
            try { return ((ChannelCallback<?>) invocation.getArgument(0)).doInRabbit(channel); }
            catch (IOException exception) { throw new AmqpIOException(exception); }
        });
        var info = new AMQP.Queue.DeclareOk.Builder().queue("ep3.queue").messageCount(0).consumerCount(0).build();
        when(channel.queueDeclarePassive(anyString())).thenReturn(info);
        when(admin.declareQueue(any(Queue.class))).thenAnswer(invocation -> ((Queue) invocation.getArgument(0)).getName());
        service = new RabbitAdminService(admin, template, new ResourcePolicy());
    }

    @Test
    void createsQueueWithSafeDefaults() throws Exception {
        when(channel.queueDeclarePassive("ep3.queue")).thenThrow(io(404));
        var result = service.createQueue(new CreateQueueRequest("ep3.queue", null, null, null));
        assertTrue(result.created());
        var argument = ArgumentCaptor.forClass(Queue.class);
        verify(admin).declareQueue(argument.capture());
        assertTrue(argument.getValue().isDurable());
        assertFalse(argument.getValue().isExclusive());
        assertFalse(argument.getValue().isAutoDelete());
        assertTrue(argument.getValue().getArguments().isEmpty());
    }

    @Test
    void repeatedQueueDeclarationIsReportedAsExisting() {
        assertFalse(service.createQueue(new CreateQueueRequest("ep3.queue", null, null, null)).created());
    }

    @Test
    void deletesQueueOnlyIfUnusedAndEmpty() throws Exception {
        service.deleteQueue("ep3.queue");
        verify(channel).queueDelete("ep3.queue", true, true);
        verify(admin, never()).deleteQueue(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "topic", "fanout"})
    void createsSupportedExchange(String type) throws Exception {
        when(channel.exchangeDeclarePassive("ep3.exchange")).thenThrow(io(404));
        var result = service.createExchange(new CreateExchangeRequest("ep3.exchange", type, null, null));
        assertTrue(result.created());
        verify(admin).declareExchange(argThat(exchange -> exchange.getType().equals(type) && exchange.isDurable() && !exchange.isAutoDelete()));
    }

    @Test
    void deletesExchangeOnlyIfUnbound() throws Exception {
        service.deleteExchange("ep3.exchange");
        verify(channel).exchangeDelete("ep3.exchange", true);
        verify(admin, never()).deleteExchange(anyString());
    }

    @Test
    void createsAndRemovesExactBinding() {
        var request = new BindingRequest("ep3.exchange", "ep3.queue", "demo.*");
        assertEquals(request, service.createBinding(request));
        var argument = ArgumentCaptor.forClass(Binding.class);
        verify(admin).declareBinding(argument.capture());
        assertEquals("ep3.exchange", argument.getValue().getExchange());
        assertEquals("ep3.queue", argument.getValue().getDestination());
        assertEquals("demo.*", argument.getValue().getRoutingKey());
        service.deleteBinding(request);
        verify(admin).removeBinding(argThat(binding -> binding.getRoutingKey().equals("demo.*")));
    }

    @Test
    void missingResourcePreventsBinding() throws Exception {
        when(channel.exchangeDeclarePassive("ep3.missing")).thenThrow(io(404));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.createBinding(new BindingRequest("ep3.missing", "ep3.queue", "demo.key")));
        verify(admin, never()).declareBinding(any());
    }

    @Test
    void returnsActualPassiveCounts() throws Exception {
        when(channel.queueDeclarePassive("ep3.queue")).thenReturn(new AMQP.Queue.DeclareOk.Builder()
                .queue("ep3.queue").messageCount(7).consumerCount(2).build());
        assertEquals(new QueueInfoResponse("ep3.queue", 7, 2), service.queueInfo("ep3.queue"));
    }

    @Test
    void missingQueueIsNotAvailabilityFailure() throws Exception {
        when(channel.queueDeclarePassive("ep3.missing")).thenThrow(io(404));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.deleteQueue("ep3.missing"));
    }

    @Test
    void configurationMismatchAndBusyQueueAreConflicts() throws Exception {
        doThrow(new AmqpIOException(io(406))).when(admin).declareQueue(any(Queue.class));
        assertStatus(HttpStatus.CONFLICT, () -> service.createQueue(new CreateQueueRequest("ep3.queue", true, false, false)));
        when(channel.queueDelete("ep3.queue", true, true)).thenThrow(io(406));
        assertStatus(HttpStatus.CONFLICT, () -> service.deleteQueue("ep3.queue"));
    }

    @Test
    void brokerDownIs503WithoutSensitiveDetails() {
        doThrow(new AmqpConnectException(new ConnectException("private-password")))
                .when(template).execute(org.mockito.ArgumentMatchers.<ChannelCallback<Object>>any());
        var exception = assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.queueInfo("ep3.queue"));
        assertFalse(exception.getMessage().contains("private-password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"q.cmd.email", "q.cmd.crew", "q.cmd.certificate", "q.cmd.email.dlq",
            "q.cmd.crew.dlq", "q.cmd.certificate.dlq", "cmd.direct", "cmd.topic", "cmd.dead.dlx", "amq.direct", "AMQ.topic"})
    void protectsSystemResourcesBeforeAnyBrokerCall(String name) {
        assertStatus(HttpStatus.CONFLICT, () -> service.deleteQueue(name));
        assertStatus(HttpStatus.CONFLICT, () -> service.deleteExchange(name));
        assertStatus(HttpStatus.CONFLICT, () -> service.createQueue(new CreateQueueRequest(name, null, null, null)));
        assertStatus(HttpStatus.CONFLICT, () -> service.createExchange(new CreateExchangeRequest(name, "direct", null, null)));
        assertStatus(HttpStatus.CONFLICT, () -> service.createBinding(new BindingRequest(name, "ep3.queue", "demo")));
        assertStatus(HttpStatus.CONFLICT, () -> service.deleteBinding(new BindingRequest("ep3.exchange", name, "demo")));
        verifyNoInteractions(admin, template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../escape", "bad/name", "bad\nname"})
    void rejectsInvalidNamesBeforeAnyBrokerCall(String name) {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.deleteQueue(name));
        verifyNoInteractions(admin, template);
    }

    private IOException io(int code) {
        var close = new AMQP.Channel.Close.Builder().replyCode(code).replyText("broker detail not for clients").classId(50).methodId(10).build();
        return new IOException(new ShutdownSignalException(false, false, close, channel));
    }

    private AdminOperationException assertStatus(HttpStatus status, org.junit.jupiter.api.function.Executable action) {
        var exception = assertThrows(AdminOperationException.class, action);
        assertEquals(status, exception.status());
        return exception;
    }
}
