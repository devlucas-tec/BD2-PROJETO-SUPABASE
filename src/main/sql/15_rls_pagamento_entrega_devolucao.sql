-- ============================================================
-- Issue #18: RLS de pagamento, entrega e devolucao
-- Pré-requisito: 12 e 14 aplicados
-- ============================================================

ALTER TABLE pagamento ENABLE ROW LEVEL SECURITY;
ALTER TABLE entrega   ENABLE ROW LEVEL SECURITY;
ALTER TABLE devolucao ENABLE ROW LEVEL SECURITY;

ALTER TABLE pagamento FORCE ROW LEVEL SECURITY;
ALTER TABLE entrega   FORCE ROW LEVEL SECURITY;
ALTER TABLE devolucao FORCE ROW LEVEL SECURITY;

-- ------------------------------------------------------------
-- pagamento: dado sensível do cliente. O vendedor não tem acesso,
-- mesmo em pedidos que contêm produtos dele.
-- ------------------------------------------------------------
CREATE POLICY pagamento_select ON pagamento FOR SELECT
    USING (EXISTS (SELECT 1 FROM pedido p
                    WHERE p.id_pedido = pagamento.id_pedido
                      AND p.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint)
        OR current_setting('app.usuario_role', true) = 'ADMIN');

-- O cliente registra o próprio pagamento, sempre como PENDENTE: quem aprova
-- é o ADMIN, então ele não pode se declarar pago.
CREATE POLICY pagamento_insert ON pagamento FOR INSERT
    WITH CHECK ((status = 'PENDENTE'
                 AND EXISTS (SELECT 1 FROM pedido p
                              WHERE p.id_pedido = pagamento.id_pedido
                                AND p.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint))
             OR current_setting('app.usuario_role', true) = 'ADMIN');

-- Alteração de status é exclusiva do ADMIN.
CREATE POLICY pagamento_update ON pagamento FOR UPDATE
    USING      (current_setting('app.usuario_role', true) = 'ADMIN')
    WITH CHECK (current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY pagamento_delete ON pagamento FOR DELETE
    USING (current_setting('app.usuario_role', true) = 'ADMIN');

-- ------------------------------------------------------------
-- entrega: o cliente acompanha; o vendedor do pedido despacha e atualiza
-- o rastreio.
-- ------------------------------------------------------------
CREATE POLICY entrega_select ON entrega FOR SELECT
    USING (EXISTS (SELECT 1 FROM pedido p
                    WHERE p.id_pedido = entrega.id_pedido
                      AND p.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint)
        OR pedido_contem_produto_do_vendedor(id_pedido)
        OR current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY entrega_insert ON entrega FOR INSERT
    WITH CHECK (pedido_contem_produto_do_vendedor(id_pedido)
             OR current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY entrega_update ON entrega FOR UPDATE
    USING      (pedido_contem_produto_do_vendedor(id_pedido)
             OR current_setting('app.usuario_role', true) = 'ADMIN')
    WITH CHECK (pedido_contem_produto_do_vendedor(id_pedido)
             OR current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY entrega_delete ON entrega FOR DELETE
    USING (current_setting('app.usuario_role', true) = 'ADMIN');

-- ------------------------------------------------------------
-- devolucao: o cliente solicita; o vendedor do pedido ou o ADMIN decidem.
-- ------------------------------------------------------------
CREATE POLICY devolucao_select ON devolucao FOR SELECT
    USING (EXISTS (SELECT 1 FROM pedido p
                    WHERE p.id_pedido = devolucao.id_pedido
                      AND p.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint)
        OR pedido_contem_produto_do_vendedor(id_pedido)
        OR current_setting('app.usuario_role', true) = 'ADMIN');

-- O cliente só solicita: não pode criar a devolução já aprovada.
CREATE POLICY devolucao_insert ON devolucao FOR INSERT
    WITH CHECK ((status = 'SOLICITADA'
                 AND EXISTS (SELECT 1 FROM pedido p
                              WHERE p.id_pedido = devolucao.id_pedido
                                AND p.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint))
             OR current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY devolucao_update ON devolucao FOR UPDATE
    USING      (pedido_contem_produto_do_vendedor(id_pedido)
             OR current_setting('app.usuario_role', true) = 'ADMIN')
    WITH CHECK (pedido_contem_produto_do_vendedor(id_pedido)
             OR current_setting('app.usuario_role', true) = 'ADMIN');

CREATE POLICY devolucao_delete ON devolucao FOR DELETE
    USING (current_setting('app.usuario_role', true) = 'ADMIN');
