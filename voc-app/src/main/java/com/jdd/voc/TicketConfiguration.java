package com.jdd.voc;

import com.jdd.voc.domain.TicketRepository;
import com.jdd.voc.domain.TicketService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TicketConfiguration {
    @Bean TicketService ticketService(TicketRepository repository) { return new TicketService(repository, Clock.systemUTC()); }
}
