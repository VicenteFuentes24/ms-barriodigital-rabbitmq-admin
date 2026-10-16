package cl.barriodigital.rabbitmqadmin.validation;

import java.util.Locale;
import cl.barriodigital.rabbitmqadmin.exception.AdminOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ResourcePolicy {
    public static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,199}";
    public static final String ROUTING_PATTERN = "[A-Za-z0-9._*#:-]*";

    public void validateName(String name) {
        if (name == null || !name.matches(NAME_PATTERN)) {
            throw new AdminOperationException(HttpStatus.BAD_REQUEST, "Nombre de recurso invalido (ASCII, 1 a 200 caracteres).");
        }
    }

    public void requireMutable(String name) {
        validateName(name);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("amq.") || lower.startsWith("cmd.") || lower.startsWith("q.cmd.")) {
            throw new AdminOperationException(HttpStatus.CONFLICT,
                    "El recurso esta reservado para RabbitMQ o la topologia central de BarrioDigital.");
        }
    }
}
