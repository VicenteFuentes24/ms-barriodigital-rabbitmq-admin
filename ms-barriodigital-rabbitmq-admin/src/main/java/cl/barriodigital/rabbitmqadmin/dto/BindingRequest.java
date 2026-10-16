package cl.barriodigital.rabbitmqadmin.dto;

import cl.barriodigital.rabbitmqadmin.validation.ResourcePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BindingRequest(
        @NotBlank @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String exchange,
        @NotBlank @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String queue,
        @NotNull @Size(max = 255) @Pattern(regexp = ResourcePolicy.ROUTING_PATTERN) String routingKey) {
}
