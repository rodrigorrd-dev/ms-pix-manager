# Política de segurança

Este serviço lida com dados pessoais (CPF/CNPJ, chaves Pix) e fluxo financeiro.

- **Reporte vulnerabilidades em privado**: aba *Security* → *Report a vulnerability* do repositório. Não abra issue pública nem cole dados reais.
- Informe versão/commit, passos para reproduzir e impacto. Não inclua PII real.
- Meta de resposta: confirmação em até 2 dias úteis; correção priorizada por severidade (CVSS).
- Só a versão mais recente (`main` e a última tag `v*`) recebe correções de segurança.
- Segredos nunca entram no repositório: `.env` é local; hml/prd usam cofre (veja `subida-producao.md`).
