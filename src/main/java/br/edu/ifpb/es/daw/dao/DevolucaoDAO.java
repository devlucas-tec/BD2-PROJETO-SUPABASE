package br.edu.ifpb.es.daw.dao;

import br.edu.ifpb.es.daw.entities.Devolucao;
import br.edu.ifpb.es.daw.entities.StatusDevolucao;

import java.util.List;

public interface DevolucaoDAO extends DAO<Devolucao> {

    /** @return devoluções do pedido visíveis no contexto atual */
    List<Devolucao> findByPedido(Long idPedido);

    /** @return devoluções com o status informado visíveis no contexto atual */
    List<Devolucao> findByStatus(StatusDevolucao status);

    /**
     * @return true se a linha foi atualizada; false se o RLS negou (só o
     *         vendedor do pedido ou ADMIN) ou se a devolução não existe
     */
    boolean atualizarStatus(Long idDevolucao, StatusDevolucao status);
}
