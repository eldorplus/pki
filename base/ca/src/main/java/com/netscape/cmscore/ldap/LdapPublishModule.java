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
package com.netscape.cmscore.ldap;

import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Hashtable;

import org.dogtagpki.server.ca.CAEngine;
import org.dogtagpki.server.ca.CAEngineConfig;
import org.mozilla.jss.netscape.security.x509.X500Name;
import org.mozilla.jss.netscape.security.x509.X509CRLImpl;
import org.mozilla.jss.netscape.security.x509.X509CertImpl;

import com.netscape.ca.CertificateAuthority;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.ISubsystem;
import com.netscape.certsrv.base.MetaInfo;
import com.netscape.certsrv.dbs.Modification;
import com.netscape.certsrv.dbs.ModificationSet;
import com.netscape.certsrv.ldap.ELdapException;
import com.netscape.certsrv.ldap.ILdapConnFactory;
import com.netscape.certsrv.publish.ILdapMapper;
import com.netscape.certsrv.publish.ILdapPlugin;
import com.netscape.certsrv.publish.ILdapPublisher;
import com.netscape.certsrv.request.IRequestListener;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.base.ConfigStore;
import com.netscape.cmscore.dbs.CertRecord;
import com.netscape.cmscore.dbs.CertificateRepository;
import com.netscape.cmscore.ldapconn.LDAPConfig;
import com.netscape.cmscore.ldapconn.LdapBoundConnFactory;
import com.netscape.cmscore.ldapconn.PKISocketConfig;
import com.netscape.cmscore.request.Request;

import netscape.ldap.LDAPConnection;

/**
 * Handles requests to perform Ldap publishing.
 */
