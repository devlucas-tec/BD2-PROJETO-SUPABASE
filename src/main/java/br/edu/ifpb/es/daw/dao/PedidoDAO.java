package br.edu.ifpb.es.daw.dao;

import br.edu.ifpb.es.daw.entities.Pedido;
import br.edu.ifpb.es.daw.entities.StatusPedido;

import java.util.List;

public interface PedidoDAO extends DAO<Pedido> {

    /**
     * @param idCliente id do cliente dono dos pedidos
     * @return pedidos visíveis no contexto atual, mais recentes primeiro
     */
    List<Pedido> findByCliente(Long idCliente);

    /**
     * @param idVendedor id do vendedor dono de produtos nos pedidos
     * @return pedidos visíveis no contexto atual, mais recentes primeiro
     */
    List<Pedido> findByVendedor(Long idVendedor);

    /**
     * @return true se a linha foi atualizada; false se o pedido não existe ou
     *         se o RLS negou o acesso ao contexto atual
     */
    boolean atualizarStatus(Long idPedido, StatusPedido status);

    /**
     * Fecha a compra do usuário autenticado em uma única transação: cria o
     * pedido, insere os itens, baixa o estoque e limpa o carrinho. Qualquer
     * falha desfaz tudo, sem deixar pedido órfão.
     *
     * Preços e total são calculados aqui, a partir do valor atual dos
     * produtos. O cliente vem do TenantContext, nunca de um parâmetro.
     *
     * @param codigoCupom código do cupom, ou null para comprar sem desconto
     * @return o pedido criado
     * @throws CheckoutException     se o carrinho estiver vazio, o cupom for
     *                               inválido ou faltar estoque
     * @throws IllegalStateException se não houver usuário autenticado
     */
    Pedido checkout(String codigoCupom);
}
