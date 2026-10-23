#!/usr/bin/env bash
# Call the TaxWebService SOAP endpoint in tax-ear with a document/literal envelope.
#
#   soap-tax.sh [country] [region] [category] [amount]     defaults: NL NH GOODS 123.74
#   soap-tax.sh --wsdl                                     print the WSDL instead

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT

endpoint="http://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT/tax/TaxWebService"

if [ "${1:-}" = "--wsdl" ]; then
  exec curl -s "$endpoint?WSDL"
fi

country="${1:-NL}"
region="${2:-NH}"
category="${3:-GOODS}"
amount="${4:-123.74}"

envelope=$(cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tax="http://acme.com/tax">
  <soapenv:Body>
    <tax:computeTax>
      <country>$country</country>
      <region>$region</region>
      <category>$category</category>
      <amount>$amount</amount>
    </tax:computeTax>
  </soapenv:Body>
</soapenv:Envelope>
EOF
)

curl -s -w '\nHTTP %{http_code}\n' \
  -H 'Content-Type: text/xml; charset=UTF-8' \
  -H 'SOAPAction: ""' \
  --data-binary "$envelope" \
  "$endpoint"