public class LdapPublishModule implements IRequestListener {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LdapPublishModule.class);

    protected ConfigStore mConfig;
    protected LdapBoundConnFactory mLdapConnFactory = null;
    private boolean mInited = false;
    protected CertificateAuthority mAuthority = null;

    /**
     * hashtable of cert types to cert mappers and publishers.
     * cert types are client, server, ca, subca, ra, crl, etc.
     * XXX the cert types need to be consistently used.
     * for each, the mapper may be null, in which case the full subject
     * name is used to map the cert.
     * for crl, if the mapper is null the ca mapper is used. if that
     * is null, the full issuer name is used.
     * XXX if we support crl issuing points the issuing point should be used
     * to publish the crl.
     * When publishers are null, the certs are not published.
     */
    protected Hashtable<String, LdapMappers> mMappers = new Hashtable<>();

    /**
     * handlers for request types (events)
     * values implement IRequestListener
     */
    protected Hashtable<String, IRequestListener> mEventHandlers = new Hashtable<>();

    /**
     * instantiate connection factory.
     */
    public static final String ATTR_LDAPPUBLISH_STATUS = "LdapPublishStatus";
    public static final String PROP_LDAP = "ldap";
    public static final String PROP_MAPPER = "mapper";
    public static final String PROP_PUBLISHER = "publisher";
    public static final String PROP_CLASS = "class";
    public static final String PROP_TYPE = "type";
    public static final String PROP_TYPE_CA = "ca";
    public static final String PROP_TYPE_CLIENT = "client";
    public static final String PROP_TYPE_CRL = "crl";

    public LdapPublishModule() {
    }

    /**
     * initialize ldap publishing module with config store
     */
    @Override
    public void init(ISubsystem sub, ConfigStore config) throws EBaseException {
    }

    @Override
    public void set(String name, String val) {
    }

    public LdapPublishModule(LdapBoundConnFactory factory) {
        mLdapConnFactory = factory;
        mInited = true;
    }

    protected CAPublisherProcessor mPubProcessor;

    public void init(CertificateAuthority authority, CAPublisherProcessor p, ConfigStore config)
            throws EBaseException {
        if (mInited)
            return;

        CAEngine engine = CAEngine.getInstance();
        CAEngineConfig cs = engine.getConfig();

        mAuthority = authority;
        mPubProcessor = p;
        mConfig = config;

        PKISocketConfig socketConfig = cs.getSocketConfig();
        LDAPConfig ldapCfg = mConfig.getSubStore("ldap", LDAPConfig.class);
        mLdapConnFactory = new LdapBoundConnFactory("LdapPublishModule");
        mLdapConnFactory.init(socketConfig, ldapCfg, engine.getPasswordStore());

        // initMappers(config);
        initHandlers();

        engine.registerRequestListener(this);
    }

    public void init(CertificateAuthority authority, ConfigStore config) throws EBaseException {
        if (mInited)
            return;

        CAEngine engine = CAEngine.getInstance();
        CAEngineConfig cs = engine.getConfig();

        mAuthority = authority;
        mConfig = config;

        PKISocketConfig socketConfig = cs.getSocketConfig();
        LDAPConfig ldapCfg = mConfig.getSubStore("ldap", LDAPConfig.class);
        mLdapConnFactory = new LdapBoundConnFactory("LdapPublishModule");
        mLdapConnFactory.init(socketConfig, ldapCfg, engine.getPasswordStore());

        initMappers(config);
        initHandlers();

        engine.registerRequestListener(this);
    }

    /**
     * Returns the internal ldap connection factory.
     * This can be useful to get a ldap connection to the
     * ldap publishing directory without having to get it again from the
     * config file. Note that this means sharing a ldap connection pool
     * with the ldap publishing module so be sure to return connections to pool.
     * Use ILdapConnFactory.getConn() to get a Ldap connection to the ldap
     * publishing directory.
     * Use ILdapConnFactory.returnConn() to return the connection.
     *
     * @see com.netscape.certsrv.ldap.LdapBoundConnFactory
     * @see com.netscape.certsrv.ldap.ILdapConnFactory
     */
    public ILdapConnFactory getLdapConnFactory() {
        return mLdapConnFactory;
    }

    /**
     * Returns the connection factory to the publishing directory.
     * Must return the connection once you return
     */

    protected LdapMappers getMappers(String certType) {
        LdapMappers mappers = null;

        if (certType == null) {
            mappers = mMappers.get(PROP_TYPE_CLIENT);
        } else {
            mappers = mMappers.get(certType);
        }
        return mappers;
    }

    protected void initMappers(ConfigStore config) throws EBaseException {
        ConfigStore types = mConfig.getSubStore(PROP_TYPE, ConfigStore.class);

        if (types == null || types.size() <= 0) {
            // nothing configured.
            logger.debug("No ldap publishing configurations.");
            return;
        }
        Enumeration<String> substores = types.getSubStoreNames().elements();

        while (substores.hasMoreElements()) {
            String certType = substores.nextElement();
            ConfigStore current = types.getSubStore(certType, ConfigStore.class);

            if (current == null || current.size() <= 0) {
                logger.debug("No ldap publish configuration for " + certType + " found.");
                continue;
            }
            ILdapPlugin mapper = null, publisher = null;
            ConfigStore mapperConf = null, publisherConf = null;
            String mapperClassName = null, publisherClassName = null;

            try {
                mapperConf = current.getSubStore(PROP_MAPPER, ConfigStore.class);
                mapperClassName = mapperConf.getString(PROP_CLASS, null);
                if (mapperClassName != null && mapperClassName.length() > 0) {
                    logger.debug("mapper " + mapperClassName + " for " + certType);
                    mapper = (ILdapPlugin) Class.forName(mapperClassName).getDeclaredConstructor().newInstance();
                    mapper.init(mapperConf);
                }
                publisherConf = current.getSubStore(PROP_PUBLISHER, ConfigStore.class);
                publisherClassName = publisherConf.getString(PROP_CLASS, null);
                if (publisherClassName != null &&
                        publisherClassName.length() > 0) {
                    logger.debug("publisher " + publisherClassName + " for " + certType);
                    publisher = (ILdapPlugin) Class.forName(publisherClassName).getDeclaredConstructor().newInstance();
                    publisher.init(publisherConf);
                }
                mMappers.put(certType, new LdapMappers(mapper, publisher));

            } catch (EBaseException e) {
                logger.error(CMS.getLogMessage("CMSCORE_LDAP_INIT_ERROR", certType, e.toString()), e);
                throw e;

            } catch (Exception e) {
                logger.error(CMS.getLogMessage("CMSCORE_LDAP_INIT_ERROR", certType, e.toString()), e);
                throw new EBaseException(e);
            }
        }
        mInited = true;
    }

    protected void initHandlers() {
        mEventHandlers.put(Request.ENROLLMENT_REQUEST,
                new HandleEnrollment(this));
        mEventHandlers.put(Request.RENEWAL_REQUEST,
                new HandleRenewal(this));
        mEventHandlers.put(Request.REVOCATION_REQUEST,
                new HandleRevocation(this));
        mEventHandlers.put(Request.UNREVOCATION_REQUEST,
                new HandleUnrevocation(this));
    }

    /**
     * Accepts completed requests from an authority and
     * performs ldap publishing.
     *
     * @param request The publishing request.
     */
    @Override
    public void accept(Request request) {
        String type = request.getRequestType();

        IRequestListener handler = mEventHandlers.get(type);

        if (handler == null) {
            logger.debug("Nothing to publish for request type " + type);
            return;
        }
        handler.accept(request);
    }

    public void publish(String certType, X509Certificate cert)
            throws ELdapException {
        // get mapper and publisher for cert type.
        LdapMappers mappers = getMappers(certType);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for " + certType + " is null");
            return;
        }
        publish((ILdapMapper) mappers.mapper,
                (ILdapPublisher) mappers.publisher, cert);

        // set the ldap published flag.
        setPublishedFlag(cert.getSerialNumber(), true);
    }

    public void unpublish(String certType, X509Certificate cert)
            throws ELdapException {
        // get mapper and publisher for cert type.
        LdapMappers mappers = getMappers(certType);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for " + certType + " is null");
            return;
        }
        unpublish((ILdapMapper) mappers.mapper,
                (ILdapPublisher) mappers.publisher, cert);

        // set the ldap published flag.
        setPublishedFlag(cert.getSerialNumber(), false);
    }

    /**
     * set published flag - true when published, false when unpublished.
     * not exist means not published.
     */
    public void setPublishedFlag(BigInteger serialNo, boolean published) {

        CAEngine engine = CAEngine.getInstance();
        CertificateRepository certdb = engine.getCertificateRepository();

        try {
            CertRecord certRec = certdb.readCertificateRecord(serialNo);
            MetaInfo metaInfo = certRec.getMetaInfo();

            if (metaInfo == null) {
                metaInfo = new MetaInfo();
            }
            metaInfo.set(
                    CertRecord.META_LDAPPUBLISH, String.valueOf(published));
            ModificationSet modSet = new ModificationSet();

            modSet.add(CertRecord.ATTR_META_INFO,
                    Modification.MOD_REPLACE, metaInfo);
            certdb.modifyCertificateRecord(serialNo, modSet);

        } catch (EBaseException e) {
            logger.warn("LdapPublishModule: Cannot mark cert 0x" + serialNo.toString(16) + " published as " + published + " in the ldap directory");
            logger.warn("LdapPublishModule: Cert Record not found: " + e.getMessage(), e);
        }
    }

    public LDAPConnection getConn() throws ELdapException {
        return mLdapConnFactory.getConn();
    }

    public void returnConn(LDAPConnection conn) throws ELdapException {
        mLdapConnFactory.returnConn(conn);
    }

    public void publish(ILdapMapper mapper, ILdapPublisher publisher,
            X509Certificate cert)
            throws ELdapException {
        LDAPConnection conn = null;

        try {
            String dirdn = null;
            String result = null;

            conn = mLdapConnFactory.getConn();
            if (mapper == null) { // use the cert's subject name exactly
                dirdn = cert.getSubjectDN().toString();
                logger.debug("no mapper found. Using subject name exactly: " + cert.getSubjectDN());
            } else {
                result = mapper.map(conn, cert);
                dirdn = result;
                if (dirdn == null) {
                    logger.error(CMS.getLogMessage("CMSCORE_LDAP_PUBLISH_NOT_MATCH",
                                    cert.getSerialNumber().toString(16),
                                    cert.getSubjectDN().toString()));
                    throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_MATCH",
                            cert.getSubjectDN().toString()));
                }
            }
            publisher.publish(conn, dirdn, cert);
        } finally {
            if (conn != null) {
                mLdapConnFactory.returnConn(conn);
            }
        }
    }

    public void unpublish(ILdapMapper mapper, ILdapPublisher publisher,
            X509Certificate cert)
            throws ELdapException {
        LDAPConnection conn = null;

        try {
            String dirdn = null;
            String result = null;

            conn = mLdapConnFactory.getConn();
            if (mapper == null) { // use the cert's subject name exactly
                dirdn = cert.getSubjectDN().toString();
            } else {
                result = mapper.map(conn, cert);
                dirdn = result;
                if (dirdn == null) {
                    logger.error(CMS.getLogMessage("CMSCORE_LDAP_PUBLISH_NOT_MATCH",
                                    cert.getSerialNumber().toString(16),
                                    cert.getSubjectDN().toString()));
                    throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_MATCH",
                            cert.getSubjectDN().toString()));
                }
            }
            publisher.unpublish(conn, dirdn, cert);
        } finally {
            if (conn != null) {
                mLdapConnFactory.returnConn(conn);
            }
        }
    }

    /**
     * publishes a crl by mapping the issuer name in the crl to an entry
     * and publishing it there. entry must be a certificate authority.
     */
    public void publish(X509CRLImpl crl)
            throws ELdapException {

        LdapMappers mappers = getMappers(PROP_TYPE_CRL);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for crl is null");
            return;
        }

        LDAPConnection conn = null;
        String dn = null;

        try {
            String result = null;

            conn = mLdapConnFactory.getConn();
            if (mappers.mapper == null) {
                dn = ((X500Name) crl.getIssuerDN()).toLdapDNString();
            } else {
                result = ((ILdapMapper) mappers.mapper).map(conn, crl);
                dn = result;
                if (dn == null) {
                    logger.error(CMS.getLogMessage("CMSCORE_LDAP_CRL_NOT_MATCH"));
                    throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_MATCH",
                            crl.getIssuerDN().toString()));
                }
            }
            ((ILdapPublisher) mappers.publisher).publish(conn, dn, crl);

        } catch (ELdapException e) {
            logger.error("Error publishing CRL to " + dn + ": " + e.getMessage(), e);
            throw e;

        } catch (IOException e) {
            logger.error("Error publishing CRL to " + dn + ": " + e.getMessage(), e);
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_GET_ISSUER_FROM_CRL_FAILED", ""));

        } finally {
            if (conn != null) {
                mLdapConnFactory.returnConn(conn);
            }
        }
    }

    /**
     * publishes a crl by mapping the issuer name in the crl to an entry
     * and publishing it there. entry must be a certificate authority.
     */
    public void publish(String dn, X509CRL crl)
            throws ELdapException {
        LdapMappers mappers = getMappers(PROP_TYPE_CRL);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for crl is null");
            return;
        }

        LDAPConnection conn = null;

        try {
            conn = mLdapConnFactory.getConn();
            ((ILdapPublisher) mappers.publisher).publish(conn, dn, crl);

        } catch (ELdapException e) {
            logger.error("Error publishing CRL to " + dn + ": " + e.getMessage(), e);
            throw e;

        } finally {
            if (conn != null) {
                mLdapConnFactory.returnConn(conn);
            }
        }
    }
}

