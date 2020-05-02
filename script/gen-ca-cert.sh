#!/bin/bash

# create self signed CA certificate for use in Java SSL
# see  https://lightbend.github.io/ssl-config/CertificateGeneration.html

rm -f exampleca.*

#export PW=`pwgen -Bs 10 1`
#echo $PW > password
PW=`cat password`


#--- Create a self signed key pair root CA certificate.

keytool -genkeypair \
  -alias exampleca \
  -dname "CN=exampleCA, OU=Example Org, O=Example Company, L=San Francisco, ST=California,C=US" \
  -keystore exampleca.jks \
  -keypass $PW \
  -storepass $PW \
  -keyalg RSA \
  -keysize 4096 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true" \
  -validity 9999

#--- Export the exampleCA public certificate as exampleca.crt so that it can be used in trust stores.

keytool -export -v \
  -alias exampleca \
  -file exampleca.crt \
  -keypass $PW \
  -storepass $PW \
  -keystore exampleca.jks \
  -rfc

