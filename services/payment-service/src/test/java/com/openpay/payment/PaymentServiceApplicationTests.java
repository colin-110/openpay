package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "openpay.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class PaymentServiceApplicationTests {

    @MockBean
    private PaymentRepository paymentRepository;

    @MockBean
    private PaymentEventRepository paymentEventRepository;

    @MockBean
    private OutboxRepository outboxRepository;

    @Test
    void contextLoads() {
        assertThat(true).isTrue();
    }
}
