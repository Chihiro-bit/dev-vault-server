-- DevVault 服务端初始结构。服务端只保存密文，不保存任何明文账号密码或待办内容。

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL COMMENT '登录名（小写）',
    display_name  VARCHAR(64)  NOT NULL,
    email         VARCHAR(128) NULL,
    auth_hash     VARCHAR(255) NOT NULL COMMENT '客户端 authKey 的 PBKDF2 哈希',
    kdf_type      VARCHAR(16)  NOT NULL,
    kdf_n         INT          NOT NULL,
    kdf_r         INT          NOT NULL,
    kdf_p         INT          NOT NULL,
    kdf_salt      VARCHAR(128) NOT NULL,
    vault_key     TEXT         NOT NULL COMMENT '被主密码派生密钥包裹的数据密钥（密文）',
    revision      BIGINT       NOT NULL DEFAULT 0 COMMENT '该用户同步数据的最新版本号',
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE sessions (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    token_hash    CHAR(64)     NOT NULL COMMENT '令牌的 SHA-256，不保存令牌原文',
    device        VARCHAR(128) NOT NULL DEFAULT '',
    created_at    DATETIME(3)  NOT NULL,
    last_used_at  DATETIME(3)  NOT NULL,
    expires_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sessions_token (token_hash),
    KEY idx_sessions_user (user_id),
    KEY idx_sessions_expires (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE sync_items (
    user_id      BIGINT      NOT NULL,
    kind         VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision     BIGINT      NOT NULL,
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    modified_at  BIGINT      NOT NULL COMMENT '客户端修改时间（毫秒）',
    payload      MEDIUMTEXT  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '客户端加密后的记录',
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, kind, item_id),
    KEY idx_sync_revision (user_id, revision),
    CONSTRAINT fk_sync_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE server_meta (
    meta_key   VARCHAR(64) NOT NULL,
    meta_value TEXT        NOT NULL,
    PRIMARY KEY (meta_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
