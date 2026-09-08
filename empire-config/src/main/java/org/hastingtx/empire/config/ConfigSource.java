package org.hastingtx.empire.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where a YAML document comes from, so {@code extends:} can resolve relative paths. */
interface ConfigSource {
    String name();
    InputStream open() throws IOException;
    ConfigSource sibling(String relative);

    record File(Path path) implements ConfigSource {
        public String name() { return path.toString(); }
        public InputStream open() throws IOException { return Files.newInputStream(path); }
        public ConfigSource sibling(String relative) { return new File(path.resolveSibling(relative).normalize()); }
    }

    record Classpath(String resource) implements ConfigSource {
        public String name() { return "classpath:" + resource; }
        public InputStream open() throws IOException {
            InputStream in = ConfigSource.class.getClassLoader().getResourceAsStream(resource);
            if (in == null) throw new IOException("resource not found: " + resource);
            return in;
        }
        public ConfigSource sibling(String relative) {
            Path p = Path.of(resource).resolveSibling(relative).normalize();
            return new Classpath(p.toString().replace('\\', '/'));
        }
    }
}
