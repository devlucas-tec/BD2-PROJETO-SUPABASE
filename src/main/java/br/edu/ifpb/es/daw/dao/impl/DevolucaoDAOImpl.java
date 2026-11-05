package br.edu.ifpb.es.daw.dao.impl;

import br.edu.ifpb.es.daw.dao.DevolucaoDAO;
import br.edu.ifpb.es.daw.dao.RowMapper;
import br.edu.ifpb.es.daw.dao.TransactionalDataAccess;
import br.edu.ifpb.es.daw.entities.Devolucao;
import br.edu.ifpb.es.daw.entities.StatusDevolucao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO JDBC de devolucao (issue #19). Tabela em
 * 14_ddl_pagamento_entrega_devolucao.sql e policies em
 * 15_rls_pagamento_entrega_devolucao.sql.
 */
public class DevolucaoDAOImpl extends AbstractDAOImpl<Devolucao> implements DevolucaoDAO {

    private static final String COLUNAS = "id, data_devolucao, motivo, status, id_pedido";

    private static final RowMapper<Devolucao> DEVOLUCAO_MAPPER = rs -> {
        Devolucao d = new Devolucao();
        d.setId(rs.getLong("id"));
        d.setDataDevolucao(rs.getTimestamp("data_devolucao").toLocalDateTime());
        d.setMotivo(rs.getString("motivo"));
        d.setStatus(StatusDevolucao.valueOf(rs.getString("status")));
        d.setIdPedido(rs.getLong("id_pedido"));
        return d;
    };

    private static final String INSERT_SQL = """
            INSERT INTO devolucao (data_devolucao, motivo, status, id_pedido)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUNAS + " FROM devolucao WHERE id = ?";
    private static final String FIND_ALL_SQL = "SELECT " + COLUNAS + " FROM devolucao ORDER BY id";
    private static final String FIND_BY_PEDIDO_SQL =
            "SELECT " + COLUNAS + " FROM devolucao WHERE id_pedido = ? ORDER BY id";
    private static final String FIND_BY_STATUS_SQL =
            "SELECT " + COLUNAS + " FROM devolucao WHERE status = ? ORDER BY id";

    private static final String UPDATE_SQL = """
            UPDATE devolucao
            SET data_devolucao = ?, motivo = ?, status = ?, id_pedido = ?
            WHERE id = ?
            """;

    private static final String UPDATE_STATUS_SQL = "UPDATE devolucao SET status = ? WHERE id = ?";
    private static final String DELETE_SQL = "DELETE FROM devolucao WHERE id = ?";
    private static final String DELETE_ALL_SQL = "DELETE FROM devolucao";

    @Override
    public void save(Devolucao devolucao) {
        devolucao.onCreate();
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
                preencher(stmt, devolucao);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        devolucao.setId(rs.getLong("id"));
                    }
                }
            }
        });
    }

    @Override
    public Devolucao findById(Long id) {
        List<Devolucao> devolucoes = listar(FIND_BY_ID_SQL, id);
        return devolucoes.isEmpty() ? null : devolucoes.get(0);
    }

    @Override
    public List<Devolucao> findAll() {
        return listar(FIND_ALL_SQL, null);
    }

    @Override
    public List<Devolucao> findByPedido(Long idPedido) {
        return listar(FIND_BY_PEDIDO_SQL, idPedido);
    }

    @Override
    public List<Devolucao> findByStatus(StatusDevolucao status) {
        return listar(FIND_BY_STATUS_SQL, status.name());
    }

    @Override
    public void update(Devolucao devolucao) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
                preencher(stmt, devolucao);
                stmt.setLong(5, devolucao.getId());
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public boolean atualizarStatus(Long idDevolucao, StatusDevolucao status) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {
                stmt.setString(1, status.name());
                stmt.setLong(2, idDevolucao);
                return stmt.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void delete(Devolucao devolucao) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
                stmt.setLong(1, devolucao.getId());
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

    private void preencher(PreparedStatement stmt, Devolucao devolucao) throws SQLException {
        stmt.setTimestamp(1, Timestamp.valueOf(devolucao.getDataDevolucao()));
        stmt.setString(2, devolucao.getMotivo());
        stmt.setString(3, devolucao.getStatus().name());
        stmt.setLong(4, devolucao.getIdPedido());
    }

    private List<Devolucao> listar(String sql, Object parametro) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            List<Devolucao> devolucoes = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (parametro != null) {
                    stmt.setObject(1, parametro);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        devolucoes.add(DEVOLUCAO_MAPPER.mapRow(rs));
                    }
                }
            }
            return devolucoes;
        });
    }
}
