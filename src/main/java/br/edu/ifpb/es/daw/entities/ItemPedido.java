package br.edu.ifpb.es.daw.entities;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * item_pedido não tem coluna "id": a PK é composta por (id_pedido,
 * id_produto) — ver 11_ddl_rls_pedido_item_pedido.sql. Mesmo tratamento de
 * ItemCarrinho: a chave é modelada como duas FKs simples.
 */
public class ItemPedido {

    private Long idPedido;

    private Long idProduto;

    private BigDecimal precoUnitario;

    private Integer quantidade;

    public ItemPedido() {
    }

    public ItemPedido(Long idPedido, Long idProduto, Integer quantidade, BigDecimal precoUnitario) {
        this.idPedido = idPedido;
        this.idProduto = idProduto;
        this.quantidade = quantidade;
        this.precoUnitario = precoUnitario;
    }

    public Long getIdPedido() {
        return idPedido;
    }

    public void setIdPedido(Long idPedido) {
        this.idPedido = idPedido;
    }

    public Long getIdProduto() {
        return idProduto;
    }

    public void setIdProduto(Long idProduto) {
        this.idProduto = idProduto;
    }

    public BigDecimal getPrecoUnitario() {
        return precoUnitario;
    }

    public void setPrecoUnitario(BigDecimal precoUnitario) {
        this.precoUnitario = precoUnitario;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(Integer quantidade) {
        this.quantidade = quantidade;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ItemPedido that = (ItemPedido) o;
        return Objects.equals(idPedido, that.idPedido) && Objects.equals(idProduto, that.idProduto);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idPedido, idProduto);
    }

    @Override
    public String toString() {
        return "ItemPedido{" +
                "idPedido=" + idPedido +
                ", idProduto=" + idProduto +
                ", precoUnitario=" + precoUnitario +
                ", quantidade=" + quantidade +
                '}';
    }
}
