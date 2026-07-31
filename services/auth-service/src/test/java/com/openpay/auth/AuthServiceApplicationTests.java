package com.openpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.auth.domain.ApiKeyRepository;
import com.openpay.auth.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "openpay.jwt.secret=test-secret-that-is-long-enough-for-hs256",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class AuthServiceApplicationTests {

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private UserRepository userRepository;

    @Test
    void contextLoads() {
        assertThat(true).isTrue();
    }
}
