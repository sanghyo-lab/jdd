package com.jdd.voc;

import com.jdd.voc.domain.Ticket;
import com.jdd.voc.domain.TicketService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TicketController {
    private final TicketService tickets;
    public TicketController(TicketService tickets) { this.tickets = tickets; }

    @GetMapping("/assignees")
    public Map<String, ?> assignees() { return Map.of("items", TicketService.ASSIGNEES); }

    @PostMapping("/tickets")
    public ResponseEntity<Ticket> create(@RequestBody Map<String, Object> body) {
        Ticket ticket = tickets.create(body);
        return ResponseEntity.created(URI.create("/api/tickets/" + ticket.ticketId())).body(ticket);
    }

    @GetMapping("/tickets")
    public Map<String, ?> list(@RequestParam(required=false) String status,
            @RequestParam(required=false) String assigneeId, @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return Map.of("items", tickets.list(status, assigneeId, limit, offset));
    }

    @GetMapping("/tickets/{ticketId}")
    public Map<String, ?> detail(@PathVariable String ticketId) {
        return Map.of("ticket", tickets.get(ticketId), "analyses", List.of());
    }

    @PatchMapping("/tickets/{ticketId}")
    public Ticket patch(@PathVariable String ticketId, @RequestBody Map<String, Object> body) {
        return tickets.patch(ticketId, body);
    }
}
