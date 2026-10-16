package cl.barriodigital.rabbitmqadmin.exception;

import org.springframework.http.HttpStatus;

public class AdminOperationException extends RuntimeException {
    private final HttpStatus status;

    public AdminOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
