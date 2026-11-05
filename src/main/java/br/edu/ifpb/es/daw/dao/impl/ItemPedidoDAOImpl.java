package br.edu.ifpb.es.daw.dao.impl;

import br.edu.ifpb.es.daw.dao.ItemPedidoDAO;
import br.edu.ifpb.es.daw.dao.RowMapper;
import br.edu.ifpb.es.daw.dao.TransactionalDataAccess;
import br.edu.ifpb.es.daw.entities.ItemPedido;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO JDBC de item_pedido (issue #15). Tabela e policies vêm da issue #14
 * (11_ddl_rls_pedido_item_pedido.sql).
 */
public class ItemPedidoDAOImpl implements ItemPedidoDAO {

    private static final RowMapper<ItemPedido> ITEM_MAPPER = rs -> new ItemPedido(
            rs.getLong("id_pedido"),
            rs.getLong("id_produto"),
            rs.getInt("quantidade"),
            rs.getBigDecimal("preco_unitario"));

    private static final String INSERT_SQL = """
            INSERT INTO item_pedido (id_pedido, id_produto, quantidade, preco_unitario)
            VALUES (?, ?, ?, ?)
            """;

    private static final String FIND_BY_PEDIDO_SQL = """
            SELECT id_pedido, id_produto, quantidade, preco_unitario
            FROM item_pedido
            WHERE id_pedido = ?
            ORDER BY id_produto
            """;

    @Override
    public void saveBatch(List<ItemPedido> itens) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> saveBatch(conn, itens));
    }

    @Override
    public void saveBatch(Connection conn, List<ItemPedido> itens) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            for (ItemPedido item : itens) {
                stmt.setLong(1, item.getIdPedido());
                stmt.setLong(2, item.getIdProduto());
                stmt.setInt(3, item.getQuantidade());
                stmt.setBigDecimal(4, item.getPrecoUnitario());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    @Override
    public List<ItemPedido> findByPedido(Long idPedido) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            List<ItemPedido> itens = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(FIND_BY_PEDIDO_SQL)) {
                stmt.setLong(1, idPedido);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        itens.add(ITEM_MAPPER.mapRow(rs));
                    }
                }
            }
            return itens;
        });
    }
}
