-- ============================================================
-- Issue #16: DDL + RLS de endereco
-- Pré-requisito: 01 a 04 aplicados
-- ============================================================

CREATE SEQUENCE seq_endereco_id;

CREATE TABLE endereco (
    id          BIGINT       NOT NULL DEFAULT nextval('seq_endereco_id'),
    rua         VARCHAR(255) NOT NULL,
    numero      VARCHAR(20)  NOT NULL,
    complemento VARCHAR(255),
    cep         VARCHAR(9)   NOT NULL,
    cidade      VARCHAR(120) NOT NULL,
    estado      CHAR(2)      NOT NULL,
    id_cliente  BIGINT       NOT NULL,
    CONSTRAINT pk_endereco PRIMARY KEY (id),
    CONSTRAINT fk_endereco_cliente FOREIGN KEY (id_cliente)
        REFERENCES cliente (id) ON DELETE CASCADE
);

CREATE INDEX idx_endereco_cliente ON endereco (id_cliente);

ALTER TABLE endereco ENABLE ROW LEVEL SECURITY;
ALTER TABLE endereco FORCE ROW LEVEL SECURITY;

-- Endereço é dado pessoal: só o cliente dono ou ADMIN alcançam a linha.
CREATE POLICY endereco_select ON endereco FOR SELECT
    USING (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
        OR current_setting('app.usuario_role', true) = 'ADMIN');

-- WITH CHECK impede cadastrar endereço para outro cliente.
CREATE POLICY endereco_insert ON endereco FOR INSERT
    WITH CHECK (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
        OR current_setting('app.usuario_role', true) = 'ADMIN');

-- WITH CHECK impede transferir o endereço para outro cliente.
CREATE POLICY endereco_update ON endereco FOR UPDATE
    USING      (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
             OR current_setting('app.usuario_role', true) = 'ADMIN')
    WITH CHECK (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
             OR current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY endereco_delete ON endereco FOR DELETE
    USING (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
        OR current_setting('app.usuario_role', true) = 'ADMIN');

GRANT SELECT, INSERT, UPDATE, DELETE ON endereco TO app_ecommerce;
GRANT USAGE, SELECT ON SEQUENCE seq_endereco_id TO app_ecommerce;
