package cl.barriodigital.rabbitmqadmin.dto;

import cl.barriodigital.rabbitmqadmin.validation.ResourcePolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record CreateQueueRequest(
        @NotBlank @Pattern(regexp = ResourcePolicy.NAME_PATTERN) String name,
        Boolean durable, Boolean exclusive, Boolean autoDelete) {
    public CreateQueueRequest {
        durable = durable == null ? true : durable;
        exclusive = exclusive == null ? false : exclusive;
        autoDelete = autoDelete == null ? false : autoDelete;
    }

    @AssertTrue(message = "Una cola exclusive requiere durable=false y autoDelete=true.")
    @JsonIgnore
    public boolean isConfigurationCoherent() {
        return !exclusive || (!durable && autoDelete);
    }
}
