package br.edu.ifpb.es.daw.service;

import br.edu.ifpb.es.daw.dao.*;
import br.edu.ifpb.es.daw.dao.impl.*;
import br.edu.ifpb.es.daw.entities.*;
import br.edu.ifpb.es.daw.filter.JwtAuthenticationFilter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Carga inicial em JDBC (issue #20), usada como cenário de docs/testes-rls.sql.
 *
 * Cada escrita roda sob a identidade de quem a faria de verdade: o vendedor
 * cadastra os próprios produtos e o cliente faz o próprio checkout. Assim a
 * carga também passa pelas policies, em vez de contorná-las.
 *
 * Não apaga nada. Se o admin da carga já existir, nada é feito, então rodar de
 * novo é seguro. As senhas são fictícias: o projeto ainda não tem login.
 */
public class SeedService {

    public static final String EMAIL_ADMIN = "admin@seed.exemplo.local";
    public static final String EMAIL_ANA = "ana@seed.exemplo.local";
    public static final String EMAIL_BRUNO = "bruno@seed.exemplo.local";
    public static final String EMAIL_CARLA = "carla@seed.exemplo.local";
    public static final String EMAIL_LOJA_X = "loja.x@seed.exemplo.local";
    public static final String EMAIL_LOJA_Y = "loja.y@seed.exemplo.local";

    private static final String SENHA_FICTICIA = "seed-sem-login";
    private static final String CUPOM = "SEED10";

    /** O ADMIN é liberado pelo papel; o id só precisa ser válido. */
    private static final Long ID_ADMIN_BOOTSTRAP = 0L;

    private final UsuarioDAO usuarioDAO = new UsuarioDAOImpl();
    private final AdminDAO adminDAO = new AdminDAOImpl();
    private final ClienteDAO clienteDAO = new ClienteDAOImpl();
    private final VendedorDAO vendedorDAO = new VendedorDAOImpl();
    private final CategoriaDAO categoriaDAO = new CategoriaDAOImpl();
    private final ProdutoDAO produtoDAO = new ProdutoDAOImpl();
    private final CupomDAO cupomDAO = new CupomDAOImpl();
    private final AvaliacaoDAO avaliacaoDAO = new AvaliacaoDAOImpl();
    private final CarrinhoDAO carrinhoDAO = new CarrinhoDAOImpl();
    private final ItemCarrinhoDAO itemCarrinhoDAO = new ItemCarrinhoDAOImpl();
    private final PedidoDAO pedidoDAO = new PedidoDAOImpl();
    private final EnderecoDAO enderecoDAO = new EnderecoDAOImpl();
    private final PagamentoDAO pagamentoDAO = new PagamentoDAOImpl();
    private final EntregaDAO entregaDAO = new EntregaDAOImpl();
    private final DevolucaoDAO devolucaoDAO = new DevolucaoDAOImpl();

    /**
     * @return true se a carga foi feita; false se ela já existia
     */
    public boolean popular() {
        boolean jaPopulado = JwtAuthenticationFilter.executeAuthenticated(ID_ADMIN_BOOTSTRAP, "ADMIN",
                () -> usuarioDAO.existsByEmail(EMAIL_ADMIN));
        if (jaPopulado) {
            return false;
        }

        Cliente ana = new Cliente();
        Cliente bruno = new Cliente();
        Cliente carla = new Cliente();
        Vendedor lojaX = new Vendedor();
        Vendedor lojaY = new Vendedor();
        Categoria eletronicos = new Categoria();
        Categoria livros = new Categoria();
        Categoria casa = new Categoria();
        Admin admin = new Admin();

        JwtAuthenticationFilter.executeAuthenticated(ID_ADMIN_BOOTSTRAP, "ADMIN", () -> {
            preencherUsuario(admin, "Admin Seed", EMAIL_ADMIN);
            adminDAO.save(admin);

            salvarCliente(ana, "Ana Seed", EMAIL_ANA, "(83) 90000-0001");
            salvarCliente(bruno, "Bruno Seed", EMAIL_BRUNO, "(83) 90000-0002");
            salvarCliente(carla, "Carla Seed", EMAIL_CARLA, "(83) 90000-0003");

            salvarVendedor(lojaX, "Loja X", EMAIL_LOJA_X, "11111111000111");
            salvarVendedor(lojaY, "Loja Y", EMAIL_LOJA_Y, "22222222000122");

            salvarCategoria(eletronicos, "Eletrônicos");
            salvarCategoria(livros, "Livros");
            salvarCategoria(casa, "Casa");

            Cupom cupom = new Cupom();
            cupom.setCodigo(CUPOM);
            cupom.setValorDesconto(new BigDecimal("10.00"));
            cupom.setDataExpiracao(LocalDate.now().plusYears(1));
            cupom.setStatus(StatusCupom.ATIVO);
            cupomDAO.save(cupom);
        });

        Produto fone = novoProduto(lojaX, eletronicos, "Fone Bluetooth", "199.90", 20);
        Produto teclado = novoProduto(lojaX, eletronicos, "Teclado Mecânico", "349.90", 15);
        novoProduto(lojaX, casa, "Luminária", "89.90", 30);
        Produto romance = novoProduto(lojaY, livros, "Romance", "49.90", 40);
        Produto tecnico = novoProduto(lojaY, livros, "Livro Técnico", "129.90", 25);
        novoProduto(lojaY, casa, "Panela", "159.90", 10);

        // Pedidos cruzados: o da Ana tem produtos das duas lojas.
        Pedido pedidoAna = comprar(ana, CUPOM, fone, romance);
        Pedido pedidoBruno = comprar(bruno, null, teclado);
        Pedido pedidoCarla = comprar(carla, null, tecnico);

        pagar(ana, pedidoAna);
        pagar(bruno, pedidoBruno);
        pagar(carla, pedidoCarla);

        despachar(lojaX, pedidoAna, "SEED-RAST-1");
        despachar(lojaX, pedidoBruno, "SEED-RAST-2");
        despachar(lojaY, pedidoCarla, "SEED-RAST-3");

        solicitarDevolucao(ana, pedidoAna, "Produto diferente do anunciado");
        solicitarDevolucao(bruno, pedidoBruno, "Produto com defeito");

        cadastrarEndereco(ana, "João Pessoa");
        cadastrarEndereco(bruno, "Campina Grande");
        cadastrarEndereco(carla, "Patos");

        avaliar(ana, fone, 5);
        avaliar(bruno, fone, 4);

        // Compras em andamento: ficam itens no carrinho depois do checkout.
        adicionarAoCarrinho(ana, teclado);
        adicionarAoCarrinho(bruno, romance);

        return true;
    }

    private void preencherUsuario(Usuario usuario, String nome, String email) {
        usuario.setNome(nome);
        usuario.setEmail(email);
        usuario.setSenhaHash(SENHA_FICTICIA);
    }

    private void salvarCliente(Cliente cliente, String nome, String email, String telefone) {
        preencherUsuario(cliente, nome, email);
        cliente.setTelefone(telefone);
        clienteDAO.save(cliente);
    }

    private void salvarVendedor(Vendedor vendedor, String nome, String email, String cnpj) {
        preencherUsuario(vendedor, nome, email);
        vendedor.setRazaoSocial(nome + " LTDA");
        vendedor.setCnpjCpf(cnpj);
        vendedorDAO.save(vendedor);
    }

    private void salvarCategoria(Categoria categoria, String nome) {
        categoria.setNome(nome);
        categoriaDAO.save(categoria);
    }

    private Produto novoProduto(Vendedor vendedor, Categoria categoria, String nome, String preco, int estoque) {
        Produto produto = new Produto();
        produto.setNome(nome);
        produto.setPreco(new BigDecimal(preco));
        produto.setEstoque(estoque);
        produto.setIdVendedor(vendedor.getId());
        produto.setCategoria(categoria);
        JwtAuthenticationFilter.executeAuthenticated(vendedor.getId(), "VENDEDOR", () -> produtoDAO.save(produto));
        return produto;
    }

    private void adicionarAoCarrinho(Cliente cliente, Produto produto) {
        comoCliente(cliente, () -> {
            Carrinho carrinho = carrinhoDAO.criarParaCliente(cliente.getId());
            itemCarrinhoDAO.adicionarItem(new ItemCarrinho(carrinho.getId(), produto.getId(), 1, produto.getPreco()));
        });
    }

    private Pedido comprar(Cliente cliente, String cupom, Produto... produtos) {
        for (Produto produto : produtos) {
            adicionarAoCarrinho(cliente, produto);
        }
        return JwtAuthenticationFilter.executeAuthenticated(cliente.getId(), "CLIENTE", () -> pedidoDAO.checkout(cupom));
    }

    private void pagar(Cliente cliente, Pedido pedido) {
        Pagamento pagamento = new Pagamento();
        pagamento.setMetodo(MetodoPagamento.PIX);
        pagamento.setValorPago(pedido.getValorTotal());
        pagamento.setIdPedido(pedido.getId());
        comoCliente(cliente, () -> pagamentoDAO.save(pagamento));
    }

    private void despachar(Vendedor vendedor, Pedido pedido, String codigo) {
        Entrega entrega = new Entrega();
        entrega.setTransportadora("Correios");
        entrega.setCodigoRastreamento(codigo);
        entrega.setIdPedido(pedido.getId());
        JwtAuthenticationFilter.executeAuthenticated(vendedor.getId(), "VENDEDOR", () -> entregaDAO.save(entrega));
    }

    private void solicitarDevolucao(Cliente cliente, Pedido pedido, String motivo) {
        Devolucao devolucao = new Devolucao();
        devolucao.setMotivo(motivo);
        devolucao.setIdPedido(pedido.getId());
        comoCliente(cliente, () -> devolucaoDAO.save(devolucao));
    }

    private void cadastrarEndereco(Cliente cliente, String cidade) {
        Endereco endereco = new Endereco();
        endereco.setRua("Rua da Carga Inicial");
        endereco.setNumero("100");
        endereco.setCep("58000-000");
        endereco.setCidade(cidade);
        endereco.setEstado("PB");
        endereco.setIdCliente(cliente.getId());
        comoCliente(cliente, () -> enderecoDAO.save(endereco));
    }

    private void avaliar(Cliente cliente, Produto produto, int nota) {
        Avaliacao avaliacao = new Avaliacao();
        avaliacao.setNota(nota);
        avaliacao.setComentario("Avaliação da carga inicial");
        avaliacao.setIdCliente(cliente.getId());
        avaliacao.setIdProduto(produto.getId());
        comoCliente(cliente, () -> avaliacaoDAO.save(avaliacao));
    }

    private void comoCliente(Cliente cliente, Runnable acao) {
        JwtAuthenticationFilter.executeAuthenticated(cliente.getId(), "CLIENTE", acao);
    }
}
