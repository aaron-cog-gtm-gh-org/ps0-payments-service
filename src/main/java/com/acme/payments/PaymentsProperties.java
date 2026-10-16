package com.acme.payments;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "acme.payments")
public record PaymentsProperties(String fxBaseUrl, String ledgerBaseUrl,
                                 @DefaultValue("5s") Duration timeout) {
}
