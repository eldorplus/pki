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
// (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cms.publish.publishers;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Vector;

import org.dogtagpki.server.ca.CAEngine;
import org.dogtagpki.server.ca.CAEngineConfig;

import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.IExtendedPluginInfo;
import com.netscape.certsrv.dbs.certdb.CertId;
import com.netscape.certsrv.ldap.ELdapException;
import com.netscape.certsrv.ldap.ELdapServerDownException;
import com.netscape.certsrv.logging.AuditFormat;
import com.netscape.certsrv.publish.ILdapPublisher;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.base.ConfigStore;
import com.netscape.cmscore.ldapconn.LdapBoundConnection;
import com.netscape.cmscore.ldapconn.PKISocketConfig;
import com.netscape.cmscore.ldapconn.PKISocketFactory;

import netscape.ldap.LDAPAttribute;
import netscape.ldap.LDAPConnection;
import netscape.ldap.LDAPEntry;
import netscape.ldap.LDAPException;
import netscape.ldap.LDAPModification;
import netscape.ldap.LDAPSearchResults;
import netscape.ldap.LDAPv3;

/**
 * Interface for mapping a X509 certificate to a LDAP entry
 *
 * @version $Revision$, $Date$
 */
public class LdapUserCertPublisher implements ILdapPublisher, IExtendedPluginInfo {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LdapUserCertPublisher.class);

    public static final String LDAP_USERCERT_ATTR = "userCertificate;binary";

    protected String mCertAttr = LDAP_USERCERT_ATTR;
    private ConfigStore mConfig;
    private boolean mInited = false;

    public LdapUserCertPublisher() {
    }

    @Override
    public String getImplName() {
        return "LdapUserCertPublisher";
    }

    @Override
    public String getDescription() {
        return "LdapUserCertPublisher";
    }

    @Override
    public String[] getExtendedPluginInfo() {
        String[] params = {
                "certAttr;string;LDAP attribute in which to store the certificate",
                IExtendedPluginInfo.HELP_TOKEN +
                        ";configuration-ldappublish-publisher-usercertpublisher",
                IExtendedPluginInfo.HELP_TEXT +
                        ";This plugin knows how to publish user certificates"
            };

        return params;

    }

    @Override
    public Vector<String> getInstanceParams() {
        Vector<String> v = new Vector<>();

        v.addElement("certAttr=" + mCertAttr);
        return v;
    }

    @Override
    public Vector<String> getDefaultParams() {
        Vector<String> v = new Vector<>();

        v.addElement("certAttr=" + mCertAttr);
        return v;
    }

    @Override
    public ConfigStore getConfigStore() {
        return mConfig;
    }

    @Override
    public void init(ConfigStore config) throws EBaseException {
        if (mInited)
            return;

        logger.info("LdapUserCertPublisher: Initializing LdapUserCertPublisher");

        mConfig = config;

        mCertAttr = mConfig.getString("certAttr", LDAP_USERCERT_ATTR);
        logger.info("LdapUserCertPublisher: - cert attr: " + mCertAttr);

        mInited = true;
    }

    public LdapUserCertPublisher(String certAttr) {
        mCertAttr = certAttr;
    }

    /**
     * publish a user certificate
     * Adds the cert to the multi-valued certificate attribute as a
     * DER encoded binary blob. Does not check if cert already exists.
     *
     * @param conn the LDAP connection
     * @param dn dn of the entry to publish the certificate
     * @param certObj the certificate object.
     */
    @Override
    public void publish(LDAPConnection conn, String dn, Object certObj) throws ELdapException {

        if (conn == null) {
            return;
        }

        if (!(certObj instanceof X509Certificate)) {
            throw new IllegalArgumentException("Illegal arg to publish");
        }

        X509Certificate cert = (X509Certificate) certObj;
        CertId certID = new CertId(cert.getSerialNumber());

        logger.info("LdapUserCertPublisher: Publishing cert " + certID.toHexString() + " to " + dn);

        CAEngine engine = CAEngine.getInstance();
        CAEngineConfig cs = engine.getConfig();

        PKISocketConfig socketConfig = cs.getSocketConfig();

        // Bugscape #56124 - support multiple publishing directory
        // see if we should create local connection
        LDAPConnection altConn = null;
        try {
            String host = mConfig.getString("host", null);
            String port = mConfig.getString("port", null);
            if (host != null && port != null) {
                int portVal = Integer.parseInt(port);
                int version = Integer.parseInt(mConfig.getString("version", "2"));
                String cert_nick = mConfig.getString("clientCertNickname", null);

                PKISocketFactory sslSocket;
                if (cert_nick != null) {
                    sslSocket = new PKISocketFactory(cert_nick);
                } else {
                    sslSocket = new PKISocketFactory(true);
                }
                sslSocket.init(socketConfig);

                String mgr_dn = mConfig.getString("bindDN", null);
                String mgr_pwd = mConfig.getString("bindPWD", null);

                altConn = new LdapBoundConnection(host, portVal,
                        version,
                        sslSocket, mgr_dn, mgr_pwd);
                conn = altConn;
            }
        } catch (LDAPException e) {
            logger.warn("LdapUserCertPublisher: Failed to create alt connection " + e.getMessage(), e);
        } catch (EBaseException e) {
            logger.warn("LdapUserCertPublisher: Failed to create alt connection " + e.getMessage(), e);
        }

        boolean deleteCert = false;
        try {
            deleteCert = mConfig.getBoolean("deleteCert", false);
        } catch (Exception e) {
        }

        logger.info("LdapUserCertPublisher: - delete cert: " + deleteCert);

        try {
            byte[] certEnc = cert.getEncoded();

            // check if cert already exists.
            LDAPSearchResults res = conn.search(dn, LDAPv3.SCOPE_BASE,
                    "(objectclass=*)", new String[] { mCertAttr }, false);
            LDAPEntry entry = res.next();

            if (ByteValueExists(entry.getAttribute(mCertAttr), certEnc)) {
                logger.info("LdapUserCertPublisher: " + dn + " already has cert " + certID.toHexString());
                return;
            }

            // publish
            LDAPModification mod = null;
            if (deleteCert) {
                logger.info("LdapUserCertPublisher: Replacing certs in " + dn);
                mod = new LDAPModification(LDAPModification.REPLACE,
                        new LDAPAttribute(mCertAttr, certEnc));
            } else {
                logger.info("LdapUserCertPublisher: Adding cert into " + dn);
                mod = new LDAPModification(LDAPModification.ADD,
                        new LDAPAttribute(mCertAttr, certEnc));
            }

            conn.modify(dn, mod);

            logger.info(
                    AuditFormat.LDAP_PUBLISHED_FORMAT,
                    "LdapUserCertPublisher",
                    cert.getSerialNumber().toString(16),
                    cert.getSubjectDN()
            );

        } catch (CertificateEncodingException e) {
            logger.error("LdapUserCertPublisher: error in publish: " + e.getMessage(), e);
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_GET_DER_ENCODED_CERT_FAILED", e.toString()));

        } catch (LDAPException e) {
            if (e.getLDAPResultCode() == LDAPException.UNAVAILABLE) {
                // need to intercept this because message from LDAP is
                // "DSA is unavailable" which confuses with DSA PKI.
                logger.error(CMS.getLogMessage("PUBLISH_NO_LDAP_SERVER"), e);
                throw new ELdapServerDownException(CMS.getUserMessage("CMS_LDAP_SERVER_UNAVAILABLE", conn.getHost(), "" + conn.getPort()), e);
            } else {
                logger.error(CMS.getLogMessage("PUBLISH_PUBLISH_ERROR", e.toString()), e);
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_PUBLISH_USERCERT_ERROR", e.toString()), e);
            }

        } finally {
            if (altConn != null) {
                try {
                    altConn.disconnect();
                } catch (LDAPException e) {
                    // safely ignored
                }
            }
        }
    }

    /**
     * unpublish a user certificate
     * deletes the certificate from the list of certificates.
     * does not check if certificate is already there.
     */
    @Override
    public void unpublish(LDAPConnection conn, String dn, Object certObj)
            throws ELdapException {

        boolean disableUnpublish = false;
        try {
            disableUnpublish = mConfig.getBoolean("disableUnpublish", false);
        } catch (Exception e) {
        }

        if (disableUnpublish) {
            logger.debug("UserCertPublisher: disable unpublish");
            return;
        }

        if (!(certObj instanceof X509Certificate))
            throw new IllegalArgumentException("Illegal arg to publish");

        X509Certificate cert = (X509Certificate) certObj;

        try {
            byte[] certEnc = cert.getEncoded();

            // check if cert already deleted.
            LDAPSearchResults res = conn.search(dn, LDAPv3.SCOPE_BASE,
                    "(objectclass=*)", new String[] { mCertAttr }, false);
            LDAPEntry entry = res.next();

            if (!ByteValueExists(entry.getAttribute(mCertAttr), certEnc)) {
                logger.info("LdapUserCertPublisher: " + dn + " already has not cert");
                return;
            }

            LDAPModification mod = new LDAPModification(LDAPModification.DELETE,
                    new LDAPAttribute(mCertAttr, certEnc));

            conn.modify(dn, mod);

        } catch (CertificateEncodingException e) {
            logger.error(CMS.getLogMessage("PUBLISH_UNPUBLISH_ERROR", e.toString()), e);
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_GET_DER_ENCODED_CERT_FAILED", e.toString()), e);

        } catch (LDAPException e) {
            if (e.getLDAPResultCode() == LDAPException.UNAVAILABLE) {
                // need to intercept this because message from LDAP is
                // "DSA is unavailable" which confuses with DSA PKI.
                logger.error(CMS.getLogMessage("PUBLISH_NO_LDAP_SERVER"), e);
                throw new ELdapServerDownException(CMS.getUserMessage("CMS_LDAP_SERVER_UNAVAILABLE", conn.getHost(), "" + conn.getPort()), e);
            } else {
                logger.error(CMS.getLogMessage("PUBLISH_UNPUBLISH_ERROR"), e);
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_UNPUBLISH_USERCERT_ERROR", e.toString()), e);
            }
        }
        return;
    }

    /**
     * checks if a byte attribute has a certain value.
     */
    public static boolean ByteValueExists(LDAPAttribute attr, byte[] bval) {
        if (attr == null) {
            return false;
        }
        Enumeration<byte[]> vals = attr.getByteValues();
        byte[] val = null;

        while (vals.hasMoreElements()) {
            val = vals.nextElement();
            if (val.length == 0)
                continue;
            if (PublisherUtils.byteArraysAreEqual(val, bval)) {
                return true;
            }
        }
        return false;
    }

    /**
     * checks if a attribute has a string value.
     */
    public static boolean StringValueExists(LDAPAttribute attr, String sval) {
        if (attr == null) {
            return false;
        }
        Enumeration<String> vals = attr.getStringValues();
        String val = null;

        while (vals.hasMoreElements()) {
            val = vals.nextElement();
            if (val.equalsIgnoreCase(sval)) {
                return true;
            }
        }
        return false;
    }

}
