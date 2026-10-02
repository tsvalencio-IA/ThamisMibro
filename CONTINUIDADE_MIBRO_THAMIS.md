# Continuidade — ThamisMibro

## Fonte de verdade

Repositório: `tsvalencio-IA/ThamisMibro`

## Escopo fixo

Aplicativo dedicado à Thamis para executar treinos já existentes no atletIA Live usando Mibro Watch GS Pro.

## Integração

- Firebase project: `lerunners-a6de2`
- autenticação: mesma conta já cadastrada no atletIA Live
- leitura de treinos:
  - `users/{auth.uid}/workouts`
  - `data/{auth.uid}/workouts`
- nenhuma consulta global a `/users`
- nenhuma chave Gemini/OpenAI/serviço privada no repositório

## Relógio

- modelo: Mibro Watch GS Pro
- ponte atual: notificações Android espelhadas pelo Mibro Fit
- não existe neste projeto escrita BLE proprietária/firmware

## Motor do Pacer

- GPS preferencial: `GPS_PROVIDER`; rede apenas como fallback quando GPS não estiver disponível
- precisão máxima aceita: 35 m
- janela de suavização: 20 s
- persistência antes do alerta: 12 s
- histerese do alvo: 3 s/km
- cooldown fora da faixa: 22 s
- cooldown de MANTENHA: 60 s
- faixa de velocidade válida: 0,6 a 8,5 m/s

## Estados

`PRONTO → CORRENDO → PAUSADO → RETOMADO → CONCLUÍDO/ENCERRADO`

## Regras de continuidade

1. Não copiar segredos do LeRunners para este repositório público.
2. Não trocar o projeto Firebase sem autorização.
3. Não alterar a lógica principal do atletIA/LeRunners a partir daqui.
4. Alterações Mibro ficam isoladas neste repositório.
5. Antes de afirmar suporte direto do relógio, exigir documentação pública ou teste físico confirmado.
6. Manter o rodapé `Powered by thIAguinho Soluções Digitais`.
