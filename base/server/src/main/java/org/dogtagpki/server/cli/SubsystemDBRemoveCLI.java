//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.server.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.dogtagpki.cli.CLI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netscape.cms.servlet.csadmin.LDAPConfigurator;
import com.netscape.cmscore.apps.EngineConfig;
import com.netscape.cmscore.ldapconn.LDAPConfig;
import com.netscape.cmscore.ldapconn.LDAPConnectionConfig;
import com.netscape.cmscore.ldapconn.LdapAuthInfo;
import com.netscape.cmscore.ldapconn.LdapBoundConnection;
import com.netscape.cmscore.ldapconn.LdapConnInfo;
import com.netscape.cmscore.ldapconn.PKISocketConfig;
import com.netscape.cmscore.ldapconn.PKISocketFactory;
import com.netscape.cmsutil.password.IPasswordStore;
import com.netscape.cmsutil.password.PasswordStoreConfig;

/**
 * @author Endi S. Dewata
 */
public class SubsystemDBRemoveCLI extends SubsystemCLI {

    public static Logger logger = LoggerFactory.getLogger(SubsystemDBRemoveCLI.class);

    public SubsystemDBRemoveCLI(CLI parent) {
        super("remove", "Remove " + parent.getParent().getName().toUpperCase() + " database", parent);
    }

    @Override
    public void createOptions() {

        Option option = new Option("d", true, "NSS database location");
        option.setArgName("database");
        options.addOption(option);

        option = new Option("f", true, "NSS database password configuration");
        option.setArgName("password config");
        options.addOption(option);

        options.addOption(null, "force", false, "Force");

        options.addOption("v", "verbose", false, "Run in verbose mode.");
        options.addOption(null, "debug", false, "Run in debug mode.");
        options.addOption(null, "help", false, "Show help message.");
    }

    @Override
    public void execute(CommandLine cmd) throws Exception {

        initializeTomcatJSS();
        String subsystem = parent.getParent().getName();
        EngineConfig cs = getEngineConfig(subsystem);
        cs.load();

        LDAPConfig ldapConfig = cs.getInternalDBConfig();
        String database = ldapConfig.getDatabase();
        String baseDN = ldapConfig.getBaseDN();

        logger.info("Removing database " + database);

        if (!cmd.hasOption("force")) {
            System.out.println("WARNING: This command will remove the " + database + " database that contains the " + baseDN + " subtree.");
            System.out.println();
            System.out.print("Are you sure (y/N)? ");
            System.out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line = reader.readLine();
            if (!line.equalsIgnoreCase("Y")) {
                return;
            }
        }

        PasswordStoreConfig psc = cs.getPasswordStoreConfig();
        IPasswordStore passwordStore = IPasswordStore.create(psc);

        LDAPConnectionConfig connConfig = ldapConfig.getConnectionConfig();

        LdapConnInfo connInfo = new LdapConnInfo(connConfig);
        LdapAuthInfo authInfo = getAuthInfo(passwordStore, connInfo, ldapConfig);

        PKISocketConfig socketConfig = cs.getSocketConfig();

        PKISocketFactory socketFactory;
        if (authInfo.getAuthType() == LdapAuthInfo.LDAP_AUTHTYPE_SSLCLIENTAUTH) {
            socketFactory = new PKISocketFactory(authInfo.getClientCertNickname());
        } else {
            socketFactory = new PKISocketFactory(connInfo.getSecure());
        }
        socketFactory.init(socketConfig);

        LdapBoundConnection conn = new LdapBoundConnection(socketFactory, connInfo, authInfo);
        LDAPConfigurator ldapConfigurator = new LDAPConfigurator(conn, ldapConfig);

        try {
            ldapConfigurator.deleteDatabase(database, baseDN);

        } finally {
            conn.disconnect();
        }
    }
}