class LdapMappers {
    public LdapMappers(ILdapPlugin aMapper, ILdapPlugin aPublisher) {
        mapper = aMapper;
        publisher = aPublisher;
    }

    public ILdapPlugin mapper = null;
    public ILdapPlugin publisher = null;
}

class HandleEnrollment implements IRequestListener {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HandleEnrollment.class);

    LdapPublishModule mModule = null;

    public HandleEnrollment(LdapPublishModule module) {
        mModule = module;
    }

    @Override
    public void set(String name, String val) {
    }

    @Override
    public void init(ISubsystem sub, ConfigStore config) throws EBaseException {
    }

    @Override
    public void accept(Request r) {
        logger.debug("handling publishing for enrollment request id " + r.getRequestId());

        // in case it's not meant for us
        if (r.getExtDataInInteger(Request.RESULT) == null)
            return;

        // check if request failed.
        if ((r.getExtDataInInteger(Request.RESULT)).equals(Request.RES_ERROR)) {
            logger.debug("Request errored. " +
                    "Nothing to publish for enrollment request id " +
                    r.getRequestId());
            return;
        }
        logger.debug("Checking publishing for request " +
                r.getRequestId());
        // check if issued certs is set.
        X509CertImpl[] certs = r.getExtDataInCertArray(Request.ISSUED_CERTS);

        if (certs == null || certs.length == 0 || certs[0] == null) {
            logger.debug("No certs to publish for request id " + r.getRequestId());
            return;
        }

        // get mapper and publisher for client certs.
        LdapMappers mappers =
                mModule.getMappers(LdapPublishModule.PROP_TYPE_CLIENT);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("In publishing: No publisher for type " +
                            LdapPublishModule.PROP_TYPE_CLIENT);
            return;
        }

        // publish
        Integer results[] = new Integer[certs.length];

        for (int i = 0; i < certs.length; i++) {
            try {
                if (certs[i] == null)
                    continue;
                mModule.publish((ILdapMapper) mappers.mapper,
                        (ILdapPublisher) mappers.publisher, certs[i]);
                results[i] = Request.RES_SUCCESS;
                logger.debug("Published cert serial no 0x" + certs[i].getSerialNumber().toString(16));
                mModule.setPublishedFlag(certs[i].getSerialNumber(), true);

            } catch (ELdapException e) {
                logger.warn(CMS.getLogMessage("CMSCORE_LDAP_CERT_NOT_PUBLISH",
                                certs[i].getSerialNumber().toString(16), e.toString()), e);
                results[i] = Request.RES_ERROR;
            }
            r.setExtData("ldapPublishStatus", results);
        }
    }
}

