// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2013 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package org.dogtagpki.server.tps;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.dogtagpki.server.tps.authentication.AuthenticationManager;
import org.dogtagpki.server.tps.cms.ConnectionManager;
import org.dogtagpki.server.tps.config.AuthenticatorDatabase;
import org.dogtagpki.server.tps.config.ConfigDatabase;
import org.dogtagpki.server.tps.config.ConnectorDatabase;
import org.dogtagpki.server.tps.config.ProfileDatabase;
import org.dogtagpki.server.tps.config.ProfileMappingDatabase;
import org.dogtagpki.server.tps.dbs.ActivityDatabase;
import org.dogtagpki.server.tps.dbs.TPSCertDatabase;
import org.dogtagpki.server.tps.dbs.TPSCertRecord;
import org.dogtagpki.server.tps.dbs.TokenDatabase;
import org.dogtagpki.server.tps.dbs.TokenRecord;
import org.dogtagpki.server.tps.engine.TPSEngine;
import org.dogtagpki.server.tps.mapping.MappingResolverManager;
import org.dogtagpki.tps.TPSConnection;
import org.dogtagpki.tps.main.TPSException;
import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.NotInitializedException;
import org.mozilla.jss.crypto.ObjectNotFoundException;
import org.mozilla.jss.crypto.TokenException;

import com.netscape.certsrv.authority.IAuthority;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.ISubsystem;
import com.netscape.certsrv.tps.token.TokenStatus;
import com.netscape.cmscore.base.ConfigStorage;
import com.netscape.cmscore.base.ConfigStore;
import com.netscape.cmscore.base.FileConfigStorage;
import com.netscape.cmscore.dbs.DBSubsystem;
import com.netscape.cmsutil.crypto.CryptoUtil;

/**
 * @author Endi S. Dewata <edewata@redhat.com>
 */
