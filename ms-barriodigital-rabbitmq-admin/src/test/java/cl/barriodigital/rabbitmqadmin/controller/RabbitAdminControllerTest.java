package cl.barriodigital.rabbitmqadmin.controller;

import cl.barriodigital.rabbitmqadmin.dto.*;
import cl.barriodigital.rabbitmqadmin.exception.AdminOperationException;
import cl.barriodigital.rabbitmqadmin.service.RabbitAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "barriodigital.admin.username=test-admin", "barriodigital.admin.password=test-only-admin-password",
        "spring.rabbitmq.username=test-broker-user", "spring.rabbitmq.password=test-only-broker-password",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class RabbitAdminControllerTest {
    private final MockMvc mvc;
    @MockitoBean RabbitAdminService service;

    @Autowired
    RabbitAdminControllerTest(MockMvc mvc) { this.mvc = mvc; }

    @Test
    void noAuthenticationIs401JsonEvenWithSpoofedRole() throws Exception {
        mvc.perform(get("/api/rabbitmq/queues/ep3.queue").header("X-User-Roles", "Admin"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void wrongRoleIs403() throws Exception {
        mvc.perform(get("/api/rabbitmq/queues/ep3.queue").with(user("operator").roles("Operador")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(service);
    }

    @Test
    void mutationsRequireExplicitNonBrowserHeader() throws Exception {
        mvc.perform(delete("/api/rabbitmq/queues/ep3.queue").with(httpBasic("test-admin", "test-only-admin-password")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void queueCreationDelegatesToServiceAndReturns201() throws Exception {
        var queue = new CreateQueueRequest("ep3.queue", null, null, null);
        when(service.createQueue(any())).thenReturn(new RabbitAdminService.Declaration<>(true, queue));
        mvc.perform(admin(post("/api/rabbitmq/queues")).content("{\"name\":\"ep3.queue\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/rabbitmq/queues/ep3.queue"))
                .andExpect(jsonPath("$.durable").value(true)).andExpect(jsonPath("$.exclusive").value(false))
                .andExpect(jsonPath("$.autoDelete").value(false)).andExpect(jsonPath("$.configurationCoherent").doesNotExist());
        verify(service).createQueue(queue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\" \"}", "{\"name\":\"../bad\"}",
            "{\"name\":\"ep3.queue\",\"durable\":\"true\"}", "{\"name\":\"ep3.queue\",\"unexpected\":true}",
            "{\"name\":\"ep3.queue\",\"exclusive\":true}", "{bad", "null",
            "{\"name\":\"ep3.queue\",\"name\":\"other.queue\"}"})
    void invalidQueuePayloadIs400WithoutCallingService(String body) throws Exception {
        mvc.perform(admin(post("/api/rabbitmq/queues")).content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "topic", "fanout"})
    void supportsExchangeTypes(String type) throws Exception {
        var exchange = new CreateExchangeRequest("ep3.exchange", type, null, null);
        when(service.createExchange(any())).thenReturn(new RabbitAdminService.Declaration<>(true, exchange));
        mvc.perform(admin(post("/api/rabbitmq/exchanges")).content("{\"name\":\"ep3.exchange\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value(type));
        verify(service).createExchange(exchange);
    }

    @Test
    void invalidExchangeTypeIs400() throws Exception {
        mvc.perform(admin(post("/api/rabbitmq/exchanges")).content("{\"name\":\"ep3.exchange\",\"type\":\"headers\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void deletesQueueAndExchange() throws Exception {
        mvc.perform(admin(delete("/api/rabbitmq/queues/ep3.queue"))).andExpect(status().isNoContent());
        mvc.perform(admin(delete("/api/rabbitmq/exchanges/ep3.exchange"))).andExpect(status().isNoContent());
        verify(service).deleteQueue("ep3.queue");
        verify(service).deleteExchange("ep3.exchange");
    }

    @Test
    void createsAndDeletesBindingUsingSameDto() throws Exception {
        var request = new BindingRequest("ep3.exchange", "ep3.queue", "demo.*");
        when(service.createBinding(request)).thenReturn(request);
        String body = "{\"exchange\":\"ep3.exchange\",\"queue\":\"ep3.queue\",\"routingKey\":\"demo.*\"}";
        mvc.perform(admin(post("/api/rabbitmq/bindings")).content(body)).andExpect(status().isCreated());
        mvc.perform(admin(delete("/api/rabbitmq/bindings")).content(body)).andExpect(status().isNoContent());
        verify(service).createBinding(request);
        verify(service).deleteBinding(request);
    }

    @Test
    void missingRoutingKeyIsNotSameAsExplicitEmptyKey() throws Exception {
        mvc.perform(admin(post("/api/rabbitmq/bindings")).content("{\"exchange\":\"ep3.exchange\",\"queue\":\"ep3.queue\"}"))
                .andExpect(status().isBadRequest());
        var binding = new BindingRequest("ep3.exchange", "ep3.queue", "");
        when(service.createBinding(binding)).thenReturn(binding);
        mvc.perform(admin(post("/api/rabbitmq/bindings")).content("{\"exchange\":\"ep3.exchange\",\"queue\":\"ep3.queue\",\"routingKey\":\"\"}"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 503})
    void preservesServiceErrorStatus(int status) throws Exception {
        when(service.queueInfo("ep3.queue")).thenThrow(new AdminOperationException(HttpStatus.valueOf(status), "Operacion no completada."));
        mvc.perform(admin(get("/api/rabbitmq/queues/ep3.queue"))).andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.path").value("/api/rabbitmq/queues/ep3.queue"));
    }

    @Test
    void unexpectedErrorsAreSanitized500() throws Exception {
        when(service.queueInfo(anyString())).thenThrow(new IllegalStateException("private-password"));
        mvc.perform(admin(get("/api/rabbitmq/queues/ep3.queue"))).andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
    }

    @Test
    void healthAlsoRequiresAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(admin(get("/actuator/health"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.with(httpBasic("test-admin", "test-only-admin-password"))
                .header("X-Admin-Request", "true").contentType(MediaType.APPLICATION_JSON);
    }
}
