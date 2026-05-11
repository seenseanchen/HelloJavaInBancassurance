#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${SCRIPT_DIR}/../config/nginx/certs"
FORCE="${1:-}"

mkdir -p "${CERT_DIR}"

if [[ "${FORCE}" != "--force" ]] && [[ -f "${CERT_DIR}/fullchain.pem" ]] && [[ -f "${CERT_DIR}/privkey.pem" ]]; then
  echo "TLS certs already exist at ${CERT_DIR}. Use --force to regenerate."
  exit 0
fi

openssl req -x509 -nodes -newkey rsa:2048 -sha256 -days 365 \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost" \
  -keyout "${CERT_DIR}/privkey.pem" \
  -out "${CERT_DIR}/fullchain.pem"

chmod 600 "${CERT_DIR}/privkey.pem"
chmod 644 "${CERT_DIR}/fullchain.pem"

echo "Generated self-signed certs:"
echo "  - ${CERT_DIR}/fullchain.pem"
echo "  - ${CERT_DIR}/privkey.pem"
