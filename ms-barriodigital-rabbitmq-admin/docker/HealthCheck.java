import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class HealthCheck {
    public static void main(String[] args) {
        try {
            String user = System.getenv("ADMIN_HTTP_USERNAME");
            String password = System.getenv("ADMIN_HTTP_PASSWORD");
            if (user == null || password == null) { System.exit(1); }
            String auth = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
            String port = System.getenv().getOrDefault("SERVER_PORT", "8088");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(7)).header("Authorization", "Basic " + auth).GET().build();
            System.exit(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1);
        } catch (Exception exception) {
            // Health output must not contain credentials, connection URLs or stack traces.
            System.exit(1);
        }
    }
}
