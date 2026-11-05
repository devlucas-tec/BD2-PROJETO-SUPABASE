-- ============================================================
-- Issue #17: DDL de pagamento, entrega e devolucao
-- Pré-requisito: 11_ddl_rls_pedido_item_pedido.sql
-- ============================================================

CREATE SEQUENCE seq_pagamento_id;
CREATE SEQUENCE seq_entrega_id;
CREATE SEQUENCE seq_devolucao_id;

-- 1:1 com pedido, garantido pelo UNIQUE em id_pedido.
CREATE TABLE pagamento (
    id_pagamento   BIGINT        NOT NULL DEFAULT nextval('seq_pagamento_id'),
    metodo         VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDENTE',
    valor_pago     NUMERIC(10,2) NOT NULL,
    data_pagamento TIMESTAMP     NOT NULL,
    id_pedido      BIGINT        NOT NULL,
    CONSTRAINT pk_pagamento PRIMARY KEY (id_pagamento),
    CONSTRAINT uq_pagamento_pedido UNIQUE (id_pedido),
    CONSTRAINT fk_pagamento_pedido FOREIGN KEY (id_pedido)
        REFERENCES pedido (id_pedido) ON DELETE CASCADE,
    CONSTRAINT chk_pagamento_metodo CHECK (metodo IN ('PIX', 'CARTAO', 'BOLETO')),
    CONSTRAINT chk_pagamento_status CHECK (status IN ('APROVADO', 'RECUSADO', 'PENDENTE')),
    CONSTRAINT chk_pagamento_valor CHECK (valor_pago >= 0)
);

-- 1:1 com pedido, garantido pelo UNIQUE em id_pedido.
CREATE TABLE entrega (
    id                    BIGINT       NOT NULL DEFAULT nextval('seq_entrega_id'),
    transportadora        VARCHAR(120) NOT NULL,
    codigo_rastreamento   VARCHAR(60)  NOT NULL UNIQUE,
    status_entrega        VARCHAR(20)  NOT NULL DEFAULT 'EMBALADO',
    data_envio            TIMESTAMP,
    data_entrega_prevista TIMESTAMP,
    id_pedido             BIGINT       NOT NULL,
    CONSTRAINT pk_entrega PRIMARY KEY (id),
    CONSTRAINT uq_entrega_pedido UNIQUE (id_pedido),
    CONSTRAINT fk_entrega_pedido FOREIGN KEY (id_pedido)
        REFERENCES pedido (id_pedido) ON DELETE CASCADE,
    CONSTRAINT chk_entrega_status CHECK (status_entrega IN
        ('EMBALADO', 'EM_TRANSITO', 'SAIU_PARA_ENTREGA', 'ENTREGUE'))
);

-- Um pedido pode ter mais de uma devolução, por isso id_pedido não é UNIQUE.
CREATE TABLE devolucao (
    id             BIGINT       NOT NULL DEFAULT nextval('seq_devolucao_id'),
    data_devolucao TIMESTAMP    NOT NULL,
    motivo         VARCHAR(500) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'SOLICITADA',
    id_pedido      BIGINT       NOT NULL,
    CONSTRAINT pk_devolucao PRIMARY KEY (id),
    CONSTRAINT fk_devolucao_pedido FOREIGN KEY (id_pedido)
        REFERENCES pedido (id_pedido) ON DELETE CASCADE,
    CONSTRAINT chk_devolucao_status CHECK (status IN ('SOLICITADA', 'APROVADA', 'RECUSADA'))
);

CREATE INDEX idx_devolucao_pedido ON devolucao (id_pedido);
CREATE INDEX idx_devolucao_status ON devolucao (status);

GRANT SELECT, INSERT, UPDATE, DELETE ON pagamento, entrega, devolucao TO app_ecommerce;
GRANT USAGE, SELECT ON SEQUENCE seq_pagamento_id, seq_entrega_id, seq_devolucao_id TO app_ecommerce;
