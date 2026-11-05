-- ============================================================
-- Issue #20: matriz de testes do RLS
--
-- COMO EXECUTAR
--   1. Aplique os scripts de src/main/sql e rode a carga (MainSeed) em um
--      banco sem outros dados: os valores esperados contam linhas.
--   2. Conecte como app_ecommerce. Com postgres ou service_role o teste não
--      prova nada, porque essas roles têm BYPASSRLS.
--   3. psql "<url de conexão>" -f docs/testes-rls.sql
--
-- O script não altera o banco: cada caso roda em uma subtransação desfeita,
-- e o BEGIN externo termina em ROLLBACK.
--
-- RESULTADOS
--   número  linhas alcançadas pelo comando
--   NEGADO  a policy recusou o comando com erro (42501)
--   FK      a policy permitiu, mas uma FK RESTRICT impediu a remoção (23503)
-- ============================================================
\set ON_ERROR_STOP on
\pset pager off

BEGIN;

-- Roda um comando sob uma identidade, sempre desfazendo o efeito.
CREATE FUNCTION pg_temp.tentar(p_id TEXT, p_role TEXT, p_sql TEXT) RETURNS TEXT
    LANGUAGE plpgsql
AS $$
DECLARE
    n INT;
BEGIN
    PERFORM set_config('app.usuario_id', coalesce(p_id, ''), true);
    PERFORM set_config('app.usuario_role', coalesce(p_role, ''), true);
    EXECUTE p_sql;
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE EXCEPTION '%', n USING ERRCODE = 'ZZ001';
EXCEPTION
    WHEN SQLSTATE 'ZZ001' THEN RETURN SQLERRM;
    WHEN insufficient_privilege THEN RETURN 'NEGADO';
    WHEN foreign_key_violation THEN RETURN 'FK';
    WHEN OTHERS THEN RETURN 'ERRO ' || SQLSTATE;
END;
$$;

-- Os ids da carga só são legíveis como ADMIN, e a tabela temporária não tem RLS.
SELECT set_config('app.usuario_role', 'ADMIN', true);

CREATE TEMP TABLE ids ON COMMIT DROP AS
SELECT (SELECT id FROM usuario WHERE email = 'admin@seed.exemplo.local') AS admin,
       (SELECT id FROM usuario WHERE email = 'ana@seed.exemplo.local')   AS ana,
       (SELECT id FROM usuario WHERE email = 'bruno@seed.exemplo.local') AS bruno,
       (SELECT id FROM usuario WHERE email = 'loja.x@seed.exemplo.local') AS loja_x,
       (SELECT id FROM usuario WHERE email = 'loja.y@seed.exemplo.local') AS loja_y,
       (SELECT id_pedido FROM pedido p JOIN usuario u ON u.id = p.id_cliente
         WHERE u.email = 'ana@seed.exemplo.local')   AS pedido_ana,
       (SELECT id_pedido FROM pedido p JOIN usuario u ON u.id = p.id_cliente
         WHERE u.email = 'bruno@seed.exemplo.local') AS pedido_bruno,
       (SELECT id_produto FROM produto WHERE nome = 'Panela') AS produto_y;

SELECT set_config('app.usuario_role', '', true);

