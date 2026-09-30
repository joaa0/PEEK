# Validação do shell e Fiscal — issues #52 e #55

Base remota verificada: `51be8061f0d7c0030bc62b266c9cd4915e39a455` (main).
Alterações preparadas localmente, sem commit, push, PR, merge ou fechamento de issues.

## Entrega

- Branding PEEK na sidebar, breadcrumbs, metadados e contexto de estoque.
- Símbolo oficial rosa com fundo removido e arquivo PNG com alpha.
- Sidebar: `/overview`, `/products`, `/inventory`, `/fiscal`, `/exceptions`, `/simulation`.
- Estado ativo por rota, inclusive detalhes; histórico voltar/avançar e recarregamento.
- Investigação preservada em `/exceptions/:id`, sem entrada independente na sidebar.
- Fiscal somente consulta: pendente, confirmado, falhou e timeout, com cores, ícones e rótulos distintos.
- Exemplos claramente identificados, tentativas, prazo, confirmação recebida e documento externo.
- Registros E03 existentes consultados na API e seus comandos fiscais vinculados, com acesso à investigação real.
- Estados vazio/erro e retry de leitura; erros de comando não ocultam a exceção correspondente.
- Nenhuma implementação de emissão fiscal, SEFAZ, regras tributárias ou mutação fiscal.

Visão geral, Estoque e Simulação possuem somente a superfície de navegação e uma
tela explícita de preparação. Sua implementação funcional continua nas issues
#53, #54 e #56. A consulta fiscal real cobre as exceções E03 e os comandos ligados
a elas; não é uma listagem de todos os comandos fiscais.

## Verificação executada

| Verificação | Resultado |
| --- | --- |
| Prettier | Aprovado |
| TypeScript (`npm run lint`) | Aprovado |
| Vitest | 22 testes aprovados (13 existentes + 9 novos) |
| API de fixtures | 1 teste aprovado |
| Next.js produção | Build aprovado |
| Playwright `e2e/shell-fiscal.spec.ts` | 3 testes aprovados |
| Visual | Desktop 1440px e mobile 390px; sem overflow da página; tabelas com rolagem interna |
| Logo | PNG RGBA; alpha transparente validado também no navegador |
| Diff | Sem erros de whitespace |

Os testes de navegador desta entrega usam interceptação HTTP explícita com dados
fictícios. O backend não foi alterado. A suíte existente com Spring Boot/PostgreSQL
não foi reexecutada nesta entrega; não se reivindica nova validação de integração
com o backend real.

```sh
cd frontend
npm run format:check
npm run lint
npm test
npm run test:fixtures
npm run build
npx playwright test e2e/shell-fiscal.spec.ts
```

Build e navegador devem ser executados sequencialmente. Antes do futuro commit,
comparar novamente a main com a base acima e revalidar se houver mudanças concorrentes.
