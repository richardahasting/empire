package org.hastingtx.empire.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Thin JSON helper for jsonb columns. Uses its own Jackson 2 mapper (the one empire-config
 * already depends on); Spring Boot 4's web layer runs Jackson 3 separately.
 */
@Component
public class Json {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    public String write(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public <T> T read(String s, Class<T> type) {
        try { return mapper.readValue(s, type); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
