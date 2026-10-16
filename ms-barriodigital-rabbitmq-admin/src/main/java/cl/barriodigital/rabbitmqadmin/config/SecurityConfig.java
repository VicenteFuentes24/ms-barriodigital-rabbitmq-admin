package cl.barriodigital.rabbitmqadmin.config;

import java.io.IOException;
import cl.barriodigital.rabbitmqadmin.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean
    UserDetailsService adminUsers(PasswordEncoder encoder,
            @Value("${barriodigital.admin.username}") String username,
            @Value("${barriodigital.admin.password}") String password) {
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || password.isBlank() || password.length() < 16
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            // Do not put credential values in Bean Validation's rejected-value diagnostics.
            throw new IllegalStateException("Define ADMIN_HTTP_USERNAME y ADMIN_HTTP_PASSWORD (16 a 72 caracteres).");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("Admin").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JsonMapper mapper) throws Exception {
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(request -> request.getRequestURI().startsWith("/api/rabbitmq/")
                                && "true".equals(request.getHeader("X-Admin-Request"))))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("Admin"))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"BarrioDigital RabbitMQ Admin\"");
                    writeError(mapper, request, response, HttpStatus.UNAUTHORIZED, "Se requieren credenciales administrativas validas.");
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        writeError(mapper, request, response, HttpStatus.FORBIDDEN,
                                "Acceso denegado. Se requiere rol Admin y X-Admin-Request: true para modificar recursos.")))
                .build();
    }

    private void writeError(JsonMapper mapper, HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(status, message, request.getRequestURI()));
    }
}
