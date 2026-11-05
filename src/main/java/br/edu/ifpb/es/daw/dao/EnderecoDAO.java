package br.edu.ifpb.es.daw.dao;

import br.edu.ifpb.es.daw.entities.Endereco;

import java.util.List;

public interface EnderecoDAO extends DAO<Endereco> {

    /**
     * Lista os endereços de um cliente.
     *
     * @param idCliente id do cliente dono
     * @return lista vazia quando o RLS negar o acesso ao contexto atual
     */
    List<Endereco> findByCliente(Long idCliente);
}
