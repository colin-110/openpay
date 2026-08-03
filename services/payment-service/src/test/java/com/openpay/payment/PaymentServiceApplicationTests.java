package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.RefundRepository;
import com.openpay.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

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

    // This context has no database at all, so the outbox is switched off and the one bean that
    // depends on it is mocked. PaymentPersistenceIT covers the real wiring.
    @MockBean
    private RefundRepository refundRepository;

    @MockBean
    private OutboxWriter outboxWriter;

    // Excluding DataSourceAutoConfiguration also removes the transaction manager, and without one
    // Spring Boot does not auto-configure the TransactionTemplate that PaymentService now injects
    // to write a payment and its outbox row together. Mocking the manager is not enough — @MockBean
    // registers after auto-configuration has already evaluated @ConditionalOnSingleCandidate — so
    // the template itself is supplied. This context proves the wiring holds, not that transactions
    // work; PaymentPersistenceIT covers the real thing against a real database.
    @MockBean
    private TransactionTemplate transactionTemplate;

    @Test
    void contextLoads() {
        assertThat(true).isTrue();
    }
}
