# ADR-001: Monólito modular com Spring Modulith

- **Status:** Aceito
- **Data:** 2026-11-24

## Contexto

O domínio tem áreas com regras próprias (catálogo, estoque, reservas, compras, acesso) que trocam informação entre si, e as operações críticas — reserva, transferência, movimentação — exigem transações consistentes. O projeto tem um desenvolvedor e precisa de fronteiras claras sem o custo operacional de vários serviços.

## Opções consideradas

1. **Monólito em camadas** — simples de começar / fronteiras de domínio não existem, o acoplamento cresce sem que nada acuse.
2. **Microsserviços** — isolamento e deploy independentes / transações distribuídas (sagas) onde hoje bastaria uma transação local, infraestrutura e observabilidade multiplicadas para um time de uma pessoa.
3. **Monólito modular com Spring Modulith** — um deploy e um banco, transações locais; módulos com API pública e eventos, fronteiras verificadas em teste / disciplina para não vazar entidades entre módulos.

## Decisão

Opção 3. Cada módulo é um pacote direto de `io.github.abreulucass.atlas` (D02). Um módulo só acessa outro pela API pública dele (tipos no pacote raiz do módulo ou em pacotes marcados como `@NamedInterface`) ou reagindo a eventos de domínio publicados por ele. Entidades JPA e repositórios são internos ao módulo; não há chave estrangeira de entidade entre módulos, só o identificador. Eventos entre módulos usam o registro de publicações do Spring Modulith (`event_publication`) para não se perderem se o listener falhar.

## Consequências

- **Positivas:** transações locais nas operações de estoque; um módulo pode virar serviço depois, porque já fala só por API e eventos; o teste acusa dependência indevida.
- **Negativas:** consultas que cruzam módulos passam pela API ou por projeções alimentadas por eventos, não por join; eventos assíncronos trazem consistência eventual entre módulos.
- **Verificação:** teste `ApplicationModules.of(AtlasApplication.class).verify()` no build; falha com ciclo entre módulos ou acesso a tipo interno de outro módulo. A documentação dos módulos é gerada pelo `Documenter` do Spring Modulith.
