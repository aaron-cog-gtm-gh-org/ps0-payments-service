package com.acme.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "acme.payments.fx-base-url=http://localhost:8091",
        "acme.payments.ledger-base-url=http://localhost:8092",
        "acme.payments.timeout=2s"
})
class PaymentsServiceApplicationTests {
    @Autowired
    private SettlementService settlementService;

    @Autowired
    private PaymentsProperties paymentsProperties;

    @Test
    void loadsSettlementServiceAndConfiguredTimeout() {
        assertThat(settlementService).isNotNull();
        assertThat(paymentsProperties.timeout()).isEqualTo(java.time.Duration.ofSeconds(2));
    }
}
