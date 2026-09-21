package com.jdd.commerce.api;

import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.common.Inputs;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".requestId";
    private final JsonMapper mapper = JsonMapper.builder().build();
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null) requestId = UUID.randomUUID().toString();
        try { Inputs.identifier(requestId, "X-Request-Id"); }
        catch (CommerceException invalid) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write(mapper.writeValueAsString(Map.of("code", invalid.code(),
                    "message", invalid.getMessage(), "retryable", false)));
            return;
        }
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        chain.doFilter(request, response);
    }
}
