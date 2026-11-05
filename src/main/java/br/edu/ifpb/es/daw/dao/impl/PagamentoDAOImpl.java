package br.edu.ifpb.es.daw.dao.impl;

import br.edu.ifpb.es.daw.dao.PagamentoDAO;
import br.edu.ifpb.es.daw.dao.RowMapper;
import br.edu.ifpb.es.daw.dao.TransactionalDataAccess;
import br.edu.ifpb.es.daw.entities.MetodoPagamento;
import br.edu.ifpb.es.daw.entities.Pagamento;
import br.edu.ifpb.es.daw.entities.StatusPagamento;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO JDBC de pagamento (issue #19). Tabela em
 * 14_ddl_pagamento_entrega_devolucao.sql e policies em
 * 15_rls_pagamento_entrega_devolucao.sql.
 */
public class PagamentoDAOImpl extends AbstractDAOImpl<Pagamento> implements PagamentoDAO {

    private static final String COLUNAS = "id_pagamento, metodo, status, valor_pago, data_pagamento, id_pedido";

    private static final RowMapper<Pagamento> PAGAMENTO_MAPPER = rs -> {
        Pagamento p = new Pagamento();
        p.setId(rs.getLong("id_pagamento"));
        p.setMetodo(MetodoPagamento.valueOf(rs.getString("metodo")));
        p.setStatus(StatusPagamento.valueOf(rs.getString("status")));
        p.setValorPago(rs.getBigDecimal("valor_pago"));
        p.setDataPagamento(rs.getTimestamp("data_pagamento").toLocalDateTime());
        p.setIdPedido(rs.getLong("id_pedido"));
        return p;
    };

    private static final String INSERT_SQL = """
            INSERT INTO pagamento (metodo, status, valor_pago, data_pagamento, id_pedido)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id_pagamento
            """;

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUNAS + " FROM pagamento WHERE id_pagamento = ?";
    private static final String FIND_ALL_SQL = "SELECT " + COLUNAS + " FROM pagamento ORDER BY id_pagamento";
    private static final String FIND_BY_PEDIDO_SQL = "SELECT " + COLUNAS + " FROM pagamento WHERE id_pedido = ?";

    private static final String UPDATE_SQL = """
            UPDATE pagamento
            SET metodo = ?, status = ?, valor_pago = ?, data_pagamento = ?, id_pedido = ?
            WHERE id_pagamento = ?
            """;

    private static final String UPDATE_STATUS_SQL = "UPDATE pagamento SET status = ? WHERE id_pagamento = ?";
    private static final String DELETE_SQL = "DELETE FROM pagamento WHERE id_pagamento = ?";
    private static final String DELETE_ALL_SQL = "DELETE FROM pagamento";

    @Override
    public void save(Pagamento pagamento) {
        pagamento.onCreate();
        if (pagamento.getStatus() == null) {
            pagamento.setStatus(StatusPagamento.PENDENTE);
        }
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
                preencher(stmt, pagamento);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        pagamento.setId(rs.getLong("id_pagamento"));
                    }
                }
            }
        });
    }

    @Override
    public Pagamento findById(Long id) {
        List<Pagamento> pagamentos = listar(FIND_BY_ID_SQL, id);
        return pagamentos.isEmpty() ? null : pagamentos.get(0);
    }

    @Override
    public List<Pagamento> findAll() {
        return listar(FIND_ALL_SQL, null);
    }

    @Override
    public Pagamento findByPedido(Long idPedido) {
        List<Pagamento> pagamentos = listar(FIND_BY_PEDIDO_SQL, idPedido);
        return pagamentos.isEmpty() ? null : pagamentos.get(0);
    }

    @Override
    public void update(Pagamento pagamento) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
                preencher(stmt, pagamento);
                stmt.setLong(6, pagamento.getId());
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public boolean atualizarStatus(Long idPagamento, StatusPagamento status) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {
                stmt.setString(1, status.name());
                stmt.setLong(2, idPagamento);
                return stmt.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void delete(Pagamento pagamento) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
                stmt.setLong(1, pagamento.getId());
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

    private void preencher(PreparedStatement stmt, Pagamento pagamento) throws SQLException {
        stmt.setString(1, pagamento.getMetodo().name());
        stmt.setString(2, pagamento.getStatus().name());
        stmt.setBigDecimal(3, pagamento.getValorPago());
        stmt.setTimestamp(4, Timestamp.valueOf(pagamento.getDataPagamento()));
        stmt.setLong(5, pagamento.getIdPedido());
    }

    private List<Pagamento> listar(String sql, Long parametro) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            List<Pagamento> pagamentos = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (parametro != null) {
                    stmt.setLong(1, parametro);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        pagamentos.add(PAGAMENTO_MAPPER.mapRow(rs));
                    }
                }
            }
            return pagamentos;
        });
    }
}