\echo
\echo === 1. Matriz: tabela x papel x operacao (carga completa) ===
WITH esperado (tabela, coluna,
               an_s, an_u, an_d,  cl_s, cl_u, cl_d,  ve_s, ve_u, ve_d,  ad_s, ad_u, ad_d) AS (VALUES
    ('usuario',       'nome',           '0','0','0',  '1','1','0',  '1','1','0',  '6','6','FK'),
    ('admin',         'id',             '0','0','0',  '0','0','0',  '0','0','0',  '1','1','1'),
    ('cliente',       'telefone',       '0','0','0',  '1','1','0',  '0','0','0',  '3','3','3'),
    ('vendedor',      'razao_social',   '0','0','0',  '0','0','0',  '1','1','0',  '2','2','FK'),
    ('categoria',     'nome',           '3','0','0',  '3','0','0',  '3','0','0',  '3','3','FK'),
    ('produto',       'nome',           '6','0','0',  '6','0','0',  '6','3','FK', '6','6','FK'),
    ('cupom',         'codigo',         '1','0','0',  '1','0','0',  '1','0','0',  '1','1','1'),
    ('avaliacao',     'nota',           '2','0','0',  '2','1','1',  '2','0','0',  '2','2','2'),
    ('carrinho',      'id_cliente',     '0','0','0',  '1','1','1',  '0','0','0',  '3','0','0'),
    ('item_carrinho', 'quantidade',     '0','0','0',  '1','1','1',  '0','0','0',  '2','0','0'),
    ('pedido',        'status',         '0','0','0',  '1','1','0',  '2','0','0',  '3','3','3'),
    ('item_pedido',   'quantidade',     '0','0','0',  '2','2','2',  '2','0','0',  '4','4','4'),
    ('endereco',      'rua',            '0','0','0',  '1','1','1',  '0','0','0',  '3','3','3'),
    ('pagamento',     'status',         '0','0','0',  '1','0','0',  '0','0','0',  '3','3','3'),
    ('entrega',       'status_entrega', '0','0','0',  '1','0','0',  '2','2','0',  '3','3','3'),
    ('devolucao',     'status',         '0','0','0',  '1','0','0',  '2','2','0',  '2','2','2')
),
casos AS (
    SELECT e.tabela, p.papel, p.id, p.role, o.operacao, o.esperado,
           CASE o.operacao
               WHEN 'SELECT' THEN format('SELECT * FROM %I', e.tabela)
               WHEN 'UPDATE' THEN format('UPDATE %I SET %I = %I', e.tabela, e.coluna, e.coluna)
               ELSE format('DELETE FROM %I', e.tabela)
           END AS comando
      FROM esperado e
      CROSS JOIN ids
      CROSS JOIN LATERAL (VALUES
          ('1-anonimo',        NULL,            NULL),
          ('2-cliente Ana',    ids.ana::text,    'CLIENTE'),
          ('3-vendedor Loja X', ids.loja_x::text, 'VENDEDOR'),
          ('4-admin',          ids.admin::text,  'ADMIN')) AS p (papel, id, role)
      CROSS JOIN LATERAL (VALUES
          ('SELECT', CASE p.papel WHEN '1-anonimo' THEN e.an_s WHEN '2-cliente Ana' THEN e.cl_s
                                  WHEN '3-vendedor Loja X' THEN e.ve_s ELSE e.ad_s END),
          ('UPDATE', CASE p.papel WHEN '1-anonimo' THEN e.an_u WHEN '2-cliente Ana' THEN e.cl_u
                                  WHEN '3-vendedor Loja X' THEN e.ve_u ELSE e.ad_u END),
          ('DELETE', CASE p.papel WHEN '1-anonimo' THEN e.an_d WHEN '2-cliente Ana' THEN e.cl_d
                                  WHEN '3-vendedor Loja X' THEN e.ve_d ELSE e.ad_d END)
      ) AS o (operacao, esperado)
)
SELECT tabela, papel, operacao, esperado,
       pg_temp.tentar(id, role, comando) AS obtido,
       CASE WHEN pg_temp.tentar(id, role, comando) = esperado THEN 'ok' ELSE 'DIVERGENTE' END AS resultado
  FROM casos
 ORDER BY tabela, papel, operacao;

