package com.jdd.voc;

import com.jdd.voc.domain.TicketRepository;
import com.jdd.voc.domain.TicketService;
import com.jdd.voc.domain.AnalysisRepository;
import com.jdd.voc.domain.AnalysisService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TicketConfiguration {
    @Bean Clock vocClock() { return Clock.systemUTC(); }
    @Bean TicketService ticketService(TicketRepository repository, Clock clock) { return new TicketService(repository, clock); }
    @Bean AnalysisService analysisService(AnalysisRepository repository, Clock clock) { return new AnalysisService(repository, clock); }
}
