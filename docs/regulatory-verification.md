# Verificação dos valores regulatórios (`pix.policy.*`)

**Para quê:** `docs/09` §4b marcava vários valores como "informados pelo solicitante, não conferidos". Esta página cruza cada parâmetro do código com fontes públicas do Banco Central consultadas em **2026-10-16** (pesquisa na web). **Não substitui o parecer do compliance:** as fontes abaixo incluem imprensa e páginas institucionais, não o texto vigente integral das resoluções, e normas do Pix mudam com frequência (o Manual Operacional do DICT já está na versão 8.5; IN BCB 722/2026 e 766/2026 são recentes).

Legenda: ✅ confirmado em fonte oficial/institucional · 🟡 confirmado só em imprensa ou fonte datada (confirmar o texto da norma) · ❌ não encontrei confirmação · 🏢 não é regra do BCB (definido pela instituição ou pelo projeto).

| Parâmetro (`pix.policy.…`) | Valor no código | Situação | Fonte / observação |
|---|---|---|---|
| `limits.night-individual` / janela `night-start`–`night-end` | R$ 1.000 entre 20h e 6h (só PF) | ✅ | [BCB — Relatório de Gestão do Pix 2023](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/relatorio_de_gestao_pix/relatorio_gestao_pix_2023.pdf): limite padrão de R$ 1.000, 20h–6h, entre pessoas físicas; início customizável para 22h é **facultativo**; [gov.br 2021](https://www.gov.br/pt-br/noticias/financas-impostos-e-gestao-publica/2021/08/pix-tera-novas-regras-para-aumentar-a-seguranca-dos-usuarios) |
| `limits.increase-delay-min/max` | aumento de limite leva 24–48 h; redução é imediata | ✅ | [gov.br 2022](https://www.gov.br/pt-br/noticias/financas-impostos-e-gestao-publica/2022/12/banco-central-altera-regras-de-limites-das-operacoes-via-pix) |
| `limits.unregistered-device-per-tx` / `-daily` | R$ 200 por transação e R$ 1.000/dia em dispositivo **não cadastrado** | 🟡 | Resolução BCB nº 403, em vigor desde 01/11/2024, segundo [Diário de Pernambuco](https://www.diariodepernambuco.com.br/noticia/economia/2024/07/banco-central-faz-ajustes-para-aperfeicoar-seguranca-do-pix.html) e [Tecnoblog](https://tecnoblog.net/noticias/bc-impoe-novas-regras-de-seguranca-para-usar-pix-no-celular/). Confirmar vigência em 2026 |
| `limits.default-daily-individual/business`, `ceiling-individual/business` | R$ 5.000 / R$ 50.000 por dia; tetos R$ 50.000 / R$ 500.000 | 🏢 | Não encontrei esses valores como regra do BCB. São **padrões do projeto**: cada instituição define seus limites. Decisão de risco/produto |
| `refund.window` | 90 dias da transação original | ✅ | [BCB — Guia do MED](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/Guia_MED.pdf) (devolução por iniciativa do usuário em até 90 dias) |
| `med.open-window` / `contestation-window` | 80 dias | ✅ | [FAQ Participantes do Pix](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/FAQ_Participantes.pdf): notificação de infração para transações de até 80 dias; também [Procon-CE](https://www.proconceara.ce.gov.br/2026/01/30/saiba-como-pedir-a-devolucao-do-pix-em-caso-de-golpes-pelo-mecanismo-especial-de-devolucao-med/) |
| `med.analysis` | 7 dias por etapa de análise | 🟡 | Guia do MED (7 dias por PSP na cadeia; PSP do pagador tem até 72 h para solicitar a devolução — **o código não modela as 72 h**). Confirmar no texto vigente |
| `med.tracking-window` | 90 dias | ❌ | Não encontrei a origem deste valor. Verificar no Guia do MED / Regulamento |
| `scheduling.retry-start` / `-end` | nova tentativa entre 18h e 21h do mesmo dia | 🟡 | Pix Agendado: 1ª liquidação 0h–8h; sem saldo, avisar e tentar de novo 18h–21h ([Correio Braziliense](https://www.correiobraziliense.com.br/economia/2024/10/6975068-pix-agendado-passa-a-ser-obrigatorio-veja-como-vai-funcionar.html), [TNH1](https://www.tnh1.com.br/noticia/nid/pix-agendado-passa-a-ser-obrigatorio-a-partir-desta-segunda-feira-entenda-como-funciona/)). Datas 29–31 inexistentes ⇒ próximo dia útil |
| `keys.claimer-completion` | reivindicador cancela se a chave não for validada até o 30º dia | ✅ | [Manual Operacional do DICT v8.5](https://www.bcb.gov.br/content/estabilidadefinanceira/pix/Regulamento_Pix/versoes_futuras/X_ManualOperacionaldoDICT-versao8-5.pdf) (30º dia). Há indício de que a reivindicação de posse vale **só para chave de celular** — conferir se o código restringe |
| `keys.donor-response` | 7 dias | ❌ | Não encontrei a duração do período de resolução do doador. Verificar no Manual Operacional do DICT / Manual de Tempos ([IX — Manual de Tempos do Pix](https://liftchallenge.bcb.gov.br/content/estabilidadefinanceira/pix/Regulamento_Pix/IX_ManualdeTemposdoPix.pdf)) |
| `keys.max-per-account-individual/business` | PF 5 / PJ 20 chaves **por conta** | ❌ | Não confirmado nesta pesquisa (é regra do Manual Operacional do DICT; verificar o texto). O ms recusa a sexta chave de PF com `PIX-422-011` |
| `charge` (expiração padrão 86400 s) | 24 h | ✅ | Valor `default` do campo `calendario.expiracao` na especificação oficial da API Pix (`docs/api/bcb/api-pix-openapi-2.10.0.yaml`) |
| `webhook.*` (8 tentativas, 5 s a 6 h) | backoff de entrega | 🏢 | Parâmetros do projeto (a spec do BCB exige reentrega, não fixa estes números) |
| `otp.*` (5 min, 5 tentativas, 60 s, 10/dia) | prova de posse | 🏢 | Parâmetros **de projeto** (BR-KEY-04); ajustar com o time de segurança |
| `schedule.max-horizon-days` (730), `max-late-days` (3), `max-technical-attempts` (5) | Pix Agendado | 🏢 | Parâmetros **de projeto**. O ms executa a partir de 00h do dia (a janela 0h–8h do agendado **não** é imposta) |
| `automatic.min/max-notice-days` (2/10) | antecedência da cobrança recorrente | ❌ | Não encontrei o prazo em fonte aberta (a Resolução BCB 402/2024 delega ao documento específico do BCB). **Confirmar antes de produção** |
| `automatic.retry-days` (3), `max-late-days` (1) | novas tentativas após o vencimento (só `allow_retry`) | ❌ | A norma obriga refazer a iniciação em certos casos, mas o nº de dias está em documento específico (IN 613/614). Um material do Fórum Pix (2023) cita retentativa **intradia**; o ms usa dias seguintes. **Confirmar** |
| `automatic.request-validity` (7 d) | prazo do pagador para decidir a solicitação | ❌ | Não confirmado |
| `automatic`: cancelamento da cobrança até 23h59 da véspera | regra implementada | 🟡 | Slide do Fórum Pix de 07/12/2023 (versão anterior das especificações) |
| `qr.*` (cadeia ICP-Brasil, sem CRL/OCSP) | validação do payload JWS | 🟡 | A especificação do BR Code dinâmico exige JWS com certificado ICP-Brasil; **revogação não é verificada** — decisão de segurança a tomar |

## Conclusão
- **Confirmados (✅):** limite noturno, tempo de aumento/redução de limite, janela de devolução de 90 dias, 80 dias do MED, 30º dia da reivindicação, expiração padrão da cobrança.
- **A confirmar no texto da norma (🟡):** limites de dispositivo não cadastrado, 7 dias/72 h do MED, janela 18h–21h do agendado.
- **Sem confirmação (❌):** `med.tracking-window` (90 d), `keys.donor-response` (7 d), chaves por conta (5/20).
- **Decisão sua, não do BCB (🏢):** limites padrão e tetos por dia, parâmetros de webhook, de OTP e do agendamento.
- **Pix Automático (❌):** antecedência da cobrança, dias de nova tentativa e validade da solicitação são **suposições de projeto** (parametrizadas em `pix.automatic.*`). Não ligue em produção sem o compliance confirmar no texto da norma.

Todos são sobrescrevíveis por configuração (`pix.policy.*`), então corrigir um valor não exige mudar código. **Ação:** compliance valida as linhas ❌ e 🟡 contra o Regulamento vigente e registra o resultado aqui (data, norma, versão).
