package cl.barriodigital.rabbitmqadmin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "barriodigital.admin.username=test-admin",
        "barriodigital.admin.password=test-only-admin-password",
        "spring.rabbitmq.username=test-broker-user",
        "spring.rabbitmq.password=test-only-broker-password",
        "management.health.rabbit.enabled=false"
})
class MsBarriodigitalRabbitmqAdminApplicationTests {

	@Test
	void contextLoads() {
	}

}
