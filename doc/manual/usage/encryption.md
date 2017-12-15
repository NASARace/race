# Using Encrypted Configurations

RACE supports encrypted configuration values for sensitive information such as
user credentials. To use encryption, follow these steps:

#### (1) create plain text config file with sensitive data
This is a normal configuration file. The toplevel node has to be "secret {..}",
but all other keys can be chosen freely, those will be later-on referenced as config
values in the universe configs:

    //------ crypt: encrypted configuration values
    secret {
      swim.user = "gonzo"
      swim.pw = "mysupersecretpassword"
      ...
    }

Store this file in the RACE root directory as `conf`

#### (2) encrypt file
RACE comes with a `CryptConfig` application and a respective wrapper script,
which is executed like this:

    > script/encryptconfig conf

When asked, enter a passphrase (and remember - it is not stored anywhere). This
will create a encrypted file `conf.crypt` and delete the plain text `conf` file.

The `encryptconfig` tool also supports a `--keep` command line option to keep the
input file. If the option is *not* specified, the un-encrypted input file will be deleted.

Values in the encrypted file will be double-encrypted and are never stored as
plain text during RACE execution

#### (3) use encryption keys in universe/satellite config values
The keys from step (1) are used as `??` prefixed values in the respective
config files, e.g.

    //--- myuniverse.conf : sample universe config
      ...
      user = "??swim.user"
      pw = "??swim.pw"
      ...

#### (4) run RACE

When executing RACE, use the `--vault <path>` option to specify which encrypted config file to use

    > ./race --vault conf.crypt config/myuniverse.conf

The system will prompt you for the same pass phrase that was entered in step (1). If the user enters 
a wrong pass phrase, or takes too long to enter it, RACE will terminate immediately.

**NOTE** `*.crypt` files are not kept in the repository, they are filtered by `.gitignore`


## using keystores

Both `encryptconfig` and `race` also support the use of a keystore file for vault encryption/decryption,
such as in

    > ./race --vault conf.crypt --keystore race.ks --alias mykeyname someconfig.conf
    
KeyStores can be created with the [standard keytool][keytool] that is distributed with Java, and 
should have a store type of "JCEKS". Keys used for vault encryption have to be "AES" keys. A sample
use would be

    > keytool -genseckey -alias mykeyname -keystore race-ks -storetype jceks -keyalg AES -keysize 256
    
If a keystore is specified, RACE will ask for the keystore password instead of the vault password


## typical example
Encrypted configuration is typically used by the `PortForwarder` and `JMSImportActor` actors in order
to specify access to gateways and JMS servers. This generally works by first establishing a ssh
session to a gateway through which respective ports for JMS servers are forwarded. Both URLs and
credentials for the gateway and JMS server should be protected. As the highest value asset, the 
`PortForwarder` interactively queries the password for the gateway which is never stored in 
the configuration file:

    ...
    { name = "portMapper"
      class = ".actor.PortForwarder"
      user = "??gw.user"
      host = "??gw.host"
      forward = "??gw.forward"
    }, ...
    { name = "sfdps-jmsImporter"
      class = ".jms.JMSImportActor"
      broker-uri = "??swim.uri.sfdps"
      user = "??swim.user"
      pw = "??swim.pw"
      write-to = "/swim/sfdps"
      jms-topic = "<jms-topic>"
    },...

The respective vault file in clear text looks like this:

    gw {
      forward="<source-port>:<destination-host>:<destination-port>,..."
      host="<gateway-hostname>"
      user="<gateway-userid>"
    }...
    swim {
        pw="<jms-password>"
        uri {
            sfdps="tcp://localhost:<source-port>"  // via port forwarding from gateway
            ...
        }
        user="<jms-userid>"
    }...

[keytool]: http://docs.oracle.com/javase/8/docs/technotes/tools/windows/keytool.html