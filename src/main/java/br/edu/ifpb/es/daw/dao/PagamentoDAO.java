package br.edu.ifpb.es.daw.dao;

import br.edu.ifpb.es.daw.entities.Pagamento;
import br.edu.ifpb.es.daw.entities.StatusPagamento;

public interface PagamentoDAO extends DAO<Pagamento> {

    /**
     * @return o pagamento do pedido (1:1), ou null se não existir ou se o RLS
     *         negar o acesso. O vendedor nunca enxerga pagamentos.
     */
    Pagamento findByPedido(Long idPedido);

    /**
     * @return true se a linha foi atualizada; false se o RLS negou (só ADMIN
     *         altera o status) ou se o pagamento não existe
     */
    boolean atualizarStatus(Long idPagamento, StatusPagamento status);
}
