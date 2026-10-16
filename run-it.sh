#!/bin/bash
# Atalho: roda UM IT com saída enxuta (resumo + falhas). Uso: ./run-it.sh ChargeApiIT [linhas]
cd "$(dirname "$0")"
./mvn.sh -q verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test="$1" -Djacoco.skip=true > /tmp/run-it.log 2>&1
grep -E "Tests run:.*(Fail|Time)|<<< (FAILURE|ERROR)|AssertionError|Expected|Actual|expected:|but was|java.lang.Assertion|Caused by: [a-z.]*(Exception|Error)|APPLICATION FAILED|^Description|^Action" /tmp/run-it.log | cut -c1-240 | head -${2:-30}
