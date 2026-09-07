package org.hastingtx.empire.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.hastingtx.empire.engine.config.GameConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Loads a world config: resolves {@code extends:} chains with deep merge (override wins,
 * lists replace), normalises keys, binds to the GameConfig record tree with unknown keys
 * rejected, then runs the cross-reference checks in {@link SchemaValidator}.
 *
 * <p>Key normalisation: an underscore immediately before a digit is dropped, so the YAML
 * {@code at_100} binds to the Java field {@code at100}. Every other key is snake_case →
 * camelCase.
 */
public final class ConfigLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper binder = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false);
    private final ObjectMapper canonical = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public record Loaded(GameConfig config, String hash, Map<String, Object> raw) {}

    public GameConfig load(Path file) { return loadWithHash(file).config(); }

    public Loaded loadWithHash(Path file) { return load(new ConfigSource.File(file)); }

    /** A preset by name from the bundled config/presets, e.g. "teaching". */
    public Loaded loadPreset(String name) { return load(new ConfigSource.Classpath("config/presets/" + name + ".yaml")); }

    /** The bundled schema itself, which is a complete "classic" configuration. */
    public Loaded loadSchema() { return load(new ConfigSource.Classpath("config/schema.yaml")); }

    Loaded load(ConfigSource src) {
        Map<String, Object> merged = resolve(src, new ArrayDeque<>());
        merged.remove("extends");
        Map<String, Object> normalised = normalise(merged);
        GameConfig cfg;
        try {
            cfg = binder.convertValue(normalised, GameConfig.class);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("config " + src.name() + ": " + rootMessage(e), e);
        }
        SchemaValidator.validate(cfg);
        return new Loaded(cfg, hash(normalised), normalised);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(ConfigSource src, Deque<String> chain) {
        if (chain.contains(src.name())) throw new ConfigException("extends cycle: " + chain + " -> " + src.name());
        chain.push(src.name());
        Map<String, Object> doc;
        try (InputStream in = src.open()) {
            Object o = yaml.readValue(in, Object.class);
            if (!(o instanceof Map)) throw new ConfigException(src.name() + " is not a YAML mapping");
            doc = new LinkedHashMap<>((Map<String, Object>) o);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + src.name(), e);
        }
        Object ext = doc.remove("extends");
        if (ext == null) { chain.pop(); return doc; }
        Map<String, Object> base = resolve(src.sibling(ext.toString()), chain);
        chain.pop();
        return merge(base, doc);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (var e : over.entrySet()) {
            Object b = out.get(e.getKey()), o = e.getValue();
            if (b instanceof Map && o instanceof Map) out.put(e.getKey(), merge((Map<String, Object>) b, (Map<String, Object>) o));
            else out.put(e.getKey(), o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> normalise(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String k = e.getKey().replaceAll("_(?=\\d)", "");
            Object v = e.getValue();
            if (v instanceof Map) v = normalise((Map<String, Object>) v);
            else if (v instanceof List) {
                List<Object> l = new ArrayList<>();
                for (Object x : (List<Object>) v) l.add(x instanceof Map ? normalise((Map<String, Object>) x) : x);
                v = l;
            }
            out.put(k, v);
        }
        return out;
    }

    private String hash(Map<String, Object> m) {
        try {
            byte[] json = canonical.writeValueAsBytes(m);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    /** Render a config back to canonical YAML (for dumps and diffs). */
    public String toYaml(Map<String, Object> raw) {
        try { return yaml.writeValueAsString(raw); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
