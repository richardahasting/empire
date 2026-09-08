-- Accounts: passwordless. Tokens are stored as SHA-256 hashes, never plaintext.
CREATE TABLE account (
    id          BIGSERIAL PRIMARY KEY,
    email       TEXT NOT NULL UNIQUE,             -- normalised lower-case at every boundary
    name        TEXT NOT NULL,
    is_admin    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login  TIMESTAMPTZ
);

CREATE TABLE auth_token (
    token_hash  TEXT PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL CHECK (kind IN ('magic', 'session')),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_token_account ON auth_token(account_id, kind);

-- A game: one world, one config, one schedule.
CREATE TABLE game (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,
    preset        TEXT NOT NULL,
    config_yaml   TEXT NOT NULL,
    config_hash   TEXT NOT NULL,
    seed          BIGINT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('setup', 'running', 'paused', 'finished')),
    update_number BIGINT NOT NULL DEFAULT 0,
    width         INT NOT NULL,
    height        INT NOT NULL,
    wrap_x        BOOLEAN NOT NULL,
    wrap_y        BOOLEAN NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT REFERENCES account(id)
);

CREATE TABLE country (
    game_id        BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    country_id     INT NOT NULL,
    name           TEXT NOT NULL,
    capital_x      INT NOT NULL,
    capital_y      INT NOT NULL,
    cash           DOUBLE PRECISION NOT NULL,
    btu            DOUBLE PRECISION NOT NULL,
    tech           DOUBLE PRECISION NOT NULL,
    research       DOUBLE PRECISION NOT NULL,
    education      DOUBLE PRECISION NOT NULL,
    happiness      DOUBLE PRECISION NOT NULL,
    handicap       JSONB NOT NULL,
    in_sanctuary   BOOLEAN NOT NULL,
    bankrupt       BOOLEAN NOT NULL,
    plague_left    INT NOT NULL DEFAULT 0,
    -- who plays it. Server-side only: NEVER copied into any view or API response.
    account_id     BIGINT REFERENCES account(id),
    controller     TEXT NOT NULL DEFAULT 'human' CHECK (controller IN ('human', 'agent', 'none')),
    agent_spec     JSONB,
    PRIMARY KEY (game_id, country_id),
    UNIQUE (game_id, name)
);

CREATE TABLE sector (
    game_id      BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    x            INT NOT NULL,
    y            INT NOT NULL,
    terrain      TEXT NOT NULL,
    elevation    INT NOT NULL,
    fertility    INT NOT NULL,
    minerals     INT NOT NULL,
    gold         INT NOT NULL,
    oil          INT NOT NULL,
    uranium      INT NOT NULL,
    owner        INT NOT NULL DEFAULT -1,
    designation  TEXT NOT NULL,
    efficiency   DOUBLE PRECISION NOT NULL,
    mobility     DOUBLE PRECISION NOT NULL,
    road_level   DOUBLE PRECISION NOT NULL DEFAULT 0,
    rail_level   DOUBLE PRECISION NOT NULL DEFAULT 0,
    radar_level  DOUBLE PRECISION NOT NULL DEFAULT 0,
    dist_x       INT,
    dist_y       INT,
    sanctuary    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (game_id, x, y)
);
CREATE INDEX sector_owner ON sector(game_id, owner);

-- Normalised stocks and thresholds: one row per (sector, commodity).
CREATE TABLE sector_stock (
    game_id    BIGINT NOT NULL,
    x          INT NOT NULL,
    y          INT NOT NULL,
    commodity  TEXT NOT NULL,
    qty        DOUBLE PRECISION NOT NULL DEFAULT 0,
    threshold  DOUBLE PRECISION,                 -- NULL = no threshold set
    PRIMARY KEY (game_id, x, y, commodity),
    FOREIGN KEY (game_id, x, y) REFERENCES sector(game_id, x, y) ON DELETE CASCADE
);

CREATE TABLE held_parcel (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL,
    x             INT NOT NULL,
    y             INT NOT NULL,
    commodity     TEXT NOT NULL,
    qty           DOUBLE PRECISION NOT NULL,
    owner         INT NOT NULL,
    origin_x      INT NOT NULL,
    origin_y      INT NOT NULL,
    dest_x        INT NOT NULL,
    dest_y        INT NOT NULL,
    issued_update BIGINT NOT NULL,
    FOREIGN KEY (game_id, x, y) REFERENCES sector(game_id, x, y) ON DELETE CASCADE
);
CREATE INDEX held_parcel_sector ON held_parcel(game_id, x, y);

CREATE TABLE move_order (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    owner         INT NOT NULL,
    from_x        INT NOT NULL,
    from_y        INT NOT NULL,
    to_x          INT NOT NULL,
    to_y          INT NOT NULL,
    commodity     TEXT NOT NULL,
    qty           DOUBLE PRECISION NOT NULL,
    issued_update BIGINT NOT NULL
);

-- Append-only logs. Nothing here is ever updated or deleted.
CREATE TABLE update_log (
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    update_number BIGINT NOT NULL,
    seed          BIGINT NOT NULL,
    state_hash    TEXT NOT NULL,
    events        JSONB NOT NULL,
    flows         JSONB NOT NULL,
    ran_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    millis        BIGINT NOT NULL,
    PRIMARY KEY (game_id, update_number)
);

CREATE TABLE command_log (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    country_id    INT NOT NULL,
    update_number BIGINT NOT NULL,
    source        TEXT NOT NULL,                  -- console | panel | agent | admin
    verb          TEXT NOT NULL,
    payload       JSONB NOT NULL,
    accepted      BOOLEAN NOT NULL,
    error         TEXT,
    btu_spent     DOUBLE PRECISION NOT NULL DEFAULT 0,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX command_log_game ON command_log(game_id, update_number);
