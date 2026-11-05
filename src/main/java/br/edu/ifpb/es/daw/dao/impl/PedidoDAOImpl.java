package br.edu.ifpb.es.daw.dao.impl;

import br.edu.ifpb.es.daw.context.TenantContext;
import br.edu.ifpb.es.daw.dao.CheckoutException;
import br.edu.ifpb.es.daw.dao.ItemPedidoDAO;
import br.edu.ifpb.es.daw.dao.PedidoDAO;
import br.edu.ifpb.es.daw.dao.RowMapper;
import br.edu.ifpb.es.daw.dao.TransactionalDataAccess;
import br.edu.ifpb.es.daw.entities.ItemPedido;
import br.edu.ifpb.es.daw.entities.Pedido;
import br.edu.ifpb.es.daw.entities.StatusPedido;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO JDBC de pedido (issue #15). Tabela e policies vêm da issue #14
 * (11_ddl_rls_pedido_item_pedido.sql), com as correções de
 * 12_fix_rls_pedido_carrinho.sql.
 *
 * O checkout usa uma única conexão do início ao fim. Chamar os métodos
 * públicos dos DAOs não serviria: cada um abre a própria transação, e o
 * rollback não desfaria o que já tivesse sido confirmado.
 *
 * A baixa de estoque passa por baixar_estoque_pedido porque produto_update
 * não alcança o cliente: um UPDATE direto afetaria 0 linhas, sem erro.
 */
public class PedidoDAOImpl extends AbstractDAOImpl<Pedido> implements PedidoDAO {

    /** RAISE EXCEPTION de baixar_estoque_pedido. */
    private static final String SQLSTATE_RAISE = "P0001";

    private static final String COLUNAS = "id_pedido, data_pedido, valor_total, status, id_cliente, id_cupom";

    private static final RowMapper<Pedido> PEDIDO_MAPPER = rs -> {
        Pedido p = new Pedido();
        p.setId(rs.getLong("id_pedido"));
        p.setDataPedido(rs.getTimestamp("data_pedido").toLocalDateTime());
        p.setValorTotal(rs.getBigDecimal("valor_total"));
        p.setStatus(StatusPedido.valueOf(rs.getString("status")));
        p.setIdCliente(rs.getLong("id_cliente"));
        p.setIdCupom(rs.getObject("id_cupom", Long.class));
        return p;
    };

    private static final String INSERT_SQL = """
            INSERT INTO pedido (data_pedido, valor_total, status, id_cliente, id_cupom)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id_pedido
            """;

    private static final String FIND_BY_ID_SQL =
            "SELECT " + COLUNAS + " FROM pedido WHERE id_pedido = ?";

    private static final String FIND_ALL_SQL =
            "SELECT " + COLUNAS + " FROM pedido ORDER BY data_pedido DESC, id_pedido DESC";

    private static final String FIND_BY_CLIENTE_SQL =
            "SELECT " + COLUNAS + " FROM pedido WHERE id_cliente = ? ORDER BY data_pedido DESC, id_pedido DESC";

    private static final String FIND_BY_VENDEDOR_SQL = """
            SELECT id_pedido, data_pedido, valor_total, status, id_cliente, id_cupom
            FROM pedido p
            WHERE EXISTS (
                SELECT 1
                FROM item_pedido ip
                JOIN produto pr ON pr.id_produto = ip.id_produto
                WHERE ip.id_pedido = p.id_pedido AND pr.id_vendedor = ?
            )
            ORDER BY data_pedido DESC, id_pedido DESC
            """;

    private static final String UPDATE_SQL = """
            UPDATE pedido
            SET data_pedido = ?, valor_total = ?, status = ?, id_cliente = ?, id_cupom = ?
            WHERE id_pedido = ?
            """;

    private static final String UPDATE_STATUS_SQL = "UPDATE pedido SET status = ? WHERE id_pedido = ?";
    private static final String DELETE_SQL = "DELETE FROM pedido WHERE id_pedido = ?";
    private static final String DELETE_ALL_SQL = "DELETE FROM pedido";

    private static final String CARRINHO_DO_CLIENTE_SQL = "SELECT id FROM carrinho WHERE id_cliente = ?";

    // O preço vem de produto, não de item_carrinho: o valor guardado no
    // carrinho pode estar defasado em relação ao preço atual.
    private static final String ITENS_DO_CARRINHO_SQL = """
            SELECT ic.produto_id, ic.quantidade, pr.preco
            FROM item_carrinho ic
            JOIN produto pr ON pr.id_produto = ic.produto_id
            WHERE ic.carrinho_id = ?
            ORDER BY ic.produto_id
            """;

    private static final String CUPOM_VALIDO_SQL = """
            SELECT id, valor_desconto
            FROM cupom
            WHERE codigo = ? AND status = 'ATIVO' AND data_expiracao >= CURRENT_DATE
            """;

    private static final String BAIXAR_ESTOQUE_SQL = "SELECT baixar_estoque_pedido(?)";
    private static final String LIMPAR_CARRINHO_SQL = "DELETE FROM item_carrinho WHERE carrinho_id = ?";
    private static final String TOCAR_CARRINHO_SQL = "UPDATE carrinho SET data_ultima_atualizacao = ? WHERE id = ?";

    private final ItemPedidoDAO itemPedidoDAO = new ItemPedidoDAOImpl();

    @Override
    public void save(Pedido pedido) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> inserir(conn, pedido));
    }

    @Override
    public Pedido findById(Long id) {
        List<Pedido> pedidos = listar(FIND_BY_ID_SQL, id);
        return pedidos.isEmpty() ? null : pedidos.get(0);
    }

    @Override
    public List<Pedido> findAll() {
        return listar(FIND_ALL_SQL, null);
    }

    @Override
    public List<Pedido> findByCliente(Long idCliente) {
        return listar(FIND_BY_CLIENTE_SQL, idCliente);
    }

    @Override
    public List<Pedido> findByVendedor(Long idVendedor) {
        return listar(FIND_BY_VENDEDOR_SQL, idVendedor);
    }

    @Override
    public void update(Pedido pedido) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
                preencher(stmt, pedido);
                stmt.setLong(6, pedido.getId());
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public boolean atualizarStatus(Long idPedido, StatusPedido status) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {
                stmt.setString(1, status.name());
                stmt.setLong(2, idPedido);
                return stmt.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void delete(Pedido pedido) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
                stmt.setLong(1, pedido.getId());
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public void deleteAll() {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public Pedido checkout(String codigoCupom) {
        TenantContext.TenantInfo usuario = TenantContext.get();
        if (usuario == null) {
            throw new IllegalStateException("Checkout exige um usuário autenticado");
        }

        try {
            return TransactionalDataAccess.executeInTransaction(conn ->
                    fecharCompra(conn, usuario.usuarioId(), codigoCupom));
        } catch (RuntimeException e) {
            throw traduzir(e);
        }
    }

    private Pedido fecharCompra(Connection conn, Long idCliente, String codigoCupom) throws SQLException {
        Long idCarrinho = buscarCarrinho(conn, idCliente);
        List<ItemPedido> itens = idCarrinho == null ? List.of() : lerItens(conn, idCarrinho);
        if (itens.isEmpty()) {
            throw new CheckoutException("Carrinho vazio");
        }

        BigDecimal total = itens.stream()
                .map(i -> i.getPrecoUnitario().multiply(BigDecimal.valueOf(i.getQuantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Pedido pedido = new Pedido();
        pedido.setIdCliente(idCliente);
        pedido.setStatus(StatusPedido.CRIADO);

        if (codigoCupom != null) {
            try (PreparedStatement stmt = conn.prepareStatement(CUPOM_VALIDO_SQL)) {
                stmt.setString(1, codigoCupom);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new CheckoutException("Cupom inválido: " + codigoCupom);
                    }
                    pedido.setIdCupom(rs.getLong("id"));
                    // chk_pedido_valor_total: o desconto nunca deixa o total negativo.
                    total = total.subtract(rs.getBigDecimal("valor_desconto")).max(BigDecimal.ZERO);
                }
            }
        }

        pedido.setValorTotal(total);
        inserir(conn, pedido);

        itens.forEach(item -> item.setIdPedido(pedido.getId()));
        itemPedidoDAO.saveBatch(conn, itens);

        try (PreparedStatement stmt = conn.prepareStatement(BAIXAR_ESTOQUE_SQL)) {
            stmt.setLong(1, pedido.getId());
            stmt.executeQuery();
        }

        try (PreparedStatement stmt = conn.prepareStatement(LIMPAR_CARRINHO_SQL)) {
            stmt.setLong(1, idCarrinho);
            stmt.executeUpdate();
        }

        try (PreparedStatement stmt = conn.prepareStatement(TOCAR_CARRINHO_SQL)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setLong(2, idCarrinho);
            stmt.executeUpdate();
        }

        return pedido;
    }

    private Long buscarCarrinho(Connection conn, Long idCliente) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(CARRINHO_DO_CLIENTE_SQL)) {
            stmt.setLong(1, idCliente);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private List<ItemPedido> lerItens(Connection conn, Long idCarrinho) throws SQLException {
        List<ItemPedido> itens = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(ITENS_DO_CARRINHO_SQL)) {
            stmt.setLong(1, idCarrinho);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    itens.add(new ItemPedido(null, rs.getLong("produto_id"),
                            rs.getInt("quantidade"), rs.getBigDecimal("preco")));
                }
            }
        }
        return itens;
    }

    private void inserir(Connection conn, Pedido pedido) throws SQLException {
        pedido.onCreate();
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            preencher(stmt, pedido);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    pedido.setId(rs.getLong("id_pedido"));
                }
            }
        }
    }

    private void preencher(PreparedStatement stmt, Pedido pedido) throws SQLException {
        stmt.setTimestamp(1, Timestamp.valueOf(pedido.getDataPedido()));
        stmt.setBigDecimal(2, pedido.getValorTotal());
        stmt.setString(3, pedido.getStatus().name());
        stmt.setLong(4, pedido.getIdCliente());
        if (pedido.getIdCupom() != null) {
            stmt.setLong(5, pedido.getIdCupom());
        } else {
            stmt.setNull(5, Types.BIGINT);
        }
    }

    private List<Pedido> listar(String sql, Long parametro) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            List<Pedido> pedidos = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (parametro != null) {
                    stmt.setLong(1, parametro);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        pedidos.add(PEDIDO_MAPPER.mapRow(rs));
                    }
                }
            }
            return pedidos;
        });
    }

    /**
     * TransactionalDataAccess embrulha toda exceção em RuntimeException, então
     * a regra de negócio precisa ser recuperada da causa.
     */
    private static RuntimeException traduzir(RuntimeException e) {
        Throwable causa = e.getCause();
        if (causa instanceof CheckoutException checkout) {
            return checkout;
        }
        if (causa instanceof SQLException sql && SQLSTATE_RAISE.equals(sql.getSQLState())) {
            return new CheckoutException(sql.getMessage().lines().findFirst().orElse("Falha no checkout"));
        }
        return e;
    }
}
