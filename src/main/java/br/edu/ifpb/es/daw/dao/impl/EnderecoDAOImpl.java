package br.edu.ifpb.es.daw.dao.impl;

import br.edu.ifpb.es.daw.dao.EnderecoDAO;
import br.edu.ifpb.es.daw.dao.RowMapper;
import br.edu.ifpb.es.daw.dao.TransactionalDataAccess;
import br.edu.ifpb.es.daw.entities.Endereco;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO JDBC de endereco (issue #16). Tabela e policies em
 * 13_ddl_rls_endereco.sql.
 */
public class EnderecoDAOImpl extends AbstractDAOImpl<Endereco> implements EnderecoDAO {

    private static final String COLUNAS = "id, rua, numero, complemento, cep, cidade, estado, id_cliente";

    private static final RowMapper<Endereco> ENDERECO_MAPPER = rs -> {
        Endereco e = new Endereco();
        e.setId(rs.getLong("id"));
        e.setRua(rs.getString("rua"));
        e.setNumero(rs.getString("numero"));
        e.setComplemento(rs.getString("complemento"));
        e.setCep(rs.getString("cep"));
        e.setCidade(rs.getString("cidade"));
        e.setEstado(rs.getString("estado"));
        e.setIdCliente(rs.getLong("id_cliente"));
        return e;
    };

    private static final String INSERT_SQL = """
            INSERT INTO endereco (rua, numero, complemento, cep, cidade, estado, id_cliente)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUNAS + " FROM endereco WHERE id = ?";
    private static final String FIND_ALL_SQL = "SELECT " + COLUNAS + " FROM endereco ORDER BY id";
    private static final String FIND_BY_CLIENTE_SQL =
            "SELECT " + COLUNAS + " FROM endereco WHERE id_cliente = ? ORDER BY id";

    private static final String UPDATE_SQL = """
            UPDATE endereco
            SET rua = ?, numero = ?, complemento = ?, cep = ?, cidade = ?, estado = ?, id_cliente = ?
            WHERE id = ?
            """;

    private static final String DELETE_SQL = "DELETE FROM endereco WHERE id = ?";
    private static final String DELETE_ALL_SQL = "DELETE FROM endereco";

    @Override
    public void save(Endereco endereco) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
                preencher(stmt, endereco);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        endereco.setId(rs.getLong("id"));
                    }
                }
            }
        });
    }

    @Override
    public Endereco findById(Long id) {
        List<Endereco> enderecos = listar(FIND_BY_ID_SQL, id);
        return enderecos.isEmpty() ? null : enderecos.get(0);
    }

    @Override
    public List<Endereco> findAll() {
        return listar(FIND_ALL_SQL, null);
    }

    @Override
    public List<Endereco> findByCliente(Long idCliente) {
        return listar(FIND_BY_CLIENTE_SQL, idCliente);
    }

    @Override
    public void update(Endereco endereco) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
                preencher(stmt, endereco);
                stmt.setLong(8, endereco.getId());
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public void delete(Endereco endereco) {
        TransactionalDataAccess.executeInTransactionVoid(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
                stmt.setLong(1, endereco.getId());
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

    private void preencher(PreparedStatement stmt, Endereco endereco) throws SQLException {
        stmt.setString(1, endereco.getRua());
        stmt.setString(2, endereco.getNumero());
        stmt.setString(3, endereco.getComplemento());
        stmt.setString(4, endereco.getCep());
        stmt.setString(5, endereco.getCidade());
        stmt.setString(6, endereco.getEstado());
        stmt.setLong(7, endereco.getIdCliente());
    }

    private List<Endereco> listar(String sql, Long parametro) {
        return TransactionalDataAccess.executeInTransaction(conn -> {
            List<Endereco> enderecos = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (parametro != null) {
                    stmt.setLong(1, parametro);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        enderecos.add(ENDERECO_MAPPER.mapRow(rs));
                    }
                }
            }
            return enderecos;
        });
    }
}
