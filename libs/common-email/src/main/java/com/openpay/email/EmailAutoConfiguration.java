package com.openpay.email;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires {@link EmailNotifier} into any service that adds this library.
 *
 * <p>Declared here rather than annotated {@code @Component}, the same reason {@code
 * AuditAutoConfiguration} is: this package sits outside every application's component scan. A
 * service that wants it just adds the dependency — {@code spring-boot-starter-mail} comes along
 * transitively — and sets {@code spring.mail.host}, the same property {@link
 * MailSenderAutoConfiguration} already reads, so there is exactly one place SMTP is configured,
 * not a second one this library invents.
 */
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
@EnableAsync
public class EmailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EmailNotifier emailNotifier(
            JavaMailSender mailSender,
            @Value("${openpay.email.from:no-reply@openpay.local}") String fromAddress) {
        return new EmailNotifier(mailSender, fromAddress);
    }

    /**
     * Small and bounded on purpose: this is notification volume, not payment volume, and a queue
     * that could grow without limit would just turn a stuck SMTP relay into a slow memory leak
     * instead of a pile of WARN log lines.
     */
    @Bean("emailNotifierExecutor")
    @ConditionalOnMissingBean(name = "emailNotifierExecutor")
    public Executor emailNotifierExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-notify-");
        executor.initialize();
        return executor;
    }
}
