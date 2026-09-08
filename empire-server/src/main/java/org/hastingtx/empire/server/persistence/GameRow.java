package org.hastingtx.empire.server.persistence;

import java.time.Instant;

public record GameRow(long id, String name, String preset, String configYaml, String configHash, long seed, String status,
                      long updateNumber, int width, int height, boolean wrapX, boolean wrapY, Instant createdAt, Long createdBy) {}
