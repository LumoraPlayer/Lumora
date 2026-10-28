#!/bin/bash
cd /root/Lumora
REPORT=app/build/intermediates/lint_intermediate_text_report/debug/lint-results-debug.txt
echo "== error categories =="
grep 'Error:' "$REPORT" | sed 's/.*\[\([A-Za-z]*\)\]$/\1/' | sort | uniq -c | sort -rn | head -20
echo
echo "== MissingTranslation count =="
grep -c 'MissingTranslation' "$REPORT"
echo
echo "== NewApi lines =="
grep -n 'NewApi' "$REPORT"
