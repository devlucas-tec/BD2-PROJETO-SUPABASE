package br.edu.ifpb.es.daw.dao;

/**
 * Regra de negócio que impediu o checkout: carrinho vazio, cupom inválido ou
 * estoque insuficiente. A transação inteira já sofreu rollback quando esta
 * exceção chega ao chamador.
 */
public class CheckoutException extends RuntimeException {

    private static final long serialVersionUID = 4021788163209145571L;

    public CheckoutException(String message) {
        super(message);
    }
}