class HandleRenewal implements IRequestListener {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HandleRenewal.class);

    private LdapPublishModule mModule = null;

    public HandleRenewal(LdapPublishModule module) {
        mModule = module;
    }

    @Override
    public void init(ISubsystem sub, ConfigStore config) throws EBaseException {
    }

    @Override
    public void set(String name, String val) {
    }

    @Override
    public void accept(Request r) {
        // Note we do not remove old certs from directory during renewal
        X509CertImpl[] certs = r.getExtDataInCertArray(Request.ISSUED_CERTS);

        if (certs == null || certs.length == 0) {
            logger.debug("no certs to publish for renewal " +
                    "request " + r.getRequestId());
            return;
        }
        Integer results[] = new Integer[certs.length];
        X509CertImpl cert = null;

        // get mapper and publisher for cert type.
        LdapMappers mappers =
                mModule.getMappers(LdapPublishModule.PROP_TYPE_CLIENT);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for " + LdapPublishModule.PROP_TYPE_CLIENT + " is null");
            return;
        }

        boolean error = false;

        for (int i = 0; i < certs.length; i++) {
            cert = certs[i];
            if (cert == null)
                continue; // there was an error issuing this cert.
            try {
                mModule.publish((ILdapMapper) mappers.mapper,
                        (ILdapPublisher) mappers.publisher, cert);
                results[i] = Request.RES_SUCCESS;
                logger.info("Published cert serial no 0x" + cert.getSerialNumber().toString(16));

            } catch (ELdapException e) {
                error = true;
                logger.warn(CMS.getLogMessage("CMSCORE_LDAP_CERT_NOT_PUBLISH",
                                cert.getSerialNumber().toString(16), e.getMessage()), e);
                results[i] = Request.RES_ERROR;
            }
        }

        r.setExtData("ldapPublishStatus", results);
        r.setExtData("ldapPublishOverAllStatus",
                (error == true ? Request.RES_ERROR : Request.RES_SUCCESS));
    }
}

