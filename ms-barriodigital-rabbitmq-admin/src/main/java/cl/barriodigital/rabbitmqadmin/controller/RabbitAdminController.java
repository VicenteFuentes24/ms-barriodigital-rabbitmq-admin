package cl.barriodigital.rabbitmqadmin.controller;

import java.net.URI;
import cl.barriodigital.rabbitmqadmin.dto.BindingRequest;
import cl.barriodigital.rabbitmqadmin.dto.CreateExchangeRequest;
import cl.barriodigital.rabbitmqadmin.dto.CreateQueueRequest;
import cl.barriodigital.rabbitmqadmin.dto.QueueInfoResponse;
import cl.barriodigital.rabbitmqadmin.service.RabbitAdminService;
import cl.barriodigital.rabbitmqadmin.validation.ResourcePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/rabbitmq", produces = MediaType.APPLICATION_JSON_VALUE)
public class RabbitAdminController {
    private final RabbitAdminService service;

    public RabbitAdminController(RabbitAdminService service) { this.service = service; }

    @PostMapping(value = "/queues", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateQueueRequest> createQueue(@Valid @RequestBody CreateQueueRequest request) {
        var declaration = service.createQueue(request);
        return ResponseEntity.status(declaration.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/api/rabbitmq/queues/" + declaration.resource().name())).body(declaration.resource());
    }

    @DeleteMapping("/queues/{name}")
    public ResponseEntity<Void> deleteQueue(@PathVariable @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String name) {
        service.deleteQueue(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/queues/{name}")
    public QueueInfoResponse queue(@PathVariable @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String name) {
        return service.queueInfo(name);
    }

    @PostMapping(value = "/exchanges", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateExchangeRequest> createExchange(@Valid @RequestBody CreateExchangeRequest request) {
        var declaration = service.createExchange(request);
        return ResponseEntity.status(declaration.created() ? HttpStatus.CREATED : HttpStatus.OK).body(declaration.resource());
    }

    @DeleteMapping("/exchanges/{name}")
    public ResponseEntity<Void> deleteExchange(@PathVariable @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String name) {
        service.deleteExchange(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/bindings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BindingRequest> createBinding(@Valid @RequestBody BindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBinding(request));
    }

    @DeleteMapping(value = "/bindings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> deleteBinding(@Valid @RequestBody BindingRequest request) {
        service.deleteBinding(request);
        return ResponseEntity.noContent().build();
    }
}
