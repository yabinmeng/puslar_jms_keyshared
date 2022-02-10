#! /bin/bash

LOCAL_PULSAR_HOME=/path/to/apache-pulsar-2.8.0

PROJECT_HOME=/path/to/JMS_to_Pulsar
LOCAL_TARGET_CONFILE_HOME=$PROJECT_HOME/docker/msgsrv/pulsar/conf
LOCAL_TARGET_SECFILE_HOME=$PROJECT_HOME/docker/msgsrv/pulsar/security

TARGET_PULSAR_HOME=/pulsar

if [[ ! -d $LOCAL_TARGET_SECFILE_HOME/key ]]; then
    mkdir -p $LOCAL_TARGET_SECFILE_HOME/key
fi

if [[ ! -d $LOCAL_TARGET_SECFILE_HOME/token ]]; then
    mkdir -p $LOCAL_TARGET_SECFILE_HOME/token
fi


PRIV_KEY=my-private.key
PUB_KEY=my-public.key
$LOCAL_PULSAR_HOME/bin/pulsar tokens create-key-pair \
   --output-private-key $LOCAL_TARGET_SECFILE_HOME/key/$PRIV_KEY \
   --output-public-key $LOCAL_TARGET_SECFILE_HOME/key/$PUB_KEY

SUPER_USER=cluster-admin
REG_USER=reguser
$LOCAL_PULSAR_HOME/bin/pulsar tokens create \
    --private-key  $LOCAL_TARGET_SECFILE_HOME/key/$PRIV_KEY \
    --subject $SUPER_USER > $LOCAL_TARGET_SECFILE_HOME/token/$SUPER_USER.jwt
$LOCAL_PULSAR_HOME/bin/pulsar tokens create \
    --private-key  $LOCAL_TARGET_SECFILE_HOME/key/$PRIV_KEY \
    --subject $REG_USER > $LOCAL_TARGET_SECFILE_HOME/token/$REG_USER.jwt

# client.conf
CLNT_CONF=$LOCAL_TARGET_CONFILE_HOME/client.conf
sed -i '' "s|^authPlugin=.*|authPlugin=org.apache.pulsar.client.impl.auth.AuthenticationToken|g" $CLNT_CONF
sed -i '' "s|^authParams=.*|authParams=file://$TARGET_PULSAR_HOME/security/token/$SUPER_USER.jwt|g" $CLNT_CONF

# broker.conf
STANDALONE_CONF=$LOCAL_TARGET_CONFILE_HOME/standalone.conf
sed -i '' "s|^authenticationEnabled=.*|authenticationEnabled=true|g" $STANDALONE_CONF
sed -i '' "s|^authenticationProviders=.*|authenticationProviders=org.apache.pulsar.broker.authentication.AuthenticationProviderToken|g" $STANDALONE_CONF
sed -i '' "s|^authorizationEnabled=.*|authorizationEnabled=true|g" $STANDALONE_CONF
sed -i '' "s|^authorizationProvider=.*|authorizationProvider=org.apache.pulsar.broker.authorization.PulsarAuthorizationProvider|g" $STANDALONE_CONF
sed -i '' "s|^superUserRoles=.*|superUserRoles=$SUPER_USER|g" $STANDALONE_CONF
sed -i '' "s|^brokerClientAuthenticationPlugin=.*|brokerClientAuthenticationPlugin=org.apache.pulsar.client.impl.auth.AuthenticationToken|g" $STANDALONE_CONF
sed -i '' "s|^brokerClientAuthenticationParameters=.*|brokerClientAuthenticationParameters=file://$TARGET_PULSAR_HOME/security/token/$SUPER_USER.jwt|g" $STANDALONE_CONF
sed -i '' "s|^tokenPublicKey=.*|tokenPublicKey=file://$TARGET_PULSAR_HOME/security/key/$PUB_KEY|g" $STANDALONE_CONF