class HandleRevocation implements IRequestListener {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HandleRevocation.class);

    private LdapPublishModule mModule = null;

    public HandleRevocation(LdapPublishModule module) {
        mModule = module;
    }

    @Override
    public void init(ISubsystem sub, ConfigStore config) throws EBaseException {
    }

    @Override
    public void set(String name, String val) {
    }

    @Override
    public void accept(Request r) {
        logger.debug("Handle publishing for revoke request id " + r.getRequestId());

        // get fields in request.
        X509CertImpl[] revcerts = r.getExtDataInCertArray(Request.OLD_CERTS);

        if (revcerts == null || revcerts.length == 0 || revcerts[0] == null) {
            // no certs in revoke.
            logger.debug("Nothing to unpublish for revocation " +
                            "request " + r.getRequestId());
            return;
        }

        // get mapper and publisher for cert type.
        LdapMappers mappers =
                mModule.getMappers(LdapPublishModule.PROP_TYPE_CLIENT);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for " + LdapPublishModule.PROP_TYPE_CLIENT + " is null");
            return;
        }

        boolean error = false;
        Integer results[] = new Integer[revcerts.length];

        for (int i = 0; i < revcerts.length; i++) {
            X509CertImpl cert = revcerts[i];

            results[i] = Request.RES_ERROR;
            try {
                mModule.unpublish((ILdapMapper) mappers.mapper,
                        (ILdapPublisher) mappers.publisher, cert);
                results[i] = Request.RES_SUCCESS;
                logger.debug("Unpublished cert serial no 0x" + cert.getSerialNumber().toString(16));

            } catch (ELdapException e) {
                error = true;
                logger.warn(CMS.getLogMessage("CMSCORE_LDAP_CERT_NOT_UNPUBLISH",
                                cert.getSerialNumber().toString(16), e.getMessage()), e);
            }
        }
        r.setExtData("ldapPublishStatus", results);
        r.setExtData("ldapPublishOverAllStatus",
                (error == true ? Request.RES_ERROR : Request.RES_SUCCESS));
    }
}

