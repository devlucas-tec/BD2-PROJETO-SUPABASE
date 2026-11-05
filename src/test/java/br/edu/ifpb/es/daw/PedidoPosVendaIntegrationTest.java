package br.edu.ifpb.es.daw;

import br.edu.ifpb.es.daw.dao.*;
import br.edu.ifpb.es.daw.dao.impl.*;
import br.edu.ifpb.es.daw.entities.*;
import br.edu.ifpb.es.daw.filter.JwtAuthenticationFilter;
import org.junit.jupiter.api.*;

import java.io.File;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de integração das issues #15, #16, #17, #18 e #19, conectado como
 * app_ecommerce (sem BYPASSRLS): checkout, endereço e pós-venda sob as
 * identidades de cliente, vendedor, ADMIN e anônimo.
 *
 * Pré-requisitos:
 * - Arquivo .env na raiz com DB_URL, DB_USER, DB_PASSWORD
 * - Scripts 01..15 de src/main/sql aplicados
 *
 * Sem .env, ou sem os scripts aplicados, a classe é pulada. Cria dados com
 * sufixo único e remove tudo que criou.
 */
class PedidoPosVendaIntegrationTest {

    private static final Long ID_ADMIN = 0L;
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final CategoriaDAO CATEGORIA_DAO = new CategoriaDAOImpl();
    private static final ClienteDAO CLIENTE_DAO = new ClienteDAOImpl();
    private static final VendedorDAO VENDEDOR_DAO = new VendedorDAOImpl();
    private static final ProdutoDAO PRODUTO_DAO = new ProdutoDAOImpl();
    private static final CupomDAO CUPOM_DAO = new CupomDAOImpl();
    private static final CarrinhoDAO CARRINHO_DAO = new CarrinhoDAOImpl();
    private static final ItemCarrinhoDAO ITEM_CARRINHO_DAO = new ItemCarrinhoDAOImpl();
    private static final PedidoDAO PEDIDO_DAO = new PedidoDAOImpl();
    private static final ItemPedidoDAO ITEM_PEDIDO_DAO = new ItemPedidoDAOImpl();
    private static final EnderecoDAO ENDERECO_DAO = new EnderecoDAOImpl();
    private static final PagamentoDAO PAGAMENTO_DAO = new PagamentoDAOImpl();
    private static final EntregaDAO ENTREGA_DAO = new EntregaDAOImpl();
    private static final DevolucaoDAO DEVOLUCAO_DAO = new DevolucaoDAOImpl();

    private static String sufixo;
    private static Categoria categoria;
    private static Cliente clienteA;
    private static Cliente clienteB;
    private static Vendedor vendedorX;
    private static Vendedor vendedorY;
    private static Cupom cupom;

    @BeforeAll
    static void montarCenario() {
        Assumptions.assumeTrue(new File(".env").exists(),
                "Sem .env na raiz: teste de integração pulado (ver .env.example)");
        Assumptions.assumeTrue(scriptsAplicados(),
                "Tabelas ausentes: aplique os scripts 12..15 de src/main/sql");

        sufixo = String.valueOf(System.currentTimeMillis());

        comoAdmin(() -> {
            categoria = new Categoria();
            categoria.setNome("Categoria Pedido " + sufixo);
            CATEGORIA_DAO.save(categoria);

            clienteA = novoCliente("a");
            clienteB = novoCliente("b");
            vendedorX = novoVendedor("1");
            vendedorY = novoVendedor("2");

            cupom = new Cupom();
            cupom.setCodigo("DESC" + sufixo);
            cupom.setValorDesconto(new BigDecimal("15.00"));
            cupom.setDataExpiracao(LocalDate.now().plusDays(30));
            cupom.setStatus(StatusCupom.ATIVO);
            CUPOM_DAO.save(cupom);
        });
    }

    @AfterAll
    static void limparCenario() {
        if (clienteA == null) {
            return;
        }
        // carrinho_delete só alcança o próprio dono, por isso cada cliente apaga o seu.
        for (Cliente cliente : List.of(clienteA, clienteB)) {
            JwtAuthenticationFilter.executeAuthenticated(cliente.getId(), "CLIENTE", () -> {
                Carrinho carrinho = CARRINHO_DAO.findByCliente(cliente.getId());
                if (carrinho != null) {
                    CARRINHO_DAO.delete(carrinho);
                }
            });
        }
        comoAdmin(() -> {
            // FK de item_pedido -> produto é RESTRICT: pedidos saem antes dos vendedores.
            for (Cliente cliente : List.of(clienteA, clienteB)) {
                PEDIDO_DAO.findByCliente(cliente.getId()).forEach(PEDIDO_DAO::delete);
                CLIENTE_DAO.delete(cliente);
            }
            VENDEDOR_DAO.delete(vendedorX);
            VENDEDOR_DAO.delete(vendedorY);
            CATEGORIA_DAO.delete(categoria);
            CUPOM_DAO.delete(cupom);
        });
    }

