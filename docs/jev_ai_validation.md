# Milestone Jev / AI — relatório local de implementação e validação

> O registro mais recente da integração JEV + Agent, incluindo a correção do
> roteiro, a formatação global e o cliente Codex real, está em
> [jev_agent_validation.md](jev_agent_validation.md). Os resultados e pendências
> das rodadas abaixo são históricos.

Data: 2026-09-30. Repositório: joaa0/PEEK. Base local: main, 51be806.

## Resultado

A milestone **Jev / AI está pronta para revisão**. As issues
[#16](https://github.com/joaa0/PEEK/issues/16) e
[#15](https://github.com/joaa0/PEEK/issues/15) estão tecnicamente aptas a serem
fechadas após revisão. Ambas permanecem abertas no GitHub.

Nenhum commit, push, merge, PR ou alteração de issue/milestone/label foi realizado.
As mudanças permanecem apenas no working tree, sem staging. Nenhuma credencial
real foi incluída e nenhuma API externa de LLM foi chamada durante os testes.

## Leitura prévia e dependências

Antes de editar arquivos foram lidos README, AGENTS.md, as oito referências
obrigatórias do produto, backend_api.md, mcp_agent.md, e02_agent_validation.md,
frontend_validation.md e frontend/README.md. As duas issues da milestone e suas
dependências foram consultadas; #16 e #15 não tinham comentários adicionais.

A inspeção de código confirmou, independentemente do status no GitHub:

- #4: fixtures traduzidas pelos mock adapters, datasets fictícios e reset local,
  com cobertura em MockAdapterFixtureTest e DemoResetIT;
- #20: E01 por comandos/tentativas, prazo e confirmação correlacionada,
  com cobertura em IntegrationEventEngineIT;
- #17: E04 por recebimento e atualização de estoque correlacionados por
  receiptId + SKU e tolerância configurada, no mesmo motor e suíte;
- E02: comparação determinística do saldo anterior à contagem física,
  registrada em EvaluationService, com cálculo em StockCalculator.

A ordem aplicada foi contrato/schema e testes da #16, depois cliente,
fallback, exposição e testes da #15. Não foram alterados os motores de
reconciliação, eventos, commands, adapters, banco ou aprovação humana.

## Implementação da #16

Contrato versionado 1.0 e independente de fornecedor/modelo, com JSON Schemas
2020-12 executáveis. A entrada distingue observações, cálculos do PEEK,
configuração, estados e histórico recente. A projeção usa somente evidências
originais, aliases locais e campos numéricos/tipados permitidos; não envia
payloads, metadata, IDs de negócio, nomes, SKU, UUIDs ou timestamps absolutos.

A saída exige resumo, hipótese principal, justificativa, referências existentes,
impacto e ação. A confiança é MODEL_SELF_REPORTED_RANKING, não probabilidade
estatística calibrada. Até duas alternativas precisam ter suporte contextual,
códigos distintos e ranking não crescente. Schema inválido, campos adicionais,
referências inventadas, JSON duplicado/truncado e hipóteses sem o tipo de evento
necessário são rejeitados. Validação de referências não certifica a veracidade
de texto natural; toda interpretação continua identificada como hipótese.

| Critério de aceite | Atendimento / verificação |
| --- | --- |
| Distinguir fatos, cálculos e hipóteses | Input.facts, Input.calculations e ModelOutput.nature; JevContractTest |
| Hipótese principal justificada nas evidências | mainHypothesis.rationale e evidenceIds; validação contextual |
| Evitar listas genéricas | Enum de hipóteses, máximo duas alternativas, suporte e ranking obrigatórios |
| Confiança não é probabilidade comprovada | Campo meaning obrigatório e aviso explícito na UI/MCP |
| Não enviar payload irrestrito/dados reais | Allowlist, limites, aliases e elegibilidade exclusiva de fixtures fictícias |
| E02 determinística como referência | Motor existente intacto; detecção/lista/detalhe sem chamadas LLM |
| Independência de fornecedor/modelo | Contrato sem SDK; LlmClient SPI; transporte/modelo configuráveis |
| Frontend renderiza diretamente | Investigation.jev tipado e renderizado sem chamar o LLM no frontend |

Todos os oito critérios foram atendidos, com as condições de segurança da demo
documentadas em jev_contract.md.

## Implementação da #15

O módulo intelligence contém serviço de enriquecimento somente de leitura,
cliente HTTP TypeSafe System One e validação local obrigatória.
Variáveis de ambiente controlam habilitação, confirmação de dados fictícios,
endpoint, modelo Jev, chave TypeSafe e prazos. A integração vem desativada.
O protocolo usa state/questions e respostas choice/probabilities/confidence;
PEEK compõe os textos explicativos a partir das evidências. O modo de schema
de Chat Completions foi removido.

Uma chamada elegível exige perfil demo, opt-in explícito, produto do namespace
Demo/QA e origem nos mock adapters. Não existe suporte ao envio de dados reais.
Essas marcações não são uma prova automática de anonimização: o operador não
pode rotular dados reais como fixtures.

O cliente aplica prazo total inclusive ao corpo, limite de resposta, HTTPS fora
de loopback restrito à TypeSafe, nenhum redirect/retry automático e nenhuma ferramenta.
Falha, timeout, recusa, resposta inválida ou contexto inelegível retornam
explicação/recomendação determinísticas, sem confiança fabricada. REST preserva
o contexto factual e acrescenta jev AVAILABLE/FALLBACK; MCP mantém compatibilidade
com AVAILABLE/UNAVAILABLE. Nenhuma interpretação persiste eventos, substitui
evidências, aprova checkpoint ou resolve E02.

| Critério de aceite | Atendimento / verificação |
| --- | --- |
| E02 aparece sem LLM ou com resposta inválida | JevServiceTest, JevIntegrationIT e navegador com LLM desativado |
| Sem fabricar eventos/substituir evidências | Schema fechado, aliases validados e comparação das evidências persistidas |
| Mockar sucesso, inválida e falha | HttpLlmClientTest, JevServiceTest e JevIntegrationIT; também timeout e corpo travado |
| Nenhuma credencial/dado real no código | Chave vazia via ambiente; fixtures fictícias; testes de não exposição e inspeção local |

Todos os quatro critérios foram atendidos. A opção de habilitar um fornecedor
real exige configuração pelo operador, não mudança de código.

## Arquivos criados (18)

Produção, em backend/src/main/java/io/peek/core/intelligence/:

- JevContract.java
- JevInputFactory.java
- JevResponseValidator.java
- LlmProperties.java
- LlmClient.java
- HttpLlmClient.java
- JevService.java
- JevAnalysisSource.java

Schemas:

- backend/src/main/resources/intelligence/jev-input.schema.json
- backend/src/main/resources/intelligence/jev-output.schema.json

Testes:

- backend/src/test/java/io/peek/core/intelligence/JevFixtures.java
- backend/src/test/java/io/peek/core/intelligence/JevContractTest.java
- backend/src/test/java/io/peek/core/intelligence/HttpLlmClientTest.java
- backend/src/test/java/io/peek/core/intelligence/JevServiceTest.java
- backend/src/test/java/io/peek/core/intelligence/JevAnalysisSourceTest.java
- backend/src/test/java/io/peek/core/JevIntegrationIT.java

Documentação:

- docs/jev_contract.md
- docs/jev_ai_validation.md

## Arquivos alterados (14)

- .env.example — variáveis LLM sem secrets;
- README.md — contrato e ativação/fallback;
- backend/pom.xml — validador JSON Schema;
- backend/src/main/resources/application.yml — configuração por ambiente;
- backend/src/main/java/io/peek/core/presentation/InvestigationController.java — contexto factual preservado + jev;
- frontend/src/lib/contracts.ts — tipos estruturados opcionais;
- frontend/src/components/Investigation.tsx — hipótese/justificativa/confiança/fallback;
- frontend/src/components/App.test.tsx — dois testes da nova projeção;
- frontend/README.md — contrato atual;
- docs/architecture.md — fronteira de inteligência e fluxo de consulta;
- docs/backend_api.md — projeção REST e fallback;
- docs/exception_engine.md — contrato rankeado em lugar da lista genérica;
- docs/technical_decisions.md — contrato, transporte e decisões de segurança;
- docs/mcp_agent.md — fonte JEV e compatibilidade, sem ampliar autoridade.

## Testes executados e resultados

Esta seção registra a validação inicial da implementação. A revalidação após
os dois achados está na seção "Correções após revisão", abaixo.

| Comando / suíte | Resultado inicial |
| --- | --- |
| backend: mvn -B test, antes de editar | 25 testes existentes passaram |
| backend: mvn -B -Dtest=JevContractTest test, etapa #16 | 6 testes iniciais passaram |
| backend: mvn -B test, etapa #15 | 47 unitários passaram |
| backend: mvn -B verify | 47 unitários + 60 integrações passaram; JAR gerado |
| backend: mvn -B clean verify, validação final | **50 unitários + 60 integrações passaram**, 0 falhas/erros/ignorados; BUILD SUCCESS |
| frontend: npm ci --no-audit --no-fund | Concluiu sem mudar package.json/package-lock.json |
| frontend: npm run lint | TypeScript passou |
| frontend: npm test | **15 testes passaram** |
| frontend: npm run test:fixtures | **1 teste passou** |
| frontend: npm run build | Build completo Next.js passou |
| frontend: npm run test:e2e | **3 testes de navegador passaram**, sequenciais, backend/DB locais e LLM desativado |
| frontend: npm run format:check | Falhou em **23 arquivos preexistentes não alterados**; divergências de estilo/line endings |
| prettier --check nos três arquivos TS/TSX alterados | Passou |
| git diff --check | Passou |
| Inspeção dos arquivos alterados/criados para padrões de tokens/chaves privadas | Nenhuma ocorrência; configurações LLM sem credenciais reais |
| git rev-parse / git diff --cached --quiet | HEAD 51be806 preservado; nenhum staging |

Detalhamento do backend naquela validação:

- JevContractTest: 7/7 (estrutura, fatos/cálculos, privacidade, entrada inválida,
  versão, schema de saída, hipóteses/evidências, alternativas rankeadas);
- HttpLlmClientTest: 8/8 (sucesso HTTP mockado, schema opt-in, recusas/JSON
  inválido, erros, timeout, tamanho máximo, corpo travado, configuração/redação);
- JevServiceTest: 8/8 (sucesso, sem LLM, opt-in, origem fictícia, falhas,
  contexto incompleto, família E02 e evidências imutáveis);
- JevAnalysisSourceTest: 2/2 (compatibilidade MCP, justificativa, confiança,
  referências e ausência de análise fabricada no fallback);
- JevIntegrationIT: 7/7 (REST real + LLM HTTP mockado + PostgreSQL,
  detecção independente, inválida/falha/timeout, evidências e snapshot original);
- EventValidationTest 3/3; JevContextServiceTest 3/3;
  MockAdapterFixtureTest 2/2; AgentActionServiceTest 5/5;
  McpProtocolTest 7/7; StockCalculatorTest 3/3; CorrelationPolicyTest 2/2;
- BackendCoreIT 7/7; DemoResetIT 2/2; E02AgentIT 19/19;
  IntegrationEventEngineIT 10/10; McpAgentIT 10/10; ProductPropagationIT 5/5.

Na implementação inicial foram adicionados **32 testes backend** e **2 frontend**. Todas as suítes finais
de testes automatizados passaram; a ressalva é a checagem global de formatação,
não um teste funcional. Nenhum teste solicitado deixou de ser executado.

Nas tentativas intermediárias, um mock que lançava exceção precisou usar
doThrow na reconfiguração; foi corrigido e todas as suítes foram repetidas.
Um clean inicialmente encontrou o JAR aberto pelo backend de navegador; esse
processo foi encerrado e clean verify repetido com sucesso. Maven/npm precisaram
de execução autorizada fora do sandbox para seus caches, sem mudança de secrets.

## Ambiente e reprodução

Java 23.0.1 compilando para release 21, Maven 3.9.9, Node 24.13.1,
PostgreSQL nativo 18.4 em loopback:55432 e Chromium/Playwright existentes.
Docker daemon não estava disponível; não impediu as integrações, executadas em
bancos fictícios separados peek_jev_test_20260930 e peek_jev_browser_20260930.
Os processos backend/frontend usados pelos testes e o cluster PostgreSQL
iniciado para esta validação foram encerrados. Os dois bancos fictícios foram
preservados localmente; nenhum dado material foi removido.

Para repetir o backend em PowerShell, a partir de backend/:

```powershell
$env:PEEK_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/peek_jev_test_20260930'
$env:PEEK_TEST_DB_USER='Pichau'
$env:PEEK_TEST_DB_PASSWORD=''
mvn.cmd -B clean verify
```

Adapte URL/usuário/senha ao seu banco de testes isolado. Não use banco de
produção. As integrações LLM abrem servidores HTTP somente em loopback.

Frontend: npm run lint, npm test, npm run test:fixtures, npm run build e
npm run test:e2e. Build e navegador devem ser executados sequencialmente.
O navegador requer backend demo local com relógio fixo
2026-01-01T12:00:00Z, banco isolado e PEEK_LLM_ENABLED=false.

O ambiente emitiu avisos não bloqueantes: PostgreSQL 18.4 acima da versão
validada pelo Flyway existente; algumas dependências de teste recomendam
Node 24.15+/22.22.2+; warnings existentes de Mockito/depreciação. Não foram
atualizadas dependências não relacionadas à milestone para suprimir avisos.

## Inconsistências e pendências

1. Dependência circular #16 ↔ #4 e #15 ↔ #20. A implementação existente das
   dependências foi verificada e reutilizada; nenhum conteúdo remoto foi alterado.
2. Documentação anterior exemplificava probable_causes como lista genérica.
   Os documentos vigentes foram alinhados com o contrato rankeado. Relatórios
   históricos de validação anteriores à integração continuam históricos.
3. A projeção legada UI/MCP é plana e usa probableCauses/UNAVAILABLE.
   Foi preservada por compatibilidade; REST/frontend novos usam o contrato
   estruturado AVAILABLE/FALLBACK. Não é uma segunda autoridade factual.
4. format:check global permanece com 23 arquivos anteriores fora do escopo.
   Não houve reformatação massiva. Todos os arquivos de frontend alterados passam.
5. Não foi feita chamada paga/real a fornecedor, por ausência de credencial e
   por uso de mocks. Smoke test de um endpoint/modelo escolhido é opcional de
   configuração; só pode usar fixtures fictícias e secrets no ambiente.

Não há pendência funcional da #16 ou #15 identificada na revisão local.
Configuração de fornecedor e revisão humana são próximos passos normais;
nenhuma issue foi fechada e nenhuma alteração foi publicada.

## Correções após revisão — 2026-09-30

Os dois achados foram reproduzidos por testes que falharam antes da respectiva
correção e passaram depois. A ordem de correção foi #16, depois #15.

### #16 — Ordem do histórico coerente com o motor

JevInputFactory agora desempata eventos por `receivedAt` antes de `id`, após
ordenar por `occurredAt`, exatamente como StockCalculator e EvaluationService.
A projeção não envia o timestamp de recebimento; os fatos e cálculos originais
continuam preservados. O motor determinístico não foi alterado.

JevContractTest inclui recebimento e venda com o mesmo horário de ocorrência,
horários de recebimento distintos e UUIDs que induziam a ordem inversa. Também
valida o desempate final por UUID quando os horários de recebimento coincidem.
Compara a sequência projetada com os eventos usados pelo cálculo determinístico,
confere o saldo esperado, valida o schema e verifica a preservação das evidências.

### #15 — Schema de transporte compatível, validação local integral

HttpLlmClient projeta uma cópia do schema para `response_format.json_schema`,
sem `uniqueItems`, limites de comprimento de texto ou anotações `$schema`, `$id`
e `title`. Constantes textuais são enums unitários tipados; enums textuais
ganham tipo explícito. O schema canônico completo permanece no prompt e no
JevResponseValidator, que não foi alterado. JSON mode permanece inalterado.

A skill OpenAI Docs orientou a separação entre contrato local e subconjunto
estrito de transporte, a partir dos guias oficiais de
[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) e
[geração de schemas](https://developers.openai.com/api/docs/guides/prompt-generation).
O modelo configurado ainda precisa suportar o subconjunto enviado; não se
declara compatibilidade universal com fornecedores ou modelos fine-tuned.

O mock de HttpLlmClientTest agora responde HTTP 400 para palavras-chave fora
do subconjunto aceito ou literais sem tipo. O teste de sucesso estrito comprova
que a projeção é aceita e que o schema canônico e o prompt permanecem intactos.
Uma nova regressão confirma que respostas com evidências duplicadas, textos
vazios/longos, referência desconhecida, campos adicionais e ranking inválido
continuam sendo rejeitadas pelo validador local após a chamada mockada.

### Arquivos desta rodada

Nenhum arquivo novo foi criado nesta correção. Foram editados somente:

- backend/src/main/java/io/peek/core/intelligence/JevInputFactory.java;
- backend/src/main/java/io/peek/core/intelligence/HttpLlmClient.java;
- backend/src/test/java/io/peek/core/intelligence/JevContractTest.java;
- backend/src/test/java/io/peek/core/intelligence/HttpLlmClientTest.java;
- docs/jev_contract.md;
- docs/jev_ai_validation.md.

As demais mudanças locais da milestone foram preservadas, sem refatoração,
migração, mudança de frontend, motor de reconciliação ou aprovação humana.

### Revalidação final

| Comando / suíte | Resultado nesta rodada |
| --- | --- |
| mvn.cmd -B -Dtest=JevContractTest test | 8/8 passaram após a correção #16 |
| mvn.cmd -B -Dtest=JevContractTest,HttpLlmClientTest test | 17/17 passaram após ambas as correções |
| mvn.cmd -B clean verify | **52 unitários + 60 integrações passaram**, zero falhas/erros/ignorados; BUILD SUCCESS e JAR completo |
| npm.cmd test | **15/15 passaram** |
| npm.cmd run test:fixtures | **1/1 passou** |
| npm.cmd run lint | Passou |
| git diff --check | Passou |
| Inspeção dos arquivos JEV/configuração/frontend para padrões de secrets | Nenhuma ocorrência de chave/token real ou chave privada |
| git rev-parse HEAD / git diff --cached --quiet | HEAD 51be806 preservado; nenhum staging |

Foram acrescentados dois testes automatizados, além do fortalecimento do teste
HTTP estrito existente: o backend tem agora 34 testes adicionados pela milestone.
JevContractTest passou 8/8; HttpLlmClientTest, 9/9. As 60 integrações incluem
detecção de E02 sem LLM, fallback, timeout/falha e preservação das evidências.
Todos os testes desta rodada usaram fixtures fictícias e LLM mockado em loopback.

O banco isolado peek_jev_test_20260930 foi reutilizado; o cluster iniciado para
os testes foi encerrado ao final, sem remover bancos ou dados materiais.
Build e navegador do frontend não foram repetidos nesta rodada, pois nenhum
arquivo de frontend foi alterado; os resultados anteriores continuam registrados
acima. Não houve teste desta correção impedido por limitação do ambiente.
A ressalva preexistente de format:check global permanece fora do escopo.

### Situação após as correções

Não resta pendência funcional identificada nos dois achados. E02 continua
determinística e independente do LLM; o fallback e a evidência original permanecem.
Configurar/testar um fornecedor real com fixtures fictícias é uma etapa opcional
do operador, não executada nesta rodada. Nenhum commit, push, merge, PR ou
alteração remota foi realizado; as issues continuam abertas.

**A milestone Jev / AI está pronta para revisão? Sim.**

- **#16:** tecnicamente apta a ser fechada após revisão humana.
- **#15:** tecnicamente apta a ser fechada após revisão humana.
