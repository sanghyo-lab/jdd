package com.jdd.voc.domain;

import java.util.List;
import java.util.Optional;

public interface TicketRepository {
    void insert(Ticket ticket);
    Optional<Ticket> find(String ticketId);
    List<Ticket> list(Ticket.Status status, String assigneeId, int limit, int offset);
    boolean replace(Ticket ticket, long expectedVersion);
}