public class TPSSubsystem implements IAuthority {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TPSSubsystem.class);

    public final static String ID = "tps";

    public String id;
    public String nickname;
    public ISubsystem owner;
    public TPSConfig config;

    public ActivityDatabase activityDatabase;
    public AuthenticatorDatabase authenticatorDatabase;
    public TPSCertDatabase certDatabase;
    public ConfigDatabase configDatabase;
    public ConnectorDatabase connectorDatabase;
    public ProfileDatabase profileDatabase;
    public ProfileMappingDatabase profileMappingDatabase;
    public TokenDatabase tokenDatabase;
    public ConnectionManager connManager;
    public AuthenticationManager authManager;
    public MappingResolverManager mappingResolverManager;

    public TPSEngine tpsEngine;
    public TokenDB tdb;

    public Map<TokenStatus, Collection<TokenStatus>> uiTransitions;
    public Map<TokenStatus, Collection<TokenStatus>> operationTransitions;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) throws EBaseException {
        this.id = id;
    }

    @Override
    public void init(ConfigStore config) throws EBaseException {

        logger.info("Initializing TPS subsystem");

        org.dogtagpki.server.tps.TPSEngine engine = org.dogtagpki.server.tps.TPSEngine.getInstance();
        TPSEngineConfig cs = engine.getConfig();

        this.owner = owner;
        this.config = cs.getTPSConfig();

        DBSubsystem dbSubsystem = engine.getDBSubsystem();

        TokenDBConfig tdbConfig = cs.getTokenDBConfig();

        String activityDatabaseDN = tdbConfig.getString("activityBaseDN");
        activityDatabase = new ActivityDatabase(dbSubsystem, activityDatabaseDN);

        String certDatabaseDN = tdbConfig.getString("certBaseDN");
        certDatabase = new TPSCertDatabase(dbSubsystem, certDatabaseDN);

        String tokenDatabaseDN = tdbConfig.getString("baseDN");
        tokenDatabase = new TokenDatabase(dbSubsystem, tokenDatabaseDN);

        configDatabase = new ConfigDatabase();
        authenticatorDatabase = new AuthenticatorDatabase();
        connectorDatabase = new ConnectorDatabase();
        profileDatabase = new ProfileDatabase();
        profileMappingDatabase = new ProfileMappingDatabase();

        ConfigStore defaultConfig;
        try {
            ConfigStorage storage = new FileConfigStorage("/usr/share/pki/tps/conf/CS.cfg");
            defaultConfig = new ConfigStore(storage);
            defaultConfig.load();
        } catch (Exception e) {
            throw new EBaseException("Unable to load default TPS configuration: " + e.getMessage(), e);
        }

        String allowedTransitions = tdbConfig.getString("allowedTransitions");
        uiTransitions = loadTokenStateTransitions("tokendb.allowedTransitions", allowedTransitions);

        operationTransitions = loadAndValidateTokenStateTransitions(
                defaultConfig, cs, TPSEngine.CFG_OPERATIONS_ALLOWED_TRANSITIONS);

        configureTPSConnection(cs);
        tdb = new TokenDB(this);

        tpsEngine = new TPSEngine();
        tpsEngine.init();
    }

    public Map<TokenStatus, Collection<TokenStatus>> loadTokenStateTransitions(String property, String value) throws EBaseException {

        if (StringUtils.isEmpty(value)) {
            logger.error("Missing token state transitions in " + property);
            throw new EBaseException("Missing token state transition in " + property);
        }

        Map<TokenStatus, Collection<TokenStatus>> transitions = new HashMap<>();

        // initialize list with empty containers
        for (TokenStatus state : TokenStatus.values()) {
            transitions.put(state, new LinkedHashSet<TokenStatus>());
        }

        for (String transition : value.split(",")) {

            String states[] = transition.split(":");
            if (states.length < 2) {
                logger.error("Invalid token state transition in " + property + ": " + transition);
                throw new EBaseException("Invalid token state transition in " + property + ": " + transition);
            }

            TokenStatus currentState = TokenStatus.fromInt(Integer.valueOf(states[0]));
            TokenStatus nextState = TokenStatus.fromInt(Integer.valueOf(states[1]));

            if(!currentState.isValid() || !nextState.isValid()) {
                logger.debug("Invalid token state transition in " + property + ": " + transition);
                throw new EBaseException("Invalid token state transition in " + property + ": " + transition);
            }

            String info = currentState + " to " + nextState +
                    " (" + currentState.getValue() + ":" + nextState.getValue() + ")";
            logger.debug("TokenSubsystem:   - " + info);

            Collection<TokenStatus> nextStates = transitions.get(currentState);
            nextStates.add(nextState);
        }

        return transitions;
    }

    public void validateTokenStateTransitions(
            Map<TokenStatus, Collection<TokenStatus>> defaultConfig,
            Map<TokenStatus, Collection<TokenStatus>> userConfig) throws EBaseException {

        for (TokenStatus currentState : userConfig.keySet()) {
            Collection<TokenStatus> nextStates = userConfig.get(currentState);
            Collection<TokenStatus> defaultNextStates = defaultConfig.get(currentState);

            for (TokenStatus nextState : nextStates) {
                if (!defaultNextStates.contains(nextState)) {
                    String info = currentState + " to " + nextState +
                            " (" + currentState.getValue() + ":" + nextState.getValue() + ")";
                    throw new EBaseException("Unsupported token state transition: " + info);
                }
            }
        }
    }

    public Map<TokenStatus, Collection<TokenStatus>> loadAndValidateTokenStateTransitions(
            ConfigStore defaultConfig,
            ConfigStore userDefinedConfig,
            String property) throws EBaseException {

        logger.debug("TokenSubsystem: Loading transitions in " + property);

        logger.debug("TokenSubsystem: * default transitions:");
        String defaultTransitionsStr = defaultConfig.getString(property);
        Map<TokenStatus, Collection<TokenStatus>> defaultTransitions =
                loadTokenStateTransitions(property, defaultTransitionsStr);

        logger.debug("TokenSubsystem: * user-defined transitions:");
        String userDefinedTransitionsStr = userDefinedConfig.getString(property);
        Map<TokenStatus, Collection<TokenStatus>> userDefinedTransitions =
                loadTokenStateTransitions(property, userDefinedTransitionsStr);

        logger.debug("TokenSubsystem: Validating transitions in " + property);
        validateTokenStateTransitions(defaultTransitions, userDefinedTransitions);

        return userDefinedTransitions;
    }
    /**
     * Return the allowed next states for changing token state via Web UI or CLI.
     *
     * If the current state is SUSPENDED, token will be allowed to transition to
     * either FORMATTED or ACTIVE depending on whether the token has certificates.
     *
     * @param tokenRecord
     * @return A non-null collection of allowed next token states.
     */
    public Collection<TokenStatus> getUINextTokenStates(TokenRecord tokenRecord) throws TPSException {

        TokenStatus currentState = tokenRecord.getTokenStatus();
        Collection<TokenStatus> nextStates = uiTransitions.get(currentState);

        if (currentState == TokenStatus.SUSPENDED) {

            Collection<TokenStatus> ns = new LinkedHashSet<>();

            // check token certificates
            Collection<TPSCertRecord> certRecords = tdb.tdbGetCertRecordsByCUID(tokenRecord.getId());

            // if token has no certificates, allow token to become FORMATTED again
            if (certRecords.isEmpty()) {
                ns.add(TokenStatus.FORMATTED);

            } else { // otherwise, allow token to become ACTIVE again
                ns.add(TokenStatus.ACTIVE);
            }

            // add the original allowed next states
            ns.addAll(nextStates);

            return ns;
        }

        return nextStates;
    }

    /**
     * Return the allowed next states for TPS token operations (i.e. format and enrollment).
     *
     * @param tokenRecord
     * @return A non-null collection of allowed next token states.
     */
    public Collection<TokenStatus> getOperationNextTokenStates(TokenRecord tokenRecord) {
        TokenStatus currentState = tokenRecord.getTokenStatus();
        return operationTransitions.get(currentState);
    }

    public void configureTPSConnection(ConfigStore cs) {
        String configValue = TPSEngine.CFG_CONNECTION_PREFIX + "." + TPSEngine.CFG_CONNECTION_MAX_MESSAGE_SIZE;
        int configValueDefault = TPSConnection.MAX_MESSAGE_SIZE_DEFAULT;

        logger.debug("TPSConnection: Retrieving config value with name: " + configValue);
        try {
            // Try to set TPSConnection static variable to CS.cfg config value
            TPSConnection.setMaxMessageSize(cs.getInteger(configValue));
            logger.debug("TPSConnection: " + TPSEngine.CFG_CONNECTION_MAX_MESSAGE_SIZE +
                    " set to " + TPSConnection.getMaxMessageSize());
        } catch(EBaseException e) {
            // Set TPSConnection static variable to default value
            TPSConnection.setMaxMessageSize(configValueDefault);
            logger.debug("TPSConnection: Could not find given config line. Defaulting to value: " + configValueDefault);
        }
    }

    @Override
    public void startup() throws EBaseException {
        logger.debug("TPSSubsystem: startup() begins");
        connManager = new ConnectionManager();
        connManager.initConnectors();
        authManager = new AuthenticationManager();
        authManager.initAuthInstances();
        mappingResolverManager = new MappingResolverManager();
        mappingResolverManager.initMappingResolverInstances();
        logger.debug("TPSSubsystem: startup() ends.");
    }

    @Override
    public void shutdown() {
    }

    @Override
    public TPSConfig getConfigStore() {
        return config;
    }

    @Override
    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @Override
    public String getOfficialName() {
        return "tps";
    }

    public ActivityDatabase getActivityDatabase() {
        return activityDatabase;
    }

    public AuthenticatorDatabase getAuthenticatorDatabase() {
        return authenticatorDatabase;
    }

    public TPSCertDatabase getCertDatabase() {
        return certDatabase;
    }

    public ConfigDatabase getConfigDatabase() {
        return configDatabase;
    }

    public ConnectorDatabase getConnectorDatabase() {
        return connectorDatabase;
    }

    public ProfileDatabase getProfileDatabase() {
        return profileDatabase;
    }

    public ProfileMappingDatabase getProfileMappingDatabase() {
        return profileMappingDatabase;
    }

    public TokenDatabase getTokenDatabase() {
        return tokenDatabase;
    }

    public ConnectionManager getConnectionManager() {
        return connManager;
    }

    public AuthenticationManager getAuthenticationManager() {
        return authManager;
    }

    public MappingResolverManager getMappingResolverManager() {
        return mappingResolverManager;
    }

    public TokenDB getTokendb() {
        return tdb;
    }

    public org.mozilla.jss.crypto.X509Certificate getSubsystemCert() throws EBaseException, NotInitializedException,
            ObjectNotFoundException, TokenException {

        org.dogtagpki.server.tps.TPSEngine engine = org.dogtagpki.server.tps.TPSEngine.getInstance();
        TPSEngineConfig cs = engine.getConfig();
        String nickname = cs.getString("tps.subsystem.nickname", "");
        String tokenname = cs.getString("tps.subsystem.tokenname", "");
        if (!CryptoUtil.isInternalToken(tokenname))
            nickname = tokenname + ":" + nickname;

        CryptoManager cm = CryptoManager.getInstance();
        return cm.findCertByNickname(nickname);
    }

    public TPSEngine getEngine() {
        return tpsEngine;
    }

    public boolean isUITransitionAllowed(TokenRecord tokenRecord, TokenStatus nextState) throws Exception {
        Collection<TokenStatus> nextStates = getUINextTokenStates(tokenRecord);
        return nextStates.contains(nextState);
    }

    public boolean isOperationTransitionAllowed(TokenRecord tokenRecord, TokenStatus nextState) {
        Collection<TokenStatus> nextStates = getOperationNextTokenStates(tokenRecord);
        return nextStates.contains(nextState);
    }
}
