package br.edu.ifpb.es.daw.dao;

import br.edu.ifpb.es.daw.entities.Entrega;
import br.edu.ifpb.es.daw.entities.StatusEntrega;

public interface EntregaDAO extends DAO<Entrega> {

    /** @return a entrega do pedido (1:1), ou null se não existir ou se o RLS negar */
    Entrega findByPedido(Long idPedido);

    /** @return a entrega com o código informado, ou null se não existir ou se o RLS negar */
    Entrega findByCodigoRastreamento(String codigoRastreamento);

    /**
     * @return true se a linha foi atualizada; false se o RLS negou (só o
     *         vendedor do pedido ou ADMIN) ou se a entrega não existe
     */
    boolean atualizarStatus(Long idEntrega, StatusEntrega status);
}