class HandleUnrevocation implements IRequestListener {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HandleUnrevocation.class);

    private LdapPublishModule mModule = null;

    public HandleUnrevocation(LdapPublishModule module) {
        mModule = module;
    }

    @Override
    public void set(String name, String val) {
    }

    @Override
    public void init(ISubsystem sub, ConfigStore config) throws EBaseException {
    }

    @Override
    public void accept(Request r) {
        logger.debug("Handle publishing for unrevoke request id " + r.getRequestId());

        // get fields in request.
        X509CertImpl[] certs = r.getExtDataInCertArray(Request.OLD_CERTS);

        if (certs == null || certs.length == 0 || certs[0] == null) {
            // no certs in unrevoke.
            logger.debug("Nothing to publish for unrevocation " +
                            "request " + r.getRequestId());
            return;
        }

        // get mapper and publisher for cert type.
        LdapMappers mappers =
                mModule.getMappers(LdapPublishModule.PROP_TYPE_CLIENT);

        if (mappers == null || mappers.publisher == null) {
            logger.debug("publisher for " + LdapPublishModule.PROP_TYPE_CLIENT + " is null");
            return;
        }

        boolean error = false;
        Integer results[] = new Integer[certs.length];

        for (int i = 0; i < certs.length; i++) {
            results[i] = Request.RES_ERROR;
            try {
                mModule.publish((ILdapMapper) mappers.mapper,
                        (ILdapPublisher) mappers.publisher, certs[i]);
                results[i] = Request.RES_SUCCESS;
                logger.debug("Unpublished cert serial no 0x" + certs[i].getSerialNumber().toString(16));

            } catch (ELdapException e) {
                error = true;
                logger.warn(CMS.getLogMessage("CMSCORE_LDAP_CERT_NOT_UNPUBLISH",
                                certs[i].getSerialNumber().toString(16), e.getMessage()), e);
            }
        }
        r.setExtData("ldapPublishStatus", results);
        r.setExtData("ldapPublishOverAllStatus",
                (error == true ? Request.RES_ERROR : Request.RES_SUCCESS));
    }

}
