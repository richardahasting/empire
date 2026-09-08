package org.hastingtx.empire.server.auth;

public record Account(long id, String email, String name, boolean admin) {}
