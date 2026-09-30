# E02 MCP: entrega e validação

Base: main `0c369a17bcd6b5d733865140c2a5cf849253b61c`.
Validação: 2026-09-29, Java 21.0.12, Maven 3.9.11, PostgreSQL 16.15, Node 24.

## Fluxo e operação

PHYSICAL_COUNT confirmada divergente → E02 OPEN → fatos/evidências PEEK e
interpretação JEV separada → Codex explica a aceitação do checkpoint existente
e pede autorização explícita → humano aprova → peek_apply_e02_reconciliation
→ AgentActionExecution SUCCEEDED/PENDING_VERIFICATION → EvaluationService
verifica em outra transação → RECONCILIATION + RESOLVED/VERIFIED.

Na negativa o cliente não chama tool de escrita; a exceção continua OPEN e não
há ação, evento ou comando novo. A detecção e o reanchoring pré-existentes de
StockCalculator foram preservados. A ação aceita a contagem confirmada como
checkpoint de reconciliação; não ajusta o estoque de um ERP/WMS externo.

## Aprovação, idempotência e prova

Schema fechado: exceptionId, idempotencyKey, humanApproved=true, approvalNote,
decisionFingerprint. A fingerprint vem do contexto factual revisado e cobre
versão da exceção, tolerância e eventos do SKU. Estado alterado exige nova revisão.
Approval é auditado no inputSummary da infraestrutura existente. Lock da exceção
e índice único impedem duplicar a decisão, inclusive com chaves diferentes ou
chamadas concorrentes. Replay com mesma chave/fingerprint retorna o mesmo registro.

A V6 permite auditoria de checkpoint sem Command externo e referencia a contagem
existente. Apenas a avaliação posterior confirma checkpoint vigente, evidências
inalteradas, reconstrução coerente e prazo de 60 segundos (relógio real e asOf).
Ela preserva evidências anteriores, acrescenta RECONCILIATION e grava prova com
countId/reconciledAt. Resolução manual, mudança de evidências ou timeout não provam
recuperação. Avaliações concorrentes relêem a versão depois do lock e não duplicam prova.

JEV usa uma projeção opcional do contrato JevAnalysis já declarado no frontend.
Hipóteses e confiança são saída do modelo, não fatos. Referências fictícias são
filtradas. Não há provedor JEV implementado na main: o caminho normal é UNAVAILABLE
com recomendação estática. Falhas/ausência de análise não impedem o domínio.

## Resultados

| Verificação | Resultado |
| --- | --- |
| mvn clean test | BUILD SUCCESS; 25 unitários, zero falhas/erros/skip |
| mvn verify, banco PostgreSQL novo | BUILD SUCCESS; 25 unitários + 53 integração, zero falhas/erros/skip |
| Flyway | V1–V6 aplicadas em banco novo; rerun sem migration adicional |
| npm run format:check | Passou |
| npm run lint | TypeScript estrito passou |
| npm test | 13 passaram |
| npm run test:fixtures | 1 passou |
| npm run build | Build Next.js passou |
| npm run test:e2e | 3 passaram, com API real e relógio controlado |
| node --check, demos E01/E02 | Passou |
| Demo E02 prepare/deny, backend real | Expected 95, physical 93, delta -2, tolerance 0; OPEN, nenhuma agentAction |

Total de testes distintos: **95**. São novos 19 testes de integração E02 e
3 unitários JEV. A suíte existente E01, autenticação/loopback/origin, E03/E04,
produto/propagação, reset/replay e frontend permaneceu passando.

Os testes E02 cobrem detecção/lista/contexto, fatos pré-contagem vs checkpoint,
JEV indisponível/hipótese, aprovação ausente/falsa/inválida, schema fechado,
família errada/ID ausente/resolvida, dados físicos inválidos, auditoria, replay,
concorrência (mesma chave, chaves diferentes e avaliações), pendência sem fechamento,
prova posterior/evidências preservadas, contexto/configuração alterados, checkpoint
superado, movimento tardio, resolução manual e timeout sem bypass por asOf retroativo.

## Arquivos alterados

- AGENTS.md
- backend/src/main/java/io/peek/core/exceptions/ExceptionRepository.java
- backend/src/main/java/io/peek/core/exceptions/ExceptionService.java
- backend/src/main/java/io/peek/core/exceptions/JevContextService.java
- backend/src/main/java/io/peek/core/mcp/AgentActionExecution.java
- backend/src/main/java/io/peek/core/mcp/AgentActionRepository.java
- backend/src/main/java/io/peek/core/mcp/AgentActionService.java
- backend/src/main/java/io/peek/core/mcp/McpController.java
- backend/src/main/java/io/peek/core/mcp/McpToolCatalog.java
- backend/src/main/java/io/peek/core/mcp/PeekAgentTools.java
- backend/src/main/java/io/peek/core/reconciliation/E02ReconciliationService.java
- backend/src/main/java/io/peek/core/reconciliation/EvaluationService.java
- backend/src/main/resources/db/migration/V6__e02_checkpoint_approval.sql
- backend/src/test/java/io/peek/core/BackendCoreIT.java
- backend/src/test/java/io/peek/core/E02AgentIT.java
- backend/src/test/java/io/peek/core/exceptions/JevContextServiceTest.java
- backend/src/test/java/io/peek/core/mcp/AgentActionServiceTest.java
- backend/src/test/java/io/peek/core/mcp/McpProtocolTest.java
- docs/architecture.md
- docs/e02_agent_validation.md
- docs/exception_engine.md
- docs/mcp_agent.md
- docs/product_scope.md
- scripts/mcp-e02-demo.mjs

## Limites

O servidor local registra a declaração do cliente autenticado e não verifica
independentemente a conversa humana. Codex deve pedir e receber autorização real
por ocorrência; autorização para merge não autoriza um incidente E02.
Não há ajuste externo, novo LLM, workflow empresarial ou UI de aprovação própria.
O MCP continua local, autenticado e desabilitado por padrão; E03/E04 são read-only.
O driver prepare/deny foi executado; o positivo foi validado via MCP HTTP nos
testes de integração. Uma sessão real com o Codex local segue docs/mcp_agent.md.