\echo
\echo === 2. Casos obrigatorios e tentativas de escrita indevida ===
WITH casos (caso, esperado, id, role, comando) AS (
    SELECT c.* FROM ids CROSS JOIN LATERAL (VALUES
        ('Cliente A le endereco do cliente B', '0', ana::text, 'CLIENTE',
            format('SELECT * FROM endereco WHERE id_cliente = %s', bruno)),
        ('Cliente A le pedido do cliente B', '0', ana::text, 'CLIENTE',
            format('SELECT * FROM pedido WHERE id_pedido = %s', pedido_bruno)),
        ('Cliente A le pagamento do cliente B', '0', ana::text, 'CLIENTE',
            format('SELECT * FROM pagamento WHERE id_pedido = %s', pedido_bruno)),
        ('Cliente A le carrinho do cliente B', '0', ana::text, 'CLIENTE',
            format('SELECT * FROM carrinho WHERE id_cliente = %s', bruno)),
        ('Cliente A le dados de identidade do cliente B', '0', ana::text, 'CLIENTE',
            format('SELECT * FROM usuario WHERE id = %s', bruno)),
        ('Cliente A cadastra endereco para o cliente B', 'NEGADO', ana::text, 'CLIENTE',
            format($q$INSERT INTO endereco (rua, numero, cep, cidade, estado, id_cliente)
                      VALUES ('r', '1', '58000-000', 'c', 'PB', %s)$q$, bruno)),
        ('Cliente A cria pedido em nome do cliente B', 'NEGADO', ana::text, 'CLIENTE',
            format($q$INSERT INTO pedido (valor_total, id_cliente) VALUES (1, %s)$q$, bruno)),
        ('Cliente aprova o proprio pagamento', '0', ana::text, 'CLIENTE',
            format($q$UPDATE pagamento SET status = 'APROVADO' WHERE id_pedido = %s$q$, pedido_ana)),
        ('Cliente registra pagamento ja aprovado', 'NEGADO', ana::text, 'CLIENTE',
            format($q$INSERT INTO pagamento (metodo, status, valor_pago, data_pagamento, id_pedido)
                      VALUES ('PIX', 'APROVADO', 1, now(), %s)$q$, pedido_ana)),
        ('Cliente aprova a propria devolucao', '0', ana::text, 'CLIENTE',
            format($q$UPDATE devolucao SET status = 'APROVADA' WHERE id_pedido = %s$q$, pedido_ana)),
        ('Cliente altera produto de um vendedor', '0', ana::text, 'CLIENTE',
            format($q$UPDATE produto SET preco = 1 WHERE id_produto = %s$q$, produto_y)),
        ('Vendedor X altera produto do vendedor Y', '0', loja_x::text, 'VENDEDOR',
            format($q$UPDATE produto SET preco = 1 WHERE id_produto = %s$q$, produto_y)),
        ('Vendedor X apaga produto do vendedor Y', '0', loja_x::text, 'VENDEDOR',
            format($q$DELETE FROM produto WHERE id_produto = %s$q$, produto_y)),
        ('Vendedor X cria produto em nome do vendedor Y', 'NEGADO', loja_x::text, 'VENDEDOR',
            format($q$INSERT INTO produto (nome, preco, id_vendedor, id_categoria)
                      SELECT 'p', 1, %s, id_categoria FROM produto WHERE id_produto = %s$q$, loja_y, produto_y)),
        ('Vendedor le pagamento de pedido com produto dele', '0', loja_x::text, 'VENDEDOR',
            format('SELECT * FROM pagamento WHERE id_pedido = %s', pedido_ana)),
        ('Vendedor le toda a tabela pagamento', '0', loja_x::text, 'VENDEDOR', 'SELECT * FROM pagamento'),
        ('Vendedor Y le pedido sem produto dele', '0', loja_y::text, 'VENDEDOR',
            format('SELECT * FROM pedido WHERE id_pedido = %s', pedido_bruno)),
        ('Admin le pagamento de qualquer pedido', '1', admin::text, 'ADMIN',
            format('SELECT * FROM pagamento WHERE id_pedido = %s', pedido_bruno)),
        ('Admin aprova pagamento', '1', admin::text, 'ADMIN',
            format($q$UPDATE pagamento SET status = 'APROVADO' WHERE id_pedido = %s$q$, pedido_bruno)),
        ('Anonimo le pedidos', '0', NULL, NULL, 'SELECT * FROM pedido'),
        ('Anonimo cria categoria', 'NEGADO', NULL, NULL,
            $q$INSERT INTO categoria (nome) VALUES ('x')$q$)
    ) AS c (caso, esperado, id, role, comando)
)
SELECT caso, esperado,
       pg_temp.tentar(id, role, comando) AS obtido,
       CASE WHEN pg_temp.tentar(id, role, comando) = esperado THEN 'ok' ELSE 'DIVERGENTE' END AS resultado
  FROM casos;

ROLLBACK;
