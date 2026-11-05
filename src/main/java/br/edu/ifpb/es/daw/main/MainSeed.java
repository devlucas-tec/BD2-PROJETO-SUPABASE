package br.edu.ifpb.es.daw.main;

import br.edu.ifpb.es.daw.service.SeedService;

public class MainSeed {

    public static void main(String[] args) {
        boolean populou = new SeedService().popular();
        System.out.println(populou ? "Carga inicial concluída." : "Carga inicial já existia: nada foi alterado.");
    }
}
