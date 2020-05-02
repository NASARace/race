#!/bin/bash

# create self signed server certificate for use in Java SSL
# note that we assume the exampleca.jks keystore for the root CA was already created with gen-ca-cert.sh
# see  https://lightbend.github.io/ssl-config/CertificateGeneration.html

HOST=$1
PW=`cat password`

rm -f $HOST.*



#--- Create a server certificate, tied to $HOST

keytool -genkeypair -v \
  -alias $HOST \
  -dname "CN=$HOST, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore $HOST.jks \
  -keypass $PW \
  -storepass $PW \
  -keyalg RSA \
  -keysize 2048 \
  -validity 9000

#--- Create a certificate signing request for HOST

keytool -certreq -v \
  -alias $HOST \
  -keypass $PW \
  -storepass $PW \
  -keystore $HOST.jks \
  -file $HOST.csr


# Tell exampleCA to sign the $HOST certificate. Note the extension is on the request, not the
# original certificate.
# Technically, keyUsage should be digitalSignature for DHE or ECDHE, keyEncipherment for RSA.

keytool -gencert -v \
  -alias exampleca \
  -keypass $PW \
  -storepass $PW \
  -keystore exampleca.jks \
  -infile $HOST.csr \
  -outfile $HOST.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:$HOST" \
  -rfc \
  -validity 8000



#--- Tell $HOST.jks it can trust exampleca as a signer.
keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore $HOST.jks \
  -storetype JKS \
  -storepass $PW << EOF
yes
EOF

#--- Import the signed certificate back into $HOST.jks 
keytool -import -v \
  -alias $HOST \
  -file $HOST.crt \
  -keystore $HOST.jks \
  -storetype JKS \
  -storepass $PW


#--- clean up

rm -f $HOST.csr
rm -f $HOST.crt


#--- List out the contents of $HOST.jks just to confirm it.  
# If you are using RACE as a TLS termination point, this is the key store you should present as the server.
keytool -list \
  -keystore $HOST.jks \
  -storepass $PW