    // ---------- #15: checkout ----------

    @Test
    @DisplayName("#15 checkout grava pedido e itens, baixa estoque, limpa carrinho e aplica cupom")
    void checkoutCompleto() {
        Produto caneta = novoProduto(vendedorX, "100.00", 10);
        Produto caderno = novoProduto(vendedorY, "50.00", 5);

        Pedido pedido = comoCliente(clienteA, () -> {
            encherCarrinho(clienteA, caneta, 2);
            encherCarrinho(clienteA, caderno, 1);
            return PEDIDO_DAO.checkout(cupom.getCodigo());
        });

        assertNotNull(pedido.getId());
        assertEquals(new BigDecimal("235.00"), pedido.getValorTotal().setScale(2), "2x100 + 50 - 15");
        assertEquals(cupom.getId(), pedido.getIdCupom());
        assertEquals(8, PRODUTO_DAO.findById(caneta.getId()).getEstoque());
        assertEquals(4, PRODUTO_DAO.findById(caderno.getId()).getEstoque());

        comoCliente(clienteA, () -> {
            assertEquals(2, ITEM_PEDIDO_DAO.findByPedido(pedido.getId()).size());
            Carrinho carrinho = CARRINHO_DAO.findByCliente(clienteA.getId());
            assertTrue(ITEM_CARRINHO_DAO.listarPorCarrinho(carrinho.getId()).isEmpty(), "carrinho não foi limpo");
            return null;
        });
    }

    @Test
    @DisplayName("#15 critério de aceite: falha na baixa de estoque não deixa pedido órfão")
    void checkoutSemEstoqueFazRollback() {
        Produto comEstoque = novoProduto(vendedorX, "10.00", 10);
        Produto semEstoque = novoProduto(vendedorX, "10.00", 1);

        comoCliente(clienteB, () -> {
            encherCarrinho(clienteB, comEstoque, 2);
            encherCarrinho(clienteB, semEstoque, 5);
            int pedidosAntes = PEDIDO_DAO.findByCliente(clienteB.getId()).size();

            CheckoutException erro = assertThrows(CheckoutException.class, () -> PEDIDO_DAO.checkout(null));
            assertTrue(erro.getMessage().contains("Estoque insuficiente"), erro.getMessage());

            assertEquals(pedidosAntes, PEDIDO_DAO.findByCliente(clienteB.getId()).size(), "pedido órfão");
            Carrinho carrinho = CARRINHO_DAO.findByCliente(clienteB.getId());
            assertEquals(2, ITEM_CARRINHO_DAO.listarPorCarrinho(carrinho.getId()).size(), "carrinho foi alterado");
            ITEM_CARRINHO_DAO.limparCarrinho(carrinho.getId());
            return null;
        });

        assertEquals(10, PRODUTO_DAO.findById(comEstoque.getId()).getEstoque(), "estoque não voltou");
        assertEquals(1, PRODUTO_DAO.findById(semEstoque.getId()).getEstoque());
    }

    @Test
    @DisplayName("#15 total usa o preço atual do produto, não o valor gravado no carrinho")
    void checkoutIgnoraPrecoDoCarrinho() {
        Produto produto = novoProduto(vendedorX, "80.00", 3);

        Pedido pedido = comoCliente(clienteA, () -> {
            Carrinho carrinho = CARRINHO_DAO.criarParaCliente(clienteA.getId());
            ITEM_CARRINHO_DAO.adicionarItem(new ItemCarrinho(carrinho.getId(), produto.getId(), 1, new BigDecimal("0.01")));
            return PEDIDO_DAO.checkout(null);
        });

        assertEquals(new BigDecimal("80.00"), pedido.getValorTotal().setScale(2));
    }

