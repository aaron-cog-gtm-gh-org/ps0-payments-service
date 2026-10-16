package com.acme.payments;

import com.acme.commons.http.AcmeHttpClient;
import com.acme.commons.http.HttpClientFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsClientConfiguration {
    @Bean
    AcmeHttpClient fxHttpClient(PaymentsProperties properties) {
        return HttpClientFactory.create(properties.fxBaseUrl(), properties.timeout());
    }

    @Bean
    AcmeHttpClient ledgerHttpClient(PaymentsProperties properties) {
        return HttpClientFactory.create(properties.ledgerBaseUrl(), properties.timeout());
    }

    @Bean
    FxRateClient fxRateClient(@Qualifier("fxHttpClient") AcmeHttpClient client) {
        return new FxRateClient(client);
    }

    @Bean
    LedgerClient ledgerClient(@Qualifier("ledgerHttpClient") AcmeHttpClient client) {
        return new LedgerClient(client);
    }
}
