package cl.barriodigital.rabbitmqadmin;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import cl.barriodigital.rabbitmqadmin.config.JsonConfig;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RABBITMQ_ADMIN_INTEGRATION_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RabbitAdminBrokerIT {
    final JsonMapper mapper = new JsonConfig().jsonMapper();
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    final String vhost = "bd-admin-it-" + UUID.randomUUID();
    ConfigurableApplicationContext application;
    Connection connection;
    Channel publisher;
    boolean created;
    String baseUrl;
    String adminAuth;
    String brokerAuth;

    @BeforeAll
    void start() throws Exception {
        adminAuth = basic(required("ADMIN_HTTP_USERNAME"), required("ADMIN_HTTP_PASSWORD"));
        brokerAuth = basic(required("RABBITMQ_USERNAME"), required("RABBITMQ_PASSWORD"));
        try {
            created = true;
            management("PUT", "/api/vhosts/" + vhost, Map.of());
            management("PUT", "/api/permissions/" + vhost + "/" + encode(required("RABBITMQ_USERNAME")),
                    Map.of("configure", ".*", "write", ".*", "read", ".*"));
            var factory = new ConnectionFactory();
            factory.setHost(env("RABBITMQ_HOST", "localhost"));
            factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
            factory.setUsername(required("RABBITMQ_USERNAME"));
            factory.setPassword(required("RABBITMQ_PASSWORD"));
            factory.setVirtualHost(vhost);
            connection = factory.newConnection("rabbitmq-admin-isolated-it");
            publisher = connection.createChannel();
            publisher.confirmSelect();
            application = startApplication(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
            baseUrl = "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port");
        } catch (Exception | AssertionError exception) {
            cleanup();
            throw exception;
        }
    }

    ConfigurableApplicationContext startApplication(int port) {
        return new SpringApplicationBuilder(MsBarriodigitalRabbitmqAdminApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.rabbitmq.addresses=",
                "--spring.rabbitmq.host=" + env("RABBITMQ_HOST", "localhost"),
                "--spring.rabbitmq.port=" + port, "--spring.rabbitmq.virtual-host=" + vhost);
    }

    @AfterAll
    void cleanup() throws Exception {
        try {
            if (application != null) { application.close(); application = null; }
            if (connection != null && connection.isOpen()) { connection.close(); }
        } finally {
            if (created) { management("DELETE", "/api/vhosts/" + vhost, null); created = false; }
        }
    }

    @Test @Order(1)
    void startupDoesNotDeclareAnyApplicationResources() throws Exception {
        assertEquals(0, management("GET", "/api/queues/" + vhost, null).size());
        assertTrue(application.getBeansOfType(org.springframework.amqp.core.Queue.class).isEmpty());
        assertTrue(application.getBeansOfType(org.springframework.amqp.core.Exchange.class).isEmpty());
        assertTrue(application.getBeansOfType(org.springframework.amqp.core.Binding.class).isEmpty());
    }

    @Test @Order(2)
    void createsInspectsAndDeletesRealQueue() throws Exception {
        var response = api("POST", "/api/rabbitmq/queues", Map.of("name", "ep3.it.queue"));
        assertEquals(201, response.statusCode());
        var queue = mapper.readTree(response.body());
        assertTrue(queue.path("durable").asBoolean());
        assertFalse(queue.path("exclusive").asBoolean());
        assertFalse(queue.path("autoDelete").asBoolean());
        assertEquals(200, api("POST", "/api/rabbitmq/queues", Map.of("name", "ep3.it.queue")).statusCode());
        var info = api("GET", "/api/rabbitmq/queues/ep3.it.queue", null);
        assertEquals(200, info.statusCode());
        assertEquals(0, mapper.readTree(info.body()).path("messageCount").asInt());
        assertEquals(0, publisher.queueDeclarePassive("ep3.it.queue").getMessageCount());
        assertEquals(204, api("DELETE", "/api/rabbitmq/queues/ep3.it.queue", null).statusCode());
        assertEquals(404, api("GET", "/api/rabbitmq/queues/ep3.it.queue", null).statusCode());
    }

    @ParameterizedTest @Order(3)
    @ValueSource(strings = {"direct", "topic", "fanout"})
    void realExchangesBindingsRoutingAndRemoval(String type) throws Exception {
        String queue = "ep3.it." + type + ".queue";
        String exchange = "ep3.it." + type + ".exchange";
        String key = type.equals("topic") ? "demo.*" : type.equals("fanout") ? "" : "demo.created";
        var binding = Map.of("exchange", exchange, "queue", queue, "routingKey", key);
        assertEquals(201, api("POST", "/api/rabbitmq/queues", Map.of("name", queue)).statusCode());
        assertEquals(201, api("POST", "/api/rabbitmq/exchanges", Map.of("name", exchange, "type", type)).statusCode());
        assertEquals(200, api("POST", "/api/rabbitmq/exchanges", Map.of("name", exchange, "type", type)).statusCode());
        publisher.exchangeDeclarePassive(exchange);
        assertEquals(201, api("POST", "/api/rabbitmq/bindings", binding).statusCode());
        assertEquals(409, api("DELETE", "/api/rabbitmq/exchanges/" + exchange, null).statusCode());
        publish(exchange, "demo.created", "routing-proof");
        var delivered = publisher.basicGet(queue, true);
        assertNotNull(delivered);
        assertEquals("routing-proof", new String(delivered.getBody(), StandardCharsets.UTF_8));
        assertEquals(204, api("DELETE", "/api/rabbitmq/bindings", binding).statusCode());
        // Removing an already absent binding is idempotent while both resources still exist.
        assertEquals(204, api("DELETE", "/api/rabbitmq/bindings", binding).statusCode());
        publish(exchange, "demo.created", "must-not-route");
        assertNull(publisher.basicGet(queue, true));
        assertEquals(204, api("DELETE", "/api/rabbitmq/exchanges/" + exchange, null).statusCode());
        assertEquals(204, api("DELETE", "/api/rabbitmq/queues/" + queue, null).statusCode());
    }

    @Test @Order(4)
    void conditionalQueueDeletionPreservesMessagesAndConsumers() throws Exception {
        String queue = "ep3.it.busy";
        assertEquals(201, api("POST", "/api/rabbitmq/queues", Map.of("name", queue)).statusCode());
        publish("", queue, "do-not-delete");
        assertEquals(409, api("DELETE", "/api/rabbitmq/queues/" + queue, null).statusCode());
        assertEquals("do-not-delete", new String(publisher.basicGet(queue, true).getBody(), StandardCharsets.UTF_8));
        var consumer = publisher.basicConsume(queue, false, (tag, message) -> { }, tag -> { });
        assertEquals(409, api("DELETE", "/api/rabbitmq/queues/" + queue, null).statusCode());
        publisher.basicCancel(consumer);
        assertEquals(204, api("DELETE", "/api/rabbitmq/queues/" + queue, null).statusCode());
    }

    @Test @Order(5)
    void realConfigurationConflictsReturn409() throws Exception {
        String queue = "ep3.it.conflict";
        String exchange = "ep3.it.conflict.exchange";
        assertEquals(201, api("POST", "/api/rabbitmq/queues", Map.of("name", queue)).statusCode());
        assertEquals(409, api("POST", "/api/rabbitmq/queues", Map.of("name", queue, "autoDelete", true)).statusCode());
        assertEquals(201, api("POST", "/api/rabbitmq/exchanges", Map.of("name", exchange, "type", "direct")).statusCode());
        assertEquals(409, api("POST", "/api/rabbitmq/exchanges", Map.of("name", exchange, "type", "topic")).statusCode());
        assertEquals(204, api("DELETE", "/api/rabbitmq/exchanges/" + exchange, null).statusCode());
        assertEquals(204, api("DELETE", "/api/rabbitmq/queues/" + queue, null).statusCode());
    }

    @Test @Order(6)
    void missingResourcesReturn404() throws Exception {
        assertEquals(404, api("DELETE", "/api/rabbitmq/queues/ep3.missing", null).statusCode());
        assertEquals(404, api("DELETE", "/api/rabbitmq/exchanges/ep3.missing", null).statusCode());
        assertEquals(404, api("POST", "/api/rabbitmq/bindings",
                Map.of("queue", "ep3.missing", "exchange", "ep3.missing", "routingKey", "demo")).statusCode());
    }

    @Test @Order(7)
    void reservedResourcesCannotBeCreatedDeletedOrRebound() throws Exception {
        for (var queue : new String[]{"q.cmd.email", "q.cmd.crew", "q.cmd.certificate", "q.cmd.email.dlq", "q.cmd.crew.dlq", "q.cmd.certificate.dlq"}) {
            assertEquals(409, api("DELETE", "/api/rabbitmq/queues/" + queue, null).statusCode());
            assertEquals(409, api("POST", "/api/rabbitmq/queues", Map.of("name", queue)).statusCode());
        }
        for (var exchange : new String[]{"cmd.direct", "cmd.topic", "cmd.dead.dlx", "amq.direct"}) {
            assertEquals(409, api("DELETE", "/api/rabbitmq/exchanges/" + exchange, null).statusCode());
        }
        assertEquals(409, api("POST", "/api/rabbitmq/bindings", Map.of("exchange", "cmd.direct", "queue", "ep3.test", "routingKey", "email.send")).statusCode());
        assertEquals(0, management("GET", "/api/queues/" + vhost, null).size());
    }

    @Test @Order(8)
    void securityAndHealthAreRealHttp() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health")).GET().build();
        assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        var health = api("GET", "/actuator/health", null);
        assertEquals(200, health.statusCode());
        assertEquals("UP", mapper.readTree(health.body()).path("status").asString());
        var forbidden = HttpRequest.newBuilder(URI.create(baseUrl + "/api/rabbitmq/queues/ep3.test"))
                .header("Authorization", adminAuth).DELETE().build();
        assertEquals(403, http.send(forbidden, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, api("POST", "/api/rabbitmq/queues", Map.of("name", " ")).statusCode());
    }

    @Test @Order(9)
    void brokerUnavailableReallyReturns503WithoutStoppingExistingBroker() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        try (var unavailable = startApplication(closedPort)) {
            var port = unavailable.getEnvironment().getProperty("local.server.port");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/rabbitmq/queues/ep3.test"))
                    .header("Authorization", adminAuth).timeout(Duration.ofSeconds(15)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertEquals(503, mapper.readTree(response.body()).path("status").asInt());
            assertFalse(response.body().contains(required("RABBITMQ_PASSWORD")));
        }
    }

    void publish(String exchange, String key, String value) throws Exception {
        publisher.basicPublish(exchange, key, new AMQP.BasicProperties.Builder().deliveryMode(2).build(), value.getBytes(StandardCharsets.UTF_8));
        publisher.waitForConfirmsOrDie(5000);
    }

    HttpResponse<String> api(String method, String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", adminAuth).header("X-Admin-Request", "true").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode management(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(env("RABBITMQ_MANAGEMENT_URL", "http://127.0.0.1:15672") + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", brokerAuth).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (method.equals("DELETE") && response.statusCode() == 404) { return mapper.nullNode(); }
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "RabbitMQ Management HTTP " + response.statusCode() + " para " + path);
        return response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    static String required(String name) {
        var value = env(name, "");
        assertFalse(value.isBlank(), "Define " + name + " para la integracion.");
        return value;
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