    @Test
    @DisplayName("#15 carrinho vazio e cupom inválido são recusados; anônimo não faz checkout")
    void checkoutInvalido() {
        comoCliente(clienteB, () -> {
            assertThrows(CheckoutException.class, () -> PEDIDO_DAO.checkout(null));
            return null;
        });

        Produto produto = novoProduto(vendedorX, "5.00", 3);
        comoCliente(clienteB, () -> {
            encherCarrinho(clienteB, produto, 1);
            assertThrows(CheckoutException.class, () -> PEDIDO_DAO.checkout("NAO-EXISTE"));
            ITEM_CARRINHO_DAO.limparCarrinho(CARRINHO_DAO.findByCliente(clienteB.getId()).getId());
            return null;
        });

        JwtAuthenticationFilter.executeAnonymous(() ->
                assertThrows(IllegalStateException.class, () -> PEDIDO_DAO.checkout(null)));
    }

    @Test
    @DisplayName("#15 baixa de estoque não pode ser repetida nem feita em pedido alheio")
    void baixaDeEstoqueProtegida() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "10.00", 10));

        comoCliente(clienteA, () -> {
            assertEquals("P0001", estadoAoExecutar("SELECT baixar_estoque_pedido(" + pedido.getId() + ")"));
            return null;
        });
        comoCliente(clienteB, () -> {
            assertEquals(INSUFFICIENT_PRIVILEGE, estadoAoExecutar("SELECT baixar_estoque_pedido(" + pedido.getId() + ")"));
            assertEquals(INSUFFICIENT_PRIVILEGE,
                    estadoAoExecutar("UPDATE pedido SET estoque_baixado = false WHERE id_pedido = " + pedido.getId()));
            return null;
        });
    }

    @Test
    @DisplayName("#15 vendedor vê pedido com produto dele; outro vendedor e outro cliente não")
    void visibilidadeDoPedido() {
        Produto produto = novoProduto(vendedorX, "20.00", 5);
        Pedido pedido = pedidoDe(clienteA, produto);

        JwtAuthenticationFilter.executeAuthenticated(vendedorX.getId(), "VENDEDOR", () -> {
            assertNotNull(PEDIDO_DAO.findById(pedido.getId()));
            assertTrue(PEDIDO_DAO.findByVendedor(vendedorX.getId()).contains(pedido));
            assertEquals(1, ITEM_PEDIDO_DAO.findByPedido(pedido.getId()).size());
            assertFalse(PEDIDO_DAO.atualizarStatus(pedido.getId(), StatusPedido.CANCELADO), "vendedor não edita pedido");
        });
        JwtAuthenticationFilter.executeAuthenticated(vendedorY.getId(), "VENDEDOR", () -> {
            assertNull(PEDIDO_DAO.findById(pedido.getId()));
            assertTrue(ITEM_PEDIDO_DAO.findByPedido(pedido.getId()).isEmpty());
        });
        comoCliente(clienteB, () -> {
            assertNull(PEDIDO_DAO.findById(pedido.getId()));
            return null;
        });
        JwtAuthenticationFilter.executeAnonymous(() -> assertNull(PEDIDO_DAO.findById(pedido.getId())));
        comoCliente(clienteA, () -> {
            assertTrue(PEDIDO_DAO.atualizarStatus(pedido.getId(), StatusPedido.CANCELADO));
            return null;
        });
    }

    // ---------- #16: endereco ----------

    @Test
    @DisplayName("#16 critério de aceite: cliente A não acessa endereço do cliente B")
    void isolamentoDeEndereco() {
        Endereco endereco = novoEndereco(clienteB);
        comoCliente(clienteB, () -> {
            ENDERECO_DAO.save(endereco);
            assertNotNull(endereco.getId());
            return null;
        });

        comoCliente(clienteA, () -> {
            assertNull(ENDERECO_DAO.findById(endereco.getId()));
            assertTrue(ENDERECO_DAO.findByCliente(clienteB.getId()).isEmpty());

            RuntimeException erro = assertThrows(RuntimeException.class, () -> ENDERECO_DAO.save(novoEndereco(clienteB)));
            assertEquals(INSUFFICIENT_PRIVILEGE, estado(erro));
            return null;
        });

        comoCliente(clienteB, () -> {
            endereco.setCidade("Campina Grande");
            ENDERECO_DAO.update(endereco);
            assertEquals("Campina Grande", ENDERECO_DAO.findById(endereco.getId()).getCidade());

            endereco.setIdCliente(clienteA.getId());
            RuntimeException erro = assertThrows(RuntimeException.class, () -> ENDERECO_DAO.update(endereco));
            assertEquals(INSUFFICIENT_PRIVILEGE, estado(erro), "transferiu endereço para outro cliente");
            return null;
        });

        comoAdmin(() -> assertEquals(1, ENDERECO_DAO.findByCliente(clienteB.getId()).size()));
    }

    // ---------- #17: restrições ----------

    @Test
    @DisplayName("#17 critério de aceite: 1:1 com pedido e enums recusando valores inválidos")
    void restricoesDoPosVenda() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "30.00", 5));
        Pedido outro = pedidoDe(clienteA, novoProduto(vendedorX, "30.00", 5));
        String codigo = "RAST" + pedido.getId() + sufixo;

        comoAdmin(() -> {
            PAGAMENTO_DAO.save(novoPagamento(pedido, StatusPagamento.PENDENTE));
            assertEquals("23505", estado(assertThrows(RuntimeException.class,
                    () -> PAGAMENTO_DAO.save(novoPagamento(pedido, StatusPagamento.PENDENTE)))));

            ENTREGA_DAO.save(novaEntrega(pedido, codigo));
            assertEquals("23505", estado(assertThrows(RuntimeException.class,
                    () -> ENTREGA_DAO.save(novaEntrega(pedido, codigo + "x")))), "segunda entrega no pedido");
            assertEquals("23505", estado(assertThrows(RuntimeException.class,
                    () -> ENTREGA_DAO.save(novaEntrega(outro, codigo)))), "código de rastreio repetido");

            String idDoPedido = pedido.getId().toString();
            assertEquals("23514", estadoAoExecutar("UPDATE pagamento SET metodo = 'CHEQUE' WHERE id_pedido = " + idDoPedido));
            assertEquals("23514", estadoAoExecutar("UPDATE pagamento SET status = 'X' WHERE id_pedido = " + idDoPedido));
            assertEquals("23514", estadoAoExecutar("UPDATE entrega SET status_entrega = 'X' WHERE id_pedido = " + idDoPedido));
            assertEquals("23514", estadoAoExecutar("INSERT INTO devolucao (data_devolucao, motivo, status, id_pedido)"
                    + " VALUES (now(), 'm', 'X', " + idDoPedido + ")"));
        });
    }

    // ---------- #18 e #19: policies e DAOs ----------

    @Test
    @DisplayName("#18 critério de aceite: vendedor não vê pagamento, nem de pedido com produto dele")
    void vendedorNaoVePagamento() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "40.00", 5));
        Pagamento pagamento = novoPagamento(pedido, StatusPagamento.PENDENTE);

        comoCliente(clienteA, () -> {
            PAGAMENTO_DAO.save(pagamento);
            assertNotNull(pagamento.getId());
            assertEquals(pagamento.getId(), PAGAMENTO_DAO.findByPedido(pedido.getId()).getId());
            assertFalse(PAGAMENTO_DAO.atualizarStatus(pagamento.getId(), StatusPagamento.APROVADO),
                    "cliente aprovou o próprio pagamento");
            return null;
        });

        JwtAuthenticationFilter.executeAuthenticated(vendedorX.getId(), "VENDEDOR", () -> {
            assertTrue(PAGAMENTO_DAO.findAll().isEmpty(), "SELECT * FROM pagamento devolveu linhas para vendedor");
            assertNull(PAGAMENTO_DAO.findByPedido(pedido.getId()));
        });
        comoCliente(clienteB, () -> {
            assertNull(PAGAMENTO_DAO.findByPedido(pedido.getId()));
            return null;
        });

        comoAdmin(() -> {
            assertTrue(PAGAMENTO_DAO.atualizarStatus(pagamento.getId(), StatusPagamento.APROVADO));
            assertEquals(StatusPagamento.APROVADO, PAGAMENTO_DAO.findById(pagamento.getId()).getStatus());
        });
    }

    @Test
    @DisplayName("#18 cliente não registra pagamento já aprovado nem em pedido alheio")
    void clienteNaoForjaPagamento() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "40.00", 5));

        comoCliente(clienteA, () -> {
            assertEquals(INSUFFICIENT_PRIVILEGE, estado(assertThrows(RuntimeException.class,
                    () -> PAGAMENTO_DAO.save(novoPagamento(pedido, StatusPagamento.APROVADO)))));
            return null;
        });
        comoCliente(clienteB, () -> {
            assertEquals(INSUFFICIENT_PRIVILEGE, estado(assertThrows(RuntimeException.class,
                    () -> PAGAMENTO_DAO.save(novoPagamento(pedido, StatusPagamento.PENDENTE)))));
            return null;
        });
    }

    @Test
    @DisplayName("#18 entrega: vendedor do pedido despacha; cliente acompanha; terceiros não veem")
    void regrasDeEntrega() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "40.00", 5));
        String codigo = "ENT" + pedido.getId() + sufixo;
        Entrega entrega = novaEntrega(pedido, codigo);

        JwtAuthenticationFilter.executeAuthenticated(vendedorY.getId(), "VENDEDOR", () ->
                assertEquals(INSUFFICIENT_PRIVILEGE, estado(assertThrows(RuntimeException.class,
                        () -> ENTREGA_DAO.save(novaEntrega(pedido, codigo + "y"))))));

        JwtAuthenticationFilter.executeAuthenticated(vendedorX.getId(), "VENDEDOR", () -> {
            ENTREGA_DAO.save(entrega);
            assertTrue(ENTREGA_DAO.atualizarStatus(entrega.getId(), StatusEntrega.EM_TRANSITO));
        });

        comoCliente(clienteA, () -> {
            assertEquals(StatusEntrega.EM_TRANSITO, ENTREGA_DAO.findByCodigoRastreamento(codigo).getStatusEntrega());
            assertNull(ENTREGA_DAO.findByPedido(pedido.getId()).getDataEnvio(), "data_envio opcional não foi lida como nula");
            assertFalse(ENTREGA_DAO.atualizarStatus(entrega.getId(), StatusEntrega.ENTREGUE), "cliente alterou a entrega");
            return null;
        });

        JwtAuthenticationFilter.executeAuthenticated(vendedorY.getId(), "VENDEDOR", () -> {
            assertNull(ENTREGA_DAO.findById(entrega.getId()));
            assertFalse(ENTREGA_DAO.atualizarStatus(entrega.getId(), StatusEntrega.ENTREGUE));
        });
    }

    @Test
    @DisplayName("#18 devolução: cliente solicita; vendedor do pedido decide; cliente não se aprova")
    void regrasDeDevolucao() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "40.00", 5));
        Devolucao devolucao = novaDevolucao(pedido);

        comoCliente(clienteB, () -> {
            assertEquals(INSUFFICIENT_PRIVILEGE, estado(assertThrows(RuntimeException.class,
                    () -> DEVOLUCAO_DAO.save(novaDevolucao(pedido)))));
            return null;
        });

        comoCliente(clienteA, () -> {
            Devolucao aprovada = novaDevolucao(pedido);
            aprovada.setStatus(StatusDevolucao.APROVADA);
            assertEquals(INSUFFICIENT_PRIVILEGE, estado(assertThrows(RuntimeException.class,
                    () -> DEVOLUCAO_DAO.save(aprovada))));

            DEVOLUCAO_DAO.save(devolucao);
            assertNotNull(devolucao.getId());
            assertFalse(DEVOLUCAO_DAO.atualizarStatus(devolucao.getId(), StatusDevolucao.APROVADA));
            return null;
        });

        JwtAuthenticationFilter.executeAuthenticated(vendedorY.getId(), "VENDEDOR", () -> {
            assertTrue(DEVOLUCAO_DAO.findByPedido(pedido.getId()).isEmpty());
            assertFalse(DEVOLUCAO_DAO.atualizarStatus(devolucao.getId(), StatusDevolucao.RECUSADA));
        });

        JwtAuthenticationFilter.executeAuthenticated(vendedorX.getId(), "VENDEDOR", () -> {
            assertTrue(DEVOLUCAO_DAO.findByStatus(StatusDevolucao.SOLICITADA).contains(devolucao));
            assertTrue(DEVOLUCAO_DAO.atualizarStatus(devolucao.getId(), StatusDevolucao.RECUSADA));
        });

        comoCliente(clienteA, () -> {
            assertEquals(StatusDevolucao.RECUSADA, DEVOLUCAO_DAO.findById(devolucao.getId()).getStatus());
            return null;
        });
    }

    @Test
    @DisplayName("#18 anônimo não alcança pós-venda, mesmo com GUC vazio herdado do pooler")
    void anonimoNaoVePosVenda() {
        Pedido pedido = pedidoDe(clienteA, novoProduto(vendedorX, "40.00", 5));
        comoCliente(clienteA, () -> {
            PAGAMENTO_DAO.save(novoPagamento(pedido, StatusPagamento.PENDENTE));
            return null;
        });

        JwtAuthenticationFilter.executeAnonymous(() -> {
            for (String tabela : List.of("pedido", "item_pedido", "pagamento", "entrega", "devolucao",
                    "endereco", "carrinho", "item_carrinho")) {
                Integer visiveis = TransactionalDataAccess.executeInTransaction(conn -> {
                    try (PreparedStatement guc = conn.prepareStatement("SELECT set_config('app.usuario_id', '', true)")) {
                        guc.executeQuery();
                    }
                    try (PreparedStatement stmt = conn.prepareStatement("SELECT count(*) FROM " + tabela);
                         ResultSet rs = stmt.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                });
                assertEquals(0, visiveis, tabela);
            }
        });
    }

    // ---------- apoio ----------

    private static void comoAdmin(Runnable acao) {
        JwtAuthenticationFilter.executeAuthenticated(ID_ADMIN, "ADMIN", acao);
    }

    private static <T> T comoCliente(Cliente cliente, java.util.function.Supplier<T> acao) {
        return JwtAuthenticationFilter.executeAuthenticated(cliente.getId(), "CLIENTE", acao);
    }

    private static Cliente novoCliente(String marca) {
        Cliente c = new Cliente();
        c.setNome("Cliente " + marca);
        c.setEmail("pedido." + marca + "." + sufixo + "@exemplo.local");
        c.setSenhaHash("hash");
        CLIENTE_DAO.save(c);
        return c;
    }

    private static Vendedor novoVendedor(String marca) {
        Vendedor v = new Vendedor();
        v.setNome("Vendedor " + marca);
        v.setEmail("pedido.vendedor." + marca + "." + sufixo + "@exemplo.local");
        v.setSenhaHash("hash");
        v.setRazaoSocial("Loja " + marca);
        v.setCnpjCpf(sufixo + marca);
        VENDEDOR_DAO.save(v);
        return v;
    }

    private static Produto novoProduto(Vendedor vendedor, String preco, int estoque) {
        Produto p = new Produto();
        p.setNome("Produto " + sufixo);
        p.setEstoque(estoque);
        p.setPreco(new BigDecimal(preco));
        p.setIdVendedor(vendedor.getId());
        p.setCategoria(categoria);
        JwtAuthenticationFilter.executeAuthenticated(vendedor.getId(), "VENDEDOR", () -> PRODUTO_DAO.save(p));
        return p;
    }

    private static void encherCarrinho(Cliente cliente, Produto produto, int quantidade) {
        Carrinho carrinho = CARRINHO_DAO.criarParaCliente(cliente.getId());
        ITEM_CARRINHO_DAO.adicionarItem(new ItemCarrinho(carrinho.getId(), produto.getId(), quantidade, produto.getPreco()));
    }

    private static Pedido pedidoDe(Cliente cliente, Produto produto) {
        return comoCliente(cliente, () -> {
            encherCarrinho(cliente, produto, 1);
            return PEDIDO_DAO.checkout(null);
        });
    }

    private static Endereco novoEndereco(Cliente cliente) {
        Endereco e = new Endereco();
        e.setRua("Rua das Trincheiras");
        e.setNumero("100");
        e.setCep("58000-000");
        e.setCidade("João Pessoa");
        e.setEstado("PB");
        e.setIdCliente(cliente.getId());
        return e;
    }

    private static Pagamento novoPagamento(Pedido pedido, StatusPagamento status) {
        Pagamento p = new Pagamento();
        p.setMetodo(MetodoPagamento.PIX);
        p.setStatus(status);
        p.setValorPago(pedido.getValorTotal());
        p.setIdPedido(pedido.getId());
        return p;
    }

    private static Entrega novaEntrega(Pedido pedido, String codigo) {
        Entrega e = new Entrega();
        e.setTransportadora("Correios");
        e.setCodigoRastreamento(codigo);
        e.setIdPedido(pedido.getId());
        return e;
    }

    private static Devolucao novaDevolucao(Pedido pedido) {
        Devolucao d = new Devolucao();
        d.setMotivo("Produto com defeito");
        d.setIdPedido(pedido.getId());
        return d;
    }

    private static String estado(RuntimeException erro) {
        return erro.getCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }

    /** Executa um comando que deve falhar e devolve o SQLSTATE do erro. */
    private static String estadoAoExecutar(String sql) {
        return estado(assertThrows(RuntimeException.class, () ->
                TransactionalDataAccess.executeInTransactionVoid(conn -> {
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.execute();
                    }
                })));
    }

    private static boolean scriptsAplicados() {
        try {
            return TransactionalDataAccess.executeInTransaction(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT to_regclass('devolucao') IS NOT NULL AND to_regclass('endereco') IS NOT NULL");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            });
        } catch (RuntimeException e) {
            return false;
        }
    }
}
