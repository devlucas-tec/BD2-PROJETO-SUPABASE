package br.edu.ifpb.es.daw.dao.impl;

import br.edu.ifpb.es.daw.dao.EntregaDAO;
import br.edu.ifpb.es.daw.dao.RowMapper;
import br.edu.ifpb.es.daw.dao.TransactionalDataAccess;
import br.edu.ifpb.es.daw.entities.Entrega;
import br.edu.ifpb.es.daw.entities.StatusEntrega;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO JDBC de entrega (issue #19). Tabela em
 * 14_ddl_pagamento_entrega_devolucao.sql e policies em
 * 15_rls_pagamento_entrega_devolucao.sql.
 */
public class EntregaDAOImpl extends AbstractDAOImpl<Entrega> implements EntregaDAO {

    private static final String COLUNAS =
            "id, transportadora, codigo_rastreamento, status_entrega, data_envio, data_entrega_prevista, id_pedido";

    private static final RowMapper<Entrega> ENTREGA_MAPPER = rs -> {
        Entrega e = new Entrega();
        e.setId(rs.getLong("id"));
        e.setTransportadora(rs.getString("transportadora"));
        e.setCodigoRastreamento(rs.getString("codigo_rastreamento"));
        e.setStatusEntrega(StatusEntrega.valueOf(rs.getString("status_entrega")));
        e.setDataEnvio(paraData(rs.getTimestamp("data_envio")));
        e.setDataEntregaPrevista(paraData(rs.getTimestamp("data_entrega_prevista")));
        e.setIdPedido(rs.getLong("id_pedido"));
        return e;
    };

    private static final String INSERT_SQL = """
            INSERT INTO entrega (transportadora, codigo_rastreamento, status_entrega,
                                 data_envio, data_entrega_prevista, id_pedido)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUNAS + " FROM entrega WHERE id = ?";
    private static final String FIND_ALL_SQL = "SELECT " + COLUNAS + " FROM entrega ORDER BY id";
    private static final String FIND_BY_PEDIDO_SQL = "SELECT " + COLUNAS + " FROM entrega WHERE id_pedido = ?";
    private static final String FIND_BY_CODIGO_SQL =
            "SELECT " + COLUNAS + " FROM entrega WHERE codigo_rastreamento = ?";

    private static final String UPDATE_SQL = """
            UPDATE entrega
            SET transportadora = ?, codigo_rastreamento = ?, status_entrega = ?,
                data_envio = ?, data_entrega_prevista = ?, id_pedido = ?
            WHERE id = ?
            """;

    private static final String UPDATE_STATUS_SQL = "UPDATE entrega SET status_entrega = ? WHERE id = ?";
    private static final String DELETE_SQL = "DELETE FROM entrega WHERE id = ?";
    private static final String DELETE_ALL_SQL = "DELETE FROM entrega";

    @Override
    public void save(Entrega entrega) {
        if (entrega.getStatusEntrega() == null) {
            entrega.setStatusEntrega(StatusEntrega.EMBALADO);
        }
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
                preencher(stmt, entrega);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        entrega.setId(rs.getLong("id"));
                    }
                }
            }
        });
    }

    @Override
    public Entrega findById(Long id) {
        return buscarUm(FIND_BY_ID_SQL, id);
    }

    @Override
    public List<Entrega> findAll() {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            List<Entrega> entregas = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(FIND_ALL_SQL);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entregas.add(ENTREGA_MAPPER.mapRow(rs));
                }
            }
            return entregas;
        });
    }

    @Override
    public Entrega findByPedido(Long idPedido) {
        return buscarUm(FIND_BY_PEDIDO_SQL, idPedido);
    }

    @Override
    public Entrega findByCodigoRastreamento(String codigoRastreamento) {
        return buscarUm(FIND_BY_CODIGO_SQL, codigoRastreamento);
    }

    @Override
    public void update(Entrega entrega) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
                preencher(stmt, entrega);
                stmt.setLong(7, entrega.getId());
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public boolean atualizarStatus(Long idEntrega, StatusEntrega status) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {
                stmt.setString(1, status.name());
                stmt.setLong(2, idEntrega);
                return stmt.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void delete(Entrega entrega) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
                stmt.setLong(1, entrega.getId());
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

    private void preencher(PreparedStatement stmt, Entrega entrega) throws SQLException {
        stmt.setString(1, entrega.getTransportadora());
        stmt.setString(2, entrega.getCodigoRastreamento());
        stmt.setString(3, entrega.getStatusEntrega().name());
        stmt.setTimestamp(4, paraTimestamp(entrega.getDataEnvio()));
        stmt.setTimestamp(5, paraTimestamp(entrega.getDataEntregaPrevista()));
        stmt.setLong(6, entrega.getIdPedido());
    }

    private Entrega buscarUm(String sql, Object parametro) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, parametro);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? ENTREGA_MAPPER.mapRow(rs) : null;
                }
            }
        });
    }

    private static LocalDateTime paraData(Timestamp valor) {
        return valor != null ? valor.toLocalDateTime() : null;
    }

    private static Timestamp paraTimestamp(LocalDateTime valor) {
        return valor != null ? Timestamp.valueOf(valor) : null;
    }
}
