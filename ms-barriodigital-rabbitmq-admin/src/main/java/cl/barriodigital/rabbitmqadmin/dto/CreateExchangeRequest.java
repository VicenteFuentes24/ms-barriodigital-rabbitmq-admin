package cl.barriodigital.rabbitmqadmin.dto;

import cl.barriodigital.rabbitmqadmin.validation.ResourcePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateExchangeRequest(
        @NotBlank @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String name,
        @NotBlank @Pattern(regexp = "direct|topic|fanout") String type,
        Boolean durable, Boolean autoDelete) {
    public CreateExchangeRequest {
        durable = durable == null ? true : durable;
        autoDelete = autoDelete == null ? false : autoDelete;
    }
}
