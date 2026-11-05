package br.edu.ifpb.es.daw.dao;

import br.edu.ifpb.es.daw.entities.ItemPedido;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * DAO de item_pedido (issue #15). Não estende DAO&lt;ItemPedido&gt; pelo mesmo
 * motivo de ItemCarrinhoDAO: a PK é composta (id_pedido, id_produto).
 */
public interface ItemPedidoDAO {

    /**
     * Grava os itens em lote, em uma transação própria.
     *
     * @param itens itens com idPedido, idProduto, quantidade e precoUnitario
     */
    void saveBatch(List<ItemPedido> itens);

    /**
     * Grava os itens em lote na transação de quem chama. É o que permite ao
     * checkout gravar pedido e itens de forma atômica.
     *
     * @param conn  conexão aberta por TransactionalDataAccess
     * @param itens itens com idPedido, idProduto, quantidade e precoUnitario
     */
    void saveBatch(Connection conn, List<ItemPedido> itens) throws SQLException;

    /**
     * Lista os itens de um pedido visíveis no contexto atual. O vendedor só
     * enxerga os itens dos próprios produtos.
     *
     * @param idPedido id do pedido
     * @return lista possivelmente vazia, ordenada por id_produto
     */
    List<ItemPedido> findByPedido(Long idPedido);
}
