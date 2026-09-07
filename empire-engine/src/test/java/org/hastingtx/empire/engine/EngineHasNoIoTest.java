package org.hastingtx.empire.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine is a pure function library: no clock, no files, no network, no framework.
 * This is the enforcer. (java.security.MessageDigest for the state hash is allowed.)
 */
class EngineHasNoIoTest {
    private static final Pattern BANNED = Pattern.compile(
            "^import\\s+(java\\.io\\.|java\\.nio\\.file\\.|java\\.net\\.|java\\.sql\\.|java\\.time\\.Clock|org\\.springframework|jakarta\\.|com\\.fasterxml)");

    @Test
    void mainSourcesImportNoIoOrFrameworks() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    return Files.readAllLines(p).stream().filter(l -> BANNED.matcher(l.strip()).find()).map(l -> p + ": " + l.strip());
                } catch (IOException e) { throw new RuntimeException(e); }
            }).toList();
            assertThat(offenders).isEmpty();
        }
    }
}
