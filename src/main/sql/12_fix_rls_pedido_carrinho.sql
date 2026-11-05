-- ============================================================
-- Issue #15: correções de RLS exigidas pelo checkout
-- Pré-requisito: 01 a 11 aplicados
--
-- PROBLEMA 1: recursão entre as policies de pedido e item_pedido
-- pedido_select consulta item_pedido, e item_pedido_select consulta pedido.
-- Qualquer SELECT em pedido como app_ecommerce levanta 42P17 ("infinite
-- recursion detected in policy"). O bloco de validação do script 11 não
-- acusou porque ele é pulado no SQL Editor (postgres tem BYPASSRLS).
--
-- PROBLEMA 2: carrinho e item_carrinho sem NULLIF
-- Mesma falha corrigida na identidade pelo script 04: sessão anônima que
-- herda '' do pooler levanta 22P02 em vez de ter o acesso negado.
--
-- PROBLEMA 3: cliente não pode baixar estoque
-- produto_update só alcança o vendedor dono ou ADMIN. No checkout, feito
-- pelo cliente, o UPDATE afetaria 0 linhas e o estoque ficaria intacto.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Quebra da recursão: a checagem do vendedor roda como dono da função,
--    sem reavaliar as policies de item_pedido.
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION pedido_contem_produto_do_vendedor(p_id_pedido BIGINT)
    RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM item_pedido ip
          JOIN produto pr ON pr.id_produto = ip.id_produto
         WHERE ip.id_pedido = p_id_pedido
           AND pr.id_vendedor = nullif(current_setting('app.usuario_id', true), '')::bigint
    );
$$;

REVOKE ALL ON FUNCTION pedido_contem_produto_do_vendedor(BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pedido_contem_produto_do_vendedor(BIGINT) TO app_ecommerce;

ALTER POLICY pedido_select ON pedido
    USING (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
        OR pedido_contem_produto_do_vendedor(id_pedido)
        OR current_setting('app.usuario_role', true) = 'ADMIN');

-- ------------------------------------------------------------
-- 2. NULLIF nas policies de carrinho e item_carrinho
-- ------------------------------------------------------------
ALTER POLICY carrinho_select ON carrinho
    USING (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint
        OR current_setting('app.usuario_role', true) = 'ADMIN');

ALTER POLICY carrinho_insert ON carrinho
    WITH CHECK (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint);

ALTER POLICY carrinho_update ON carrinho
    USING      (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint)
    WITH CHECK (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint);

ALTER POLICY carrinho_delete ON carrinho
    USING (id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint);

ALTER POLICY item_carrinho_select ON item_carrinho
    USING (EXISTS (SELECT 1 FROM carrinho c
                    WHERE c.id = item_carrinho.carrinho_id
                      AND c.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint)
        OR current_setting('app.usuario_role', true) = 'ADMIN');

ALTER POLICY item_carrinho_insert ON item_carrinho
    WITH CHECK (EXISTS (SELECT 1 FROM carrinho c
                         WHERE c.id = item_carrinho.carrinho_id
                           AND c.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint));

ALTER POLICY item_carrinho_update ON item_carrinho
    USING      (EXISTS (SELECT 1 FROM carrinho c
                         WHERE c.id = item_carrinho.carrinho_id
                           AND c.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint))
    WITH CHECK (EXISTS (SELECT 1 FROM carrinho c
                         WHERE c.id = item_carrinho.carrinho_id
                           AND c.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint));

ALTER POLICY item_carrinho_delete ON item_carrinho
    USING (EXISTS (SELECT 1 FROM carrinho c
                    WHERE c.id = item_carrinho.carrinho_id
                      AND c.id_cliente = nullif(current_setting('app.usuario_id', true), '')::bigint));

-- ------------------------------------------------------------
-- 3. Baixa de estoque amarrada a um pedido do próprio cliente
--
--    A função não recebe produto nem quantidade: lê os itens do pedido.
--    Assim o cliente só consegue baixar o que comprou, e estoque_baixado
--    impede repetir a baixa. A coluna fica fora do GRANT de UPDATE, então
--    só esta função consegue alterá-la.
-- ------------------------------------------------------------
ALTER TABLE pedido ADD COLUMN estoque_baixado BOOLEAN NOT NULL DEFAULT false;

REVOKE UPDATE ON pedido FROM app_ecommerce;
GRANT UPDATE (data_pedido, valor_total, status, id_cliente, id_cupom) ON pedido TO app_ecommerce;

CREATE OR REPLACE FUNCTION baixar_estoque_pedido(p_id_pedido BIGINT)
    RETURNS VOID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_usuario BIGINT := nullif(current_setting('app.usuario_id', true), '')::bigint;
    v_role    TEXT   := current_setting('app.usuario_role', true);
    v_cliente BIGINT;
    v_baixado BOOLEAN;
    v_item    RECORD;
BEGIN
    SELECT id_cliente, estoque_baixado INTO v_cliente, v_baixado
      FROM pedido
     WHERE id_pedido = p_id_pedido
       FOR UPDATE;

    IF NOT FOUND OR (v_cliente IS DISTINCT FROM v_usuario AND v_role IS DISTINCT FROM 'ADMIN') THEN
        RAISE EXCEPTION 'Pedido % nao encontrado', p_id_pedido USING ERRCODE = '42501';
    END IF;

    IF v_baixado THEN
        RAISE EXCEPTION 'Estoque do pedido % ja foi baixado', p_id_pedido USING ERRCODE = 'P0001';
    END IF;

    -- ORDER BY: compras concorrentes travam os produtos sempre na mesma
    -- ordem, evitando deadlock.
    FOR v_item IN
        SELECT id_produto, quantidade FROM item_pedido
         WHERE id_pedido = p_id_pedido
         ORDER BY id_produto
    LOOP
        UPDATE produto
           SET estoque = estoque - v_item.quantidade, data_atualizacao = now()
         WHERE id_produto = v_item.id_produto
           AND estoque >= v_item.quantidade;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Estoque insuficiente para o produto %', v_item.id_produto
                USING ERRCODE = 'P0001';
        END IF;
    END LOOP;

    UPDATE pedido SET estoque_baixado = true WHERE id_pedido = p_id_pedido;
END;
$$;

REVOKE ALL ON FUNCTION baixar_estoque_pedido(BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION baixar_estoque_pedido(BIGINT) TO app_ecommerce;
