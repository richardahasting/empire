package org.hastingtx.empire.server.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonValue;

/** Pass a jsonb column through untouched. */
public record RawJson(@JsonRawValue @JsonValue String json) {
    public static RawJson of(String json) { return new RawJson(json == null ? "null" : json); }
}
