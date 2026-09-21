package com.jdd.commerce;

import com.jdd.commerce.inventory.port.InventoryReadObserver;
import com.jdd.commerce.payment.port.RefundFault;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CommerceConfiguration {
    @Bean Clock commerceClock() { return Clock.systemUTC(); }
    @Bean
    @ConditionalOnProperty(name = "jdd.reproduction-enabled", havingValue = "false", matchIfMissing = true)
    InventoryReadObserver inventoryReadObserver() { return (productId, checkoutKey, quantity) -> {}; }
    @Bean
    @ConditionalOnProperty(name = "jdd.reproduction-enabled", havingValue = "false", matchIfMissing = true)
    RefundFault refundFault() { return (orderId, requestKey) -> false; }
}
