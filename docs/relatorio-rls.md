# Relatório de testes de RLS

Resultado da matriz de `docs/testes-rls.sql` sobre a carga de `SeedService`: **213 de 213 casos conforme o esperado, sem vazamento entre usuários**.

## Ambiente

| Item | Valor |
| :--- | :--- |
| Banco | PostgreSQL 16 local, com os scripts `01` a `15` de `src/main/sql` |
| Conexão | `app_ecommerce` (`rolbypassrls = false`, `rolsuper = false`) |
| Dados | Carga de `MainSeed`: 1 admin, 3 clientes, 2 vendedores, 6 produtos, 3 pedidos cruzados |

### Ainda falta comprovar

- **Execução no Supabase:** estes resultados vêm do PostgreSQL local. Repita o procedimento abaixo no Supabase depois de aplicar os scripts `12` a `15`.
- **Testes por HTTP com JWT real:** o projeto não tem camada HTTP nem login desde a remoção do Spring. Aqui, a identidade é definida pelo `JwtAuthenticationFilter` e pelas variáveis de sessão do PostgreSQL. O teste com Postman ou Insomnia depende de criar essa camada.

## Como reproduzir

1. Aplique os scripts de `src/main/sql` em ordem numérica, em um banco sem outros dados.
2. Rode a carga:

   ```bash
   java -cp target/crud-jpa-template-0.0.1-SNAPSHOT.jar br.edu.ifpb.es.daw.main.MainSeed
   ```

3. Conectado como `app_ecommerce`, execute a matriz. O script não altera o banco:

   ```bash
   psql "<url de conexão>" -f docs/testes-rls.sql
   ```

## Matriz por tabela e papel

Cada célula mostra as linhas alcançadas por **`SELECT` / `UPDATE` / `DELETE`** sem filtro nos 192 casos desta matriz. `FK` significa que a policy permitiu, mas uma FK `RESTRICT` impediu a remoção.

| Tabela | Anônimo | Cliente Ana | Vendedor Loja X | Admin |
| :--- | :--- | :--- | :--- | :--- |
| `usuario` | 0/0/0 | 1/1/0 | 1/1/0 | 6/6/FK |
| `admin` | 0/0/0 | 0/0/0 | 0/0/0 | 1/1/1 |
| `cliente` | 0/0/0 | 1/1/0 | 0/0/0 | 3/3/3 |
| `vendedor` | 0/0/0 | 0/0/0 | 1/1/0 | 2/2/FK |
| `categoria` | 3/0/0 | 3/0/0 | 3/0/0 | 3/3/FK |
| `produto` | 6/0/0 | 6/0/0 | 6/3/FK | 6/6/FK |
| `cupom` | 1/0/0 | 1/0/0 | 1/0/0 | 1/1/1 |
| `avaliacao` | 2/0/0 | 2/1/1 | 2/0/0 | 2/2/2 |
| `carrinho` | 0/0/0 | 1/1/1 | 0/0/0 | 3/0/0 |
| `item_carrinho` | 0/0/0 | 1/1/1 | 0/0/0 | 2/0/0 |
| `pedido` | 0/0/0 | 1/1/0 | 2/0/0 | 3/3/3 |
| `item_pedido` | 0/0/0 | 2/2/2 | 2/0/0 | 4/4/4 |
| `endereco` | 0/0/0 | 1/1/1 | 0/0/0 | 3/3/3 |
| `pagamento` | 0/0/0 | 1/0/0 | 0/0/0 | 3/3/3 |
| `entrega` | 0/0/0 | 1/0/0 | 2/2/0 | 3/3/3 |
| `devolucao` | 0/0/0 | 1/0/0 | 2/2/0 | 2/2/2 |

## Casos obrigatórios

`0` significa que nenhuma linha foi alcançada; `NEGADO` significa que a policy recusou o comando com erro.

| Caso | Esperado | Obtido |
| :--- | :--- | :--- |
| Cliente A lê endereço do cliente B | 0 | 0 |
| Cliente A lê pedido do cliente B | 0 | 0 |
| Cliente A lê pagamento do cliente B | 0 | 0 |
| Cliente A lê carrinho do cliente B | 0 | 0 |
| Cliente A lê dados de identidade do cliente B | 0 | 0 |
| Cliente A cadastra endereço para o cliente B | NEGADO | NEGADO |
| Cliente A cria pedido em nome do cliente B | NEGADO | NEGADO |
| Cliente aprova o próprio pagamento | 0 | 0 |
| Cliente registra pagamento já aprovado | NEGADO | NEGADO |
| Cliente aprova a própria devolução | 0 | 0 |
| Cliente altera produto de um vendedor | 0 | 0 |
| Vendedor X altera produto do vendedor Y | 0 | 0 |
| Vendedor X apaga produto do vendedor Y | 0 | 0 |
| Vendedor X cria produto em nome do vendedor Y | NEGADO | NEGADO |
| Vendedor lê pagamento de pedido com produto dele | 0 | 0 |
| Vendedor lê toda a tabela `pagamento` | 0 | 0 |
| Vendedor Y lê pedido sem produto dele | 0 | 0 |
| Admin lê pagamento de qualquer pedido | 1 | 1 |
| Admin aprova pagamento | 1 | 1 |
| Anônimo lê pedidos | 0 | 0 |
| Anônimo cria categoria | NEGADO | NEGADO |

## Problemas encontrados pelos testes

Corrigidos em `src/main/sql/12_fix_rls_pedido_carrinho.sql`:

1. **Recursão nas policies:** `pedido_select` e `item_pedido_select` consultavam uma à outra, e qualquer `SELECT` em `pedido` falhava com "infinite recursion detected". O bloco de validação do script `11` não acusou porque é pulado no SQL Editor, onde `postgres` tem `BYPASSRLS`.
2. **`carrinho` e `item_carrinho` sem `NULLIF`:** sessões anônimas que herdavam `''` do pooler recebiam erro `22P02` em vez de terem o acesso negado.
3. **Cliente sem permissão para baixar estoque:** `produto_update` só alcança o vendedor dono ou o ADMIN, então o checkout não conseguiria atualizar o estoque. A função `baixar_estoque_pedido` faz isso apenas para os itens de um pedido do próprio cliente, e uma única vez.

## Pontos de atenção

A matriz mostra que o **cliente pode alterar e apagar itens do próprio pedido, e alterar o próprio pedido**, inclusive `valor_total`, mesmo depois do checkout. Isso segue as policies definidas na issue #14, mas permite mudar preço e quantidade de uma compra já fechada, e deve ser restringido.
