#!/usr/bin/env bash

if [ -z "$CERT_CERTFILE" ]
then
  exit 0
fi

if [ -z "$CERT_KEYFILE" ]
then
  exit 0
fi

echo "$CERT_CERTFILE" > keys/org.crt
echo "$CERT_KEYFILE" | base64 -d > keys/org.key.pkcs8
