# PEEKio · mock do frontend

Protótipo navegável do MVP em Next.js, TypeScript e Tailwind CSS, com componentes e CSS de identidade visual próprios. Os dados são fictícios e as ações são simuladas localmente no navegador; ainda não existe integração com o backend.

## Rodar

```bash
npm install
npm run dev
```

Abra `http://localhost:3000/dashboard`. Para validar: `npm run lint` e `npm run build`.

## Telas

- Visão geral operacional e exceções priorizadas
- Produtos, detalhe da identidade, mapeamentos e criação/vínculo simulados
- Pedidos e progressão operacional
- Estoque e separação entre esperado, ERP, físico e canais
- Fiscal e confirmação do adapter simulado
- Central de Exceções e investigação E01–E04 com evidências antes da análise JEV
- Demo de cenários com etapas reproduzíveis

Reprocessamento e resolução exibem estados e mensagens explícitos. Resolução exige anotação. O estado do mock é guardado em `localStorage` (`peekio-mock-v1`); para recomeçar, limpe os dados do site. Valores e datas da demonstração não representam operações reais.

Este pacote contém apenas `frontend/` para ser adicionado à raiz do repositório. Nenhum arquivo do repositório remoto foi modificado.
