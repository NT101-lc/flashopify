-- Flashopify - V1 init schema (MySQL 8)
-- Location: backend/src/main/resources/db/migration/mysql/V1__init.sql

SET NAMES utf8mb4;

-- ===== AUTH & USER =====

CREATE TABLE users (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    email               VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    full_name           VARCHAR(100) NOT NULL,
    phone               VARCHAR(20)  NULL,
    role                ENUM('CUSTOMER','ADMIN')          NOT NULL DEFAULT 'CUSTOMER',
    status              ENUM('ACTIVE','LOCKED','DELETED') NOT NULL DEFAULT 'ACTIVE',
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        DATETIME     NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE addresses (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    receiver_name  VARCHAR(100) NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    address_line   VARCHAR(255) NOT NULL,
    ward           VARCHAR(100) NULL,
    district       VARCHAR(100) NULL,
    city           VARCHAR(100) NOT NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_addresses_user (user_id),
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== CATALOG =====

CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT       NULL,
    slug        VARCHAR(100) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_slug (slug),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE products (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    category_id    BIGINT        NOT NULL,
    source_id      INT           NULL COMMENT 'id ben DummyJSON, de seed idempotent',
    sku            VARCHAR(50)   NOT NULL,
    name           VARCHAR(255)  NOT NULL,
    slug           VARCHAR(255)  NOT NULL,
    description    TEXT          NULL,
    brand          VARCHAR(100)  NULL,
    price          DECIMAL(15,2) NOT NULL,
    rating         DECIMAL(3,2)  NULL,
    thumbnail_url  VARCHAR(500)  NULL,
    status         ENUM('ACTIVE','INACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_products_source (source_id),
    UNIQUE KEY uk_products_sku (sku),
    UNIQUE KEY uk_products_slug (slug),
    KEY idx_products_category_status (category_id, status),
    KEY idx_products_brand (brand),
    KEY idx_products_price (price),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_products_price CHECK (price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_images (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    url         VARCHAR(500) NOT NULL COMMENT 'URL tren MinIO/S3, phuc vu qua CDN',
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_product_images_product (product_id, sort_order),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory (
    product_id  BIGINT   NOT NULL,
    quantity    INT      NOT NULL COMMENT 'so luong ban duoc',
    reserved    INT      NOT NULL DEFAULT 0 COMMENT 'giu cho flash-sale va don PENDING',
    version     INT      NOT NULL DEFAULT 0 COMMENT 'optimistic lock',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_inventory_quantity CHECK (quantity >= 0),
    CONSTRAINT chk_inventory_reserved CHECK (reserved >= 0 AND reserved <= quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== CART =====

CREATE TABLE carts (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_carts_user (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cart_items (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    cart_id     BIGINT   NOT NULL,
    product_id  BIGINT   NOT NULL,
    quantity    INT      NOT NULL,
    added_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_items_cart_product (cart_id, product_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_cart_items_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== FLASH SALE =====

CREATE TABLE flash_sale_campaigns (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL,
    start_time  DATETIME     NOT NULL,
    end_time    DATETIME     NOT NULL,
    status      ENUM('SCHEDULED','RUNNING','ENDED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_campaigns_status_start (status, start_time),
    CONSTRAINT fk_campaigns_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_campaigns_time CHECK (end_time > start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flash_sale_items (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id     BIGINT        NOT NULL,
    product_id      BIGINT        NOT NULL,
    sale_price      DECIMAL(15,2) NOT NULL,
    total_stock     INT           NOT NULL,
    sold            INT           NOT NULL DEFAULT 0 COMMENT 'dong bo tu Redis sau chien dich',
    limit_per_user  INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fs_items_campaign_product (campaign_id, product_id),
    CONSTRAINT fk_fs_items_campaign FOREIGN KEY (campaign_id) REFERENCES flash_sale_campaigns (id),
    CONSTRAINT fk_fs_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_fs_items_stock CHECK (total_stock > 0 AND sold >= 0 AND sold <= total_stock),
    CONSTRAINT chk_fs_items_limit CHECK (limit_per_user > 0),
    CONSTRAINT chk_fs_items_price CHECK (sale_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Ton kho realtime o Redis: fs:stock:{id}, fs:bought:{id}';

-- ===== ORDER & PAYMENT =====

CREATE TABLE orders (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    order_no            VARCHAR(32)   NOT NULL,
    user_id             BIGINT        NOT NULL,
    type                ENUM('NORMAL','FLASH') NOT NULL,
    flash_sale_item_id  BIGINT        NULL,
    status              ENUM('PENDING','PAID','COMPLETED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'PENDING',
    total_amount        DECIMAL(15,2) NOT NULL,
    shipping_name       VARCHAR(100)  NOT NULL COMMENT 'snapshot dia chi luc dat',
    shipping_phone      VARCHAR(20)   NOT NULL,
    shipping_address    VARCHAR(500)  NOT NULL,
    idempotency_key     VARCHAR(64)   NOT NULL,
    expired_at          DATETIME      NOT NULL,
    paid_at             DATETIME      NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    UNIQUE KEY uk_orders_user_idem (user_id, idempotency_key),
    KEY idx_orders_user_created (user_id, created_at),
    KEY idx_orders_status_expired (status, expired_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_fs_item FOREIGN KEY (flash_sale_item_id) REFERENCES flash_sale_items (id),
    CONSTRAINT chk_orders_total CHECK (total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_items (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    order_id       BIGINT        NOT NULL,
    product_id     BIGINT        NOT NULL,
    product_name   VARCHAR(255)  NOT NULL COMMENT 'snapshot',
    thumbnail_url  VARCHAR(500)  NULL,
    quantity       INT           NOT NULL,
    unit_price     DECIMAL(15,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order (order_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payments (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    order_id         BIGINT        NOT NULL,
    provider_txn_id  VARCHAR(64)   NULL COMMENT 'chong xu ly callback trung',
    amount           DECIMAL(15,2) NOT NULL,
    status           ENUM('INITIATED','SUCCESS','FAILED') NOT NULL DEFAULT 'INITIATED',
    raw_callback     JSON          NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_provider_txn (provider_txn_id),
    KEY idx_payments_order (order_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== EVENTS =====

CREATE TABLE outbox_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)     NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL COMMENT 'OrderCreated, OrderPaid, OrderCancelled',
    payload         JSON         NOT NULL,
    status          ENUM('NEW','PUBLISHED','FAILED') NOT NULL DEFAULT 'NEW',
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE processed_events (
    event_id      CHAR(36)     NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    processed_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Kafka consumer idempotent';
