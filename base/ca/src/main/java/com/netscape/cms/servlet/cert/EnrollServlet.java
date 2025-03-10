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
package com.netscape.cms.servlet.cert;

import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dogtag.util.cert.CertUtil;
import org.dogtagpki.legacy.policy.IPolicyProcessor;
import org.dogtagpki.server.authentication.AuthToken;
import org.dogtagpki.server.authorization.AuthzToken;
import org.dogtagpki.server.ca.CAEngine;
import org.dogtagpki.server.ca.CAEngineConfig;
import org.mozilla.jss.netscape.security.extensions.CertInfo;
import org.mozilla.jss.netscape.security.pkcs.PKCS10;
import org.mozilla.jss.netscape.security.util.Utils;
import org.mozilla.jss.netscape.security.x509.AlgorithmId;
import org.mozilla.jss.netscape.security.x509.CertificateAlgorithmId;
import org.mozilla.jss.netscape.security.x509.CertificateX509Key;
import org.mozilla.jss.netscape.security.x509.X509CertImpl;
import org.mozilla.jss.netscape.security.x509.X509CertInfo;
import org.mozilla.jss.netscape.security.x509.X509Key;

import com.netscape.ca.CertificateAuthority;
import com.netscape.certsrv.authorization.EAuthzAccessDenied;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.IArgBlock;
import com.netscape.certsrv.base.KeyGenInfo;
import com.netscape.certsrv.logging.AuditEvent;
import com.netscape.certsrv.logging.AuditFormat;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.certsrv.logging.event.CertRequestProcessedEvent;
import com.netscape.certsrv.request.RequestStatus;
import com.netscape.cms.servlet.base.CMSServlet;
import com.netscape.cms.servlet.common.CMSGateway;
import com.netscape.cms.servlet.common.CMSRequest;
import com.netscape.cms.servlet.common.ECMSGWException;
import com.netscape.cms.servlet.common.ICMSTemplateFiller;
import com.netscape.cms.servlet.processors.CMCProcessor;
import com.netscape.cms.servlet.processors.CRMFProcessor;
import com.netscape.cms.servlet.processors.KeyGenProcessor;
import com.netscape.cms.servlet.processors.PKCS10Processor;
import com.netscape.cms.servlet.processors.PKIProcessor;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.authentication.AuthSubsystem;
import com.netscape.cmscore.base.ConfigStore;
import com.netscape.cmscore.cert.CertUtils;
import com.netscape.cmscore.dbs.CertRecord;
import com.netscape.cmscore.dbs.CertRecordList;
import com.netscape.cmscore.dbs.CertificateRepository;
import com.netscape.cmscore.request.CertRequestRepository;
import com.netscape.cmscore.request.Request;
import com.netscape.cmscore.usrgrp.Group;
import com.netscape.cmscore.usrgrp.UGSubsystem;
import com.netscape.cmscore.usrgrp.User;

/**
 * Submit a Certificate Enrollment request
 *
 * @version $Revision$, $Date$
 */
public class EnrollServlet extends CMSServlet {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EnrollServlet.class);
    private static final long serialVersionUID = -6983729702665630013L;

    public final static String ADMIN_ENROLL_SERVLET_ID = "caadminEnroll";

    // enrollment templates.
    public static final String ENROLL_SUCCESS_TEMPLATE = "EnrollSuccess.template";

    // http params
    public static final String OLD_CERT_TYPE = "csrCertType";
    public static final String CERT_TYPE = "certType";
    // same as in ConfigConstant.java
    public static final String REQUEST_FORMAT = "reqFormat";
    public static final String REQUEST_FORMAT_PKCS10 = "PKCS10";
    public static final String REQUEST_FORMAT_CMC = "CMC";
    public static final String REQUEST_CONTENT = "requestContent";
    public static final String SUBJECT_KEYGEN_INFO = "subjectKeyGenInfo";
    public static final String PKCS10_REQUEST = "pkcs10Request";
    public static final String CMC_REQUEST = "cmcRequest";
    public static final String CRMF_REQUEST = "CRMFRequest";
    public static final String SUBJECT_NAME = "subject";
    public static final String CRMF_REQID = "crmfReqId";
    public static final String CHALLENGE_PASSWORD = "challengePhrase";

    private static final String CERT_AUTH_DUAL = "dual";
    private static final String CERT_AUTH_ENCRYPTION = "encryption";
    private static final String CERT_AUTH_SINGLE = "single";
    private static final String CLIENT_ISSUER = "clientIssuer";

    private String mEnrollSuccessTemplate = null;
    private ICMSTemplateFiller mEnrollSuccessFiller = new ImportCertsTemplateFiller();

    CertificateAuthority mCa;
    CertificateRepository mRepository;

    private boolean enforcePop = false;

    private String auditServiceID = ILogger.UNIDENTIFIED;
    private final static String ADMIN_CA_ENROLLMENT_SERVLET =
            "caadminEnroll";
    private final static String AGENT_CA_BULK_ENROLLMENT_SERVLET =
            "cabulkissuance";
    private final static String AGENT_RA_BULK_ENROLLMENT_SERVLET =
            "rabulkissuance";
    private final static String EE_CA_CERT_BASED_ENROLLMENT_SERVLET =
            "cacertbasedenrollment";
    private final static String EE_CA_ENROLLMENT_SERVLET =
            "caenrollment";
    private final static String EE_RA_CERT_BASED_ENROLLMENT_SERVLET =
            "racertbasedenrollment";
    private final static String EE_RA_ENROLLMENT_SERVLET =
            "raenrollment";
    private final static String[] SIGNED_AUDIT_AUTOMATED_REJECTION_REASON = new String[] {

    /* 0 */"automated non-profile cert request rejection:  "
            + "unable to render OLD_CERT_TYPE response",

    /* 1 */"automated non-profile cert request rejection:  "
            + "unable to complete handleEnrollAuditLog() method",

    /* 2 */"automated non-profile cert request rejection:  "
            + "unable to render success template",

    /* 3 */"automated non-profile cert request rejection:  "
            + "indeterminate reason for inability to process "
            + "cert request due to an EBaseException"
        };
    private static final String HEADER = "-----BEGIN NEW CERTIFICATE REQUEST-----";
    private static final String TRAILER = "-----END NEW CERTIFICATE REQUEST-----";

    public EnrollServlet() {
        super();
    }

    /**
     * initialize the servlet.
     * <p>
     * the following parameters are read from the servlet config:
     * <ul>
     * <li>CMSServlet.PROP_ID - ID for signed audit log messages
     * <li>CMSServlet.PROP_SUCCESS_TEMPLATE - success template file
     *
     * @param sc servlet configuration, read from the web.xml file
     */
    @Override
    public void init(ServletConfig sc) throws ServletException {
        try {
            super.init(sc);

            logger.debug("EnrollServlet: In Enroll Servlet init!");

            CAEngine engine = CAEngine.getInstance();
            CAEngineConfig configStore = engine.getConfig();

            try {
                String PKI_Subsystem = configStore.getString("subsystem.0.id",
                                                              null);

                // CMS 6.1 began utilizing the "Certificate Profiles" framework
                // instead of the legacy "Certificate Policies" framework.
                //
                // Beginning with CS 8.1, to meet the Common Criteria
                // evaluation performed on this version of the product, it
                // was determined that this legacy "Certificate Policies"
                // framework would be deprecated and disabled by default
                // (see Bugzilla Bug #472597).
                //
                // NOTE:  The "Certificate Policies" framework ONLY applied to
                //        to CA, KRA, and legacy RA (pre-CMS 7.0) subsystems.
                //
                //        Further, the "EnrollServlet.java" servlet is ONLY
                //        used by the CA for the following:
                //
                //        SERVLET-NAME           URL-PATTERN
                //        ====================================================
                //        caadminEnroll          ca/admin/ca/adminEnroll.html
                //        cabulkissuance         ca/agent/ca/bulkissuance.html
                //        cacertbasedenrollment  ca/certbasedenrollment.html
                //        caenrollment           ca/enrollment.html
                //
                //        The "EnrollServlet.java" servlet is NOT used by
                //        the KRA.
                //
                if (PKI_Subsystem.trim().equalsIgnoreCase("ca")) {
                    String policyStatus = PKI_Subsystem.trim().toLowerCase()
                                        + "." + "Policy"
                                        + "." + IPolicyProcessor.PROP_ENABLE;

                    if (configStore.getBoolean(policyStatus, true) == true) {
                        // NOTE:  If "<subsystem>.Policy.enable=<boolean>"
                        //        is missing, then the referenced instance
                        //        existed prior to this name=value pair
                        //        existing in its 'CS.cfg' file, and thus
                        //        we err on the side that the user may
                        //        still need to use the policy framework.
                        logger.debug("EnrollServlet::init Certificate "
                                 + "Policy Framework (deprecated) "
                                 + "is ENABLED");
                    } else {
                        // CS 8.1 Default:  <subsystem>.Policy.enable=false
                        logger.debug("EnrollServlet::init Certificate "
                                 + "Policy Framework (deprecated) "
                                 + "is DISABLED");
                        return;
                    }
                }
            } catch (EBaseException e) {
                throw new ServletException("EnrollServlet::init - "
                                          + "EBaseException:  "
                                          + "Unable to initialize "
                                          + "Certificate Policy Framework "
                                          + "(deprecated)");
            }

            // override success template to allow direct import of keygen certs.
            mTemplates.remove(CMSRequest.SUCCESS);

            try {
                // determine the service ID for signed audit log messages
                String id = sc.getInitParameter(CMSServlet.PROP_ID);

                if (id != null) {
                    if (!(auditServiceID.equals(
                                ADMIN_CA_ENROLLMENT_SERVLET))
                            && !(auditServiceID.equals(
                                    AGENT_CA_BULK_ENROLLMENT_SERVLET))
                            && !(auditServiceID.equals(
                                    AGENT_RA_BULK_ENROLLMENT_SERVLET))
                            && !(auditServiceID.equals(
                                    EE_CA_CERT_BASED_ENROLLMENT_SERVLET))
                            && !(auditServiceID.equals(
                                    EE_CA_ENROLLMENT_SERVLET))
                            && !(auditServiceID.equals(
                                    EE_RA_CERT_BASED_ENROLLMENT_SERVLET))
                            && !(auditServiceID.equals(
                                    EE_RA_ENROLLMENT_SERVLET))) {
                        auditServiceID = ILogger.UNIDENTIFIED;
                    } else {
                        auditServiceID = id.trim();
                    }
                }

                mEnrollSuccessTemplate = sc.getInitParameter(
                            CMSServlet.PROP_SUCCESS_TEMPLATE);
                if (mEnrollSuccessTemplate == null)
                    mEnrollSuccessTemplate = ENROLL_SUCCESS_TEMPLATE;
                String fillername = sc.getInitParameter(
                        PROP_SUCCESS_TEMPLATE_FILLER);

                if (fillername != null) {
                    ICMSTemplateFiller filler = newFillerObject(fillername);

                    if (filler != null)
                        mEnrollSuccessFiller = filler;
                }

                // cfu
                mCa = engine.getCA();

                init_testbed_hack(mConfig);

            } catch (Exception e) {
                logger.warn(CMS.getLogMessage("CMSGW_IMP_INIT_SERV_ERR", e.toString(), mId), e);
            }
        } catch (ServletException eAudit1) {
            // rethrow caught exception
            throw eAudit1;
        }
    }

    /**
     * XXX (SHOULD CHANGE TO READ FROM Servletconfig)
     * Getter method to see if Proof of Posession checking is enabled.
     * this value is set in the CMS.cfg filem with the parameter
     * "enrollment.enforcePop". It defaults to false
     *
     * @return true if user is required to Prove that they possess the
     *         private key corresponding to the public key in the certificate
     *         request they are submitting
     */
    public boolean getEnforcePop() {
        return enforcePop;
    }

    /**
     * Process the HTTP request.
     * <UL>
     * <LI>If the request is coming through the admin port, it is only allowed to continue if 'admin enrollment' is
     * enabled in the CMS.cfg file
     * <LI>If the CMS.cfg parameter useThreadNaming is true, the current thread is renamed with more information about
     * the current request ID
     * <LI>The request is preprocessed, then processed further in one of the cert request processor classes:
     * KeyGenProcessor, PKCS10Processor, CMCProcessor, CRMFProcessor
     * </UL>
     *
     * @param cmsReq the object holding the request and response information
     */
    @Override
    protected void process(CMSRequest cmsReq)
            throws EBaseException {
        // SPECIAL CASE:
        // if it is adminEnroll servlet,check if it's enabled
        if (mId.equals(ADMIN_ENROLL_SERVLET_ID) &&
                !CMSGateway.getEnableAdminEnroll()) {
            logger.error(CMS.getLogMessage("ADMIN_SRVLT_ENROLL_ACCESS_AFTER_SETUP"));
            throw new ECMSGWException(
                    CMS.getUserMessage("CMS_GW_REDIRECTING_ADMINENROLL_ERROR",
                            "Attempt to access adminEnroll after already setup."));
        }

        processX509(cmsReq);
    }

    private boolean getCertAuthEnrollStatus(IArgBlock httpParams) {

        /*
         * === certAuth based enroll ===
         * "certAuthEnroll" is on.
         * "certauthEnrollType can be one of the three:
         *               single - it's for single cert enrollment
         *               dual - it's for dual certs enrollment
         *               encryption - getting the encryption cert only via
         *                    authentication of the signing cert
         *                    (crmf or keyGenInfo)
         */
        boolean certAuthEnroll = false;

        String certAuthEnrollOn =
                httpParams.getValueAsString("certauthEnroll", null);

        if ((certAuthEnrollOn != null) && (certAuthEnrollOn.equals("on"))) {
            certAuthEnroll = true;
            logger.debug("EnrollServlet: certAuthEnroll is on");
        }

        return certAuthEnroll;

    }

    private String getCertAuthEnrollType(IArgBlock httpParams, boolean certAuthEnroll)
            throws EBaseException {

        String certauthEnrollType = null;

        if (certAuthEnroll == true) {
            certauthEnrollType =
                    httpParams.getValueAsString("certauthEnrollType", null);
            if (certauthEnrollType != null) {
                if (certauthEnrollType.equals("dual")) {
                    logger.debug("EnrollServlet: certauthEnrollType is dual");
                } else if (certauthEnrollType.equals("encryption")) {
                    logger.debug("EnrollServlet: certauthEnrollType is encryption");
                } else if (certauthEnrollType.equals("single")) {
                    logger.debug("EnrollServlet: certauthEnrollType is single");
                } else {
                    logger.error(CMS.getLogMessage("CMSGW_INVALID_CERTAUTH_ENROLL_TYPE_1", certauthEnrollType));
                    throw new ECMSGWException(
                            CMS.getUserMessage("CMS_GW_INVALID_CERTAUTH_ENROLL_TYPE"));
                }
            } else {
                logger.error(CMS.getLogMessage("MSGW_MISSING_CERTAUTH_ENROLL_TYPE"));
                throw new ECMSGWException(
                        CMS.getUserMessage("CMS_GW_MISSING_CERTAUTH_ENROLL_TYPE"));
            }
        }

        return certauthEnrollType;

    }

    private boolean checkClientCertSigningOnly(X509Certificate sslClientCert)
            throws EBaseException {
        if (!CertUtils.isSigningCert((X509CertImpl) sslClientCert) ||
                CertUtils.isSigningCert((X509CertImpl) sslClientCert) &&
                CertUtils.isEncryptionCert((X509CertImpl) sslClientCert)) {

            // either it's not a signing cert, or it's a dual cert
            logger.error(CMS.getLogMessage("CMSGW_INVALID_CERT_TYPE"));
            throw new ECMSGWException(
                    CMS.getUserMessage("CMS_GW_INVALID_CERT_TYPE"));
        }

        return true;
    }

    private X509CertInfo[] handleCertAuthDual(X509CertInfo certInfo, AuthToken authToken,
            X509Certificate sslClientCert,
            CertificateAuthority mCa, String certBasedOldSubjectDN,
            BigInteger certBasedOldSerialNum)
            throws EBaseException {

        logger.debug("EnrollServlet: In handleCertAuthDual!");

        if (mCa == null) {
            logger.error(CMS.getLogMessage("CMSGW_NOT_A_CA"));
            throw new ECMSGWException(
                    CMS.getUserMessage("CMS_GW_NOT_A_CA"));
        }

        CAEngine engine = CAEngine.getInstance();
        CertificateRepository cr = engine.getCertificateRepository();

        // first, make sure the client cert is indeed a
        // signing only cert

        try {

            checkClientCertSigningOnly(sslClientCert);
        } catch (ECMSGWException e) {

            throw new ECMSGWException(e.toString());

        }

        X509Key key = null;

        // for signing cert
        key = (X509Key) sslClientCert.getPublicKey();
        try {
            certInfo.set(X509CertInfo.KEY, new CertificateX509Key(key));

        } catch (CertificateException e) {
            logger.error(CMS.getLogMessage("CMSGW_FAILED_SET_KEY_FROM_CERT_AUTH_ENROLL_1", e.toString()), e);
            throw new ECMSGWException(CMS.getUserMessage("CMS_GW_SET_KEY_FROM_CERT_AUTH_ENROLL_FAILED", e.toString()), e);

        } catch (IOException e) {
            logger.error(CMS.getLogMessage("CMSGW_FAILED_SET_KEY_FROM_CERT_AUTH_ENROLL_IO", e.toString()), e);
            throw new ECMSGWException(CMS.getUserMessage("CMS_GW_SET_KEY_FROM_CERT_AUTH_ENROLL_FAILED", e.toString()), e);
        }

        String filter =
                "(&(x509cert.subject="
                        + certBasedOldSubjectDN + ")(!(x509cert.serialNumber=" + certBasedOldSerialNum
                        + "))(certStatus=VALID))";
        CertRecordList list = cr.findCertRecordsInList(filter, null, 10);
        int size = list.getSize();
        Enumeration<CertRecord> en = list.getCertRecords(0, size - 1);

        logger.debug("EnrollServlet: signing cert filter " + filter);

        if (!en.hasMoreElements()) {
            logger.warn("EnrollServlet: pairing encryption cert not found!");
            return null;
            // pairing encryption cert not found
        } else {
            X509CertInfo encCertInfo = new CertInfo();
            X509CertInfo[] cInfoArray = new X509CertInfo[] { certInfo,
                    encCertInfo };
            int i = 1;

            boolean encCertFound = false;

            while (en.hasMoreElements()) {
                CertRecord record = en.nextElement();
                X509CertImpl cert = record.getCertificate();

                // if not encryption cert only, try next one
                if (!CertUtils.isEncryptionCert(cert) ||
                        CertUtils.isEncryptionCert(cert) &&
                        CertUtils.isSigningCert(cert)) {

                    logger.debug("EnrollServlet: Not encryption only cert, will try next one.");
                    continue;
                }

                key = (X509Key) cert.getPublicKey();
                logger.debug("EnrollServlet: Found key for encryption cert.");
                encCertFound = true;

                try {
                    encCertInfo = (X509CertInfo)
                            cert.get(
                                    X509CertImpl.NAME + "." + X509CertImpl.INFO);

                } catch (CertificateParsingException ex) {
                    logger.error(CMS.getLogMessage("CMSGW_MISSING_CERTINFO_ENCRYPT_CERT"), ex);
                    throw new ECMSGWException(CMS.getUserMessage("CMS_GW_MISSING_CERTINFO"), ex);
                }

                try {
                    encCertInfo.set(X509CertInfo.KEY, new CertificateX509Key(key));

                } catch (CertificateException e) {
                    logger.error(CMS.getLogMessage("CMSGW_FAILED_SET_KEY_FROM_CERT_AUTH_ENROLL_1", e.toString()), e);
                    throw new ECMSGWException(CMS.getUserMessage("CMS_GW_SET_KEY_FROM_CERT_AUTH_ENROLL_FAILED", e.toString()), e);

                } catch (IOException e) {
                    logger.error(CMS.getLogMessage("CMSGW_FAILED_SET_KEY_FROM_CERT_AUTH_ENROLL_1", e.toString()), e);
                    throw new ECMSGWException(CMS.getUserMessage("CMS_GW_SET_KEY_FROM_CERT_AUTH_ENROLL_FAILED", e.toString()), e);
                }

                logger.debug("EnrollServlet: About to fillCertInfoFromAuthToken!");
                PKIProcessor.fillCertInfoFromAuthToken(encCertInfo, authToken);

                cInfoArray[i++] = encCertInfo;
                break;

            }
            if (encCertFound == false) {
                logger.warn("EnrollServlet: Leaving because Enc Cert not found.");
                return null;
            }

            logger.debug("EnrollServlet: returning cInfoArray of length " + cInfoArray.length);
            return cInfoArray;
        }

    }

    private boolean handleEnrollAuditLog(Request req, CMSRequest cmsReq, String authMgr, AuthToken authToken,
            X509CertInfo certInfo, long startTime)
            throws EBaseException {
        //for audit log

        String initiative = null;
        String agentID = null;

        if (authToken == null) {
            // request is from eegateway, so fromUser.
            initiative = AuditFormat.FROMUSER;
        } else {
            agentID = authToken.getInString("userid");
            initiative = AuditFormat.FROMAGENT + " agentID: " + agentID;
        }

        // if service not complete return standard templates.
        RequestStatus status = req.getRequestStatus();

        if (status != RequestStatus.COMPLETE) {
            cmsReq.setIRequestStatus(); // set status acc. to Request status.
            // audit log the status
            try {
                if (status == RequestStatus.REJECTED) {
                    Vector<String> messages = req.getExtDataInStringVector(Request.ERRORS);

                    if (messages != null) {
                        Enumeration<String> msgs = messages.elements();
                        StringBuffer wholeMsg = new StringBuffer();

                        while (msgs.hasMoreElements()) {
                            wholeMsg.append("\n");
                            wholeMsg.append(msgs.nextElement());
                        }
                        logger.info(
                                AuditFormat.ENROLLMENTFORMAT,
                                req.getRequestId().toHexString(),
                                initiative,
                                authMgr,
                                status.toString(),
                                certInfo.get(X509CertInfo.SUBJECT),
                                " violation: " + wholeMsg
                        );
                    } else { // no policy violation, from agent
                        logger.info(
                                AuditFormat.ENROLLMENTFORMAT,
                                req.getRequestId().toHexString(),
                                initiative,
                                authMgr,
                                status.toString(),
                                certInfo.get(X509CertInfo.SUBJECT),
                                ""
                        );
                    }
                } else { // other imcomplete status
                    long endTime = new Date().getTime();

                    logger.info(
                            AuditFormat.ENROLLMENTFORMAT,
                            req.getRequestId().toHexString(),
                            initiative,
                            authMgr,
                            status.toString(),
                            certInfo.get(X509CertInfo.SUBJECT) + " time: " + (endTime - startTime),
                            ""
                    );
                }

            } catch (IOException e) {
                logger.warn(CMS.getLogMessage("CMSGW_CANT_GET_CERT_SUBJ_AUDITING", e.toString()), e);

            } catch (CertificateException e) {
                logger.warn(CMS.getLogMessage("CMSGW_CANT_GET_CERT_SUBJ_AUDITING", e.toString()), e);
            }

            return false;
        }
        // if service error use standard error templates.
        Integer result = req.getExtDataInInteger(Request.RESULT);

        if (result.equals(Request.RES_ERROR)) {

            cmsReq.setStatus(CMSRequest.ERROR);
            cmsReq.setError(req.getExtDataInString(Request.ERROR));
            String[] svcErrors =
                    req.getExtDataInStringArray(Request.SVCERRORS);

            if (svcErrors != null && svcErrors.length > 0) {
                for (int i = 0; i < svcErrors.length; i++) {
                    String err = svcErrors[i];

                    if (err != null) {
                        //System.out.println(
                        //"revocation servlet: setting error description "+
                        //err.toString());
                        cmsReq.setErrorDescription(err);
                        // audit log the error
                        try {
                            logger.info(
                                    AuditFormat.ENROLLMENTFORMAT,
                                    req.getRequestId().toHexString(),
                                    initiative,
                                    authMgr,
                                    "completed with error: " + err,
                                    certInfo.get(X509CertInfo.SUBJECT),
                                    ""
                            );

                        } catch (IOException e) {
                            logger.warn(CMS.getLogMessage("CMSGW_CANT_GET_CERT_SUBJ_AUDITING", e.toString()), e);

                        } catch (CertificateException e) {
                            logger.warn(CMS.getLogMessage("CMSGW_CANT_GET_CERT_SUBJ_AUDITING", e.toString()), e);
                        }

                    }
                }
            }
            return false;

        }

        return true;

    }

    /**
     * Process X509 certificate enrollment request
     * <P>
     *
     * (Certificate Request - either an "admin" cert request for an admin certificate, an "agent" cert request for
     * "bulk enrollment", or an "EE" standard cert request)
     * <P>
     *
     * (Certificate Request Processed - either an automated "admin" non-profile based CA admin cert acceptance, an
     * automated "admin" non-profile based CA admin cert rejection, an automated "EE" non-profile based cert acceptance,
     * or an automated "EE" non-profile based cert rejection)
     * <P>
     *
     * <ul>
     * <li>signed.audit LOGGING_SIGNED_AUDIT_NON_PROFILE_CERT_REQUEST used when a non-profile cert request is made
     * (before approval process)
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CERT_REQUEST_PROCESSED used when a certificate request has just been
     * through the approval process
     * </ul>
     *
     * @param cmsReq a certificate enrollment request
     * @exception EBaseException an error has occurred
     */
    protected void processX509(CMSRequest cmsReq)
            throws EBaseException {

        CAEngine engine = CAEngine.getInstance();
        CAEngineConfig configStore = engine.getConfig();

        String auditMessage = null;
        String auditSubjectID = auditSubjectID();
        String auditRequesterID = ILogger.UNIDENTIFIED;
        String auditCertificateSubjectName = ILogger.SIGNED_AUDIT_EMPTY_VALUE;
        String id = null;

        // define variables common to try-catch-blocks
        long startTime = 0;
        IArgBlock httpParams = null;
        HttpServletRequest httpReq = null;
        AuthToken authToken = null;
        AuthzToken authzToken = null;
        Request req = null;
        X509CertInfo certInfo = null;

        /* XXX shouldn't we read this from ServletConfig at init time? */
        enforcePop = configStore.getBoolean("enrollment.enforcePop", false);
        logger.debug("EnrollServlet: enforcePop " + enforcePop);

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            startTime = new Date().getTime();
            httpParams = cmsReq.getHttpParams();
            httpReq = cmsReq.getHttpReq();
            if (mAuthMgr != null) {
                authToken = authenticate(cmsReq);
            }

            try {
                authzToken = authorize(mAclMethod, authToken,
                            mAuthzResourceName, "submit");

            } catch (EAuthzAccessDenied e) {
                logger.warn(CMS.getLogMessage("ADMIN_SRVLT_AUTH_FAILURE", e.toString()), e);

            } catch (Exception e) {
                logger.warn(CMS.getLogMessage("ADMIN_SRVLT_AUTH_FAILURE", e.toString()), e);
            }

            if (authzToken == null) {
                cmsReq.setStatus(CMSRequest.UNAUTHORIZED);

                // store a message in the signed audit log file
                // (either an "admin" cert request for an admin certificate,
                //  an "agent" cert request for "bulk enrollment", or
                //  an "EE" standard cert request)
                auditMessage = CMS.getLogMessage(
                            AuditEvent.NON_PROFILE_CERT_REQUEST,
                            auditSubjectID,
                            ILogger.FAILURE,
                            auditRequesterID,
                            auditServiceID,
                            auditCertificateSubjectName);

                audit(auditMessage);

                return;
            }

            // create enrollment request in request repository
            CertRequestRepository requestRepository = engine.getCertRequestRepository();
            req = requestRepository.createRequest(Request.ENROLLMENT_REQUEST);

            // retrieve the actual "auditRequesterID"
            if (req != null) {
                // overwrite "auditRequesterID" if and only if "id" != null
                id = req.getRequestId().toString();
                if (id != null) {
                    auditRequesterID = id.trim();
                }
            }

            try {
                if (configStore.getBoolean("useThreadNaming", false)) {
                    String currentName = Thread.currentThread().getName();

                    Thread.currentThread().setName(currentName
                            + "-request-"
                            + req.getRequestId().toString()
                            + "-"
                            + (new Date()).getTime());
                }
            } catch (Exception e) {
            }

            /*
             * === certAuth based enroll ===
             * "certAuthEnroll" is on.
             * "certauthEnrollType can be one of the three:
             *       single - it's for single cert enrollment
             *       dual - it's for dual certs enrollment
             *       encryption - getting the encryption cert only via
             *                    authentication of the signing cert
             *                    (crmf or keyGenInfo)
             */
            boolean certAuthEnroll = false;
            String certauthEnrollType = null;

            certAuthEnroll = getCertAuthEnrollStatus(httpParams);

            try {
                if (certAuthEnroll == true) {
                    certauthEnrollType = getCertAuthEnrollType(httpParams,
                                certAuthEnroll);
                }
            } catch (ECMSGWException e) {
                // store a message in the signed audit log file
                // (either an "admin" cert request for an admin certificate,
                //  an "agent" cert request for "bulk enrollment", or
                //  an "EE" standard cert request)
                auditMessage = CMS.getLogMessage(
                            AuditEvent.NON_PROFILE_CERT_REQUEST,
                            auditSubjectID,
                            ILogger.FAILURE,
                            auditRequesterID,
                            auditServiceID,
                            auditCertificateSubjectName);

                audit(auditMessage);

                throw new ECMSGWException(e.toString());
            }

            logger.debug("EnrollServlet: In EnrollServlet.processX509!");
            logger.debug("EnrollServlet: certAuthEnroll " + certAuthEnroll);
            logger.debug("EnrollServlet: certauthEnrollType " + certauthEnrollType);

            String challengePassword = httpParams.getValueAsString(
                    "challengePassword", "");

            cmsReq.setRequest(req);
            saveHttpHeaders(httpReq, req);
            saveHttpParams(httpParams, req);

            X509Certificate sslClientCert = null;

            // cert auth enroll
            String certBasedOldSubjectDN = null;
            BigInteger certBasedOldSerialNum = null;

            // check if request was authenticated, if so set authtoken &
            // certInfo.  also if authenticated, take certInfo from authToken.
            certInfo = null;
            if (certAuthEnroll == true) {
                sslClientCert = getSSLClientCertificate(httpReq);
                if (sslClientCert == null) {
                    logger.error(CMS.getLogMessage("CMSGW_MISSING_SSL_CLIENT_CERT"));

                    // store a message in the signed audit log file
                    // (either an "admin" cert request for an admin certificate,
                    //  an "agent" cert request for "bulk enrollment", or
                    //  an "EE" standard cert request)
                    auditMessage = CMS.getLogMessage(
                                AuditEvent.NON_PROFILE_CERT_REQUEST,
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditRequesterID,
                                auditServiceID,
                                auditCertificateSubjectName);

                    audit(auditMessage);

                    throw new ECMSGWException(
                            CMS.getUserMessage("CMS_GW_MISSING_SSL_CLIENT_CERT"));
                }

                certBasedOldSubjectDN = sslClientCert.getSubjectDN().toString();
                certBasedOldSerialNum = sslClientCert.getSerialNumber();

                logger.debug("EnrollServlet: certBasedOldSubjectDN " + certBasedOldSubjectDN);
                logger.debug("EnrollServlet: certBasedOldSerialNum " + certBasedOldSerialNum);

                // if the cert subject name is NOT MISSING, retrieve the
                // actual "auditCertificateSubjectName" and "normalize" it
                if (certBasedOldSubjectDN != null) {
                    // NOTE:  This is ok even if the cert subject name
                    //        is "" (empty)!
                    auditCertificateSubjectName = certBasedOldSubjectDN.trim();
                }

                try {
                    certInfo = (X509CertInfo)
                            ((X509CertImpl) sslClientCert).get(
                                    X509CertImpl.NAME + "." + X509CertImpl.INFO);

                } catch (CertificateParsingException ex) {
                    logger.error(CMS.getLogMessage("CMSGW_MISSING_CERTINFO"), ex);

                    // store a message in the signed audit log file
                    // (either an "admin" cert request for an admin certificate,
                    //  an "agent" cert request for "bulk enrollment", or
                    //  an "EE" standard cert request)
                    auditMessage = CMS.getLogMessage(
                                AuditEvent.NON_PROFILE_CERT_REQUEST,
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditRequesterID,
                                auditServiceID,
                                auditCertificateSubjectName);

                    audit(auditMessage);

                    throw new ECMSGWException(
                            CMS.getUserMessage(getLocale(httpReq), "CMS_GW_MISSING_CERTINFO"));
                }
            } else {
                logger.debug("EnrollServlet: No CertAuthEnroll.");
                certInfo = new CertInfo();
            }

            X509CertInfo[] certInfoArray = new X509CertInfo[] { certInfo };

            String authMgr = AuditFormat.NOAUTH;

            // if authentication
            if (authToken != null) {
                authMgr =
                        authToken.getInString(AuthToken.TOKEN_AUTHMGR_INST_NAME);
                // don't store agent token in request.
                // agent currently used for bulk issuance.
                // if (!authMgr.equals(AuthSubsystem.CERTUSERDB_AUTHMGR_ID)) {
                logger.info("EnrollServlet: Enrollment request was authenticated by " +
                                authToken.getInString(AuthToken.TOKEN_AUTHMGR_INST_NAME));

                PKIProcessor.fillCertInfoFromAuthToken(certInfo,
                        authToken);
                // save authtoken attrs to request directly
                // (for policy use)
                saveAuthToken(authToken, req);
                // req.set(Request.AUTH_TOKEN, authToken);
                // }
            }

            logger.debug("EnrollServlet: Enroll authMgr " + authMgr);

            if (certAuthEnroll == true) {
                // logger.debug("just gotten subjectDN and serialNumber from ssl client cert");
                if (authToken == null) {
                    // authToken is null, can't match to anyone; bail!
                    logger.error(CMS.getLogMessage("CMSGW_ERR_PROCESS_ENROLL_NO_AUTH"));

                    // store a message in the signed audit log file
                    // (either an "admin" cert request for an admin certificate,
                    //  an "agent" cert request for "bulk enrollment", or
                    //  an "EE" standard cert request)
                    auditMessage = CMS.getLogMessage(
                                AuditEvent.NON_PROFILE_CERT_REQUEST,
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditRequesterID,
                                auditServiceID,
                                auditCertificateSubjectName);

                    audit(auditMessage);

                    return;
                }
            }

            // fill certInfo from input types: keygen, cmc, pkcs10 or crmf
            KeyGenInfo keyGenInfo = httpParams.getValueAsKeyGenInfo(
                    SUBJECT_KEYGEN_INFO, null);
            PKCS10 pkcs10 = null;

            // support Enterprise 3.5.1 server where CERT_TYPE=csrCertType
            // instead of certType
            String certType = httpParams.getValueAsString(OLD_CERT_TYPE, null);
            logger.debug("EnrollServlet: certType " + certType);

            if (certType == null) {
                certType = httpParams.getValueAsString(CERT_TYPE, "client");
                logger.debug("EnrollServlet: certType " + certType);
            } else {
                // some policies may rely on the fact that
                // CERT_TYPE is set. So for 3.5.1 or eariler
                // we need to set CERT_TYPE here.
                req.setExtData(Request.HTTP_PARAMS, CERT_TYPE, certType);
            }
            if (certType.equals("client")) {
                // coming from MSIE
                String p10b64 = httpParams.getValueAsString(PKCS10_REQUEST,
                        null);

                if (p10b64 != null) {
                    try {
                        byte[] bytes = Utils.base64decode(p10b64);

                        pkcs10 = new PKCS10(bytes);
                    } catch (Exception e) {
                        // ok, if the above fails, it could
                        // be a PKCS10 with header
                        pkcs10 = httpParams.getValueAsPKCS10(PKCS10_REQUEST,
                                    false, null);
                        // e.printStackTrace();
                    }
                }

                //pkcs10 = httpParams.getValuePKCS10(PKCS10_REQUEST, null);

            } else {
                try {
                    // coming from server cut & paste blob.
                    pkcs10 = httpParams.getValueAsPKCS10(PKCS10_REQUEST,
                                false, null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            String cmc = null;
            String asciiBASE64Blob = httpParams.getValueAsString(CMC_REQUEST, null);

            if (asciiBASE64Blob != null) {
                int startIndex = asciiBASE64Blob.indexOf(HEADER);
                int endIndex = asciiBASE64Blob.indexOf(TRAILER);
                if (startIndex != -1 && endIndex != -1) {
                    startIndex = startIndex + HEADER.length();
                    cmc = asciiBASE64Blob.substring(startIndex, endIndex);
                } else
                    cmc = asciiBASE64Blob;
                logger.debug("EnrollServlet: cmc " + cmc);
            }

            String crmf = httpParams.getValueAsString(CRMF_REQUEST, null);

            logger.debug("EnrollServlet: crmf " + crmf);

            if (certAuthEnroll == true) {

                PKIProcessor.fillCertInfoFromAuthToken(certInfo, authToken);

                // for dual certs
                if (certauthEnrollType.equals(CERT_AUTH_DUAL)) {

                    logger.debug("EnrollServlet: Attempting CERT_AUTH_DUAL");
                    boolean gotEncCert = false;
                    X509CertInfo[] cInfoArray = null;

                    try {
                        cInfoArray = handleCertAuthDual(certInfo, authToken,
                                    sslClientCert, mCa,
                                    certBasedOldSubjectDN,
                                    certBasedOldSerialNum);
                    } catch (ECMSGWException e) {
                        // store a message in the signed audit log file
                        // (either an "admin" cert request for an admin
                        //  certificate, an "agent" cert request for
                        //  "bulk enrollment", or an "EE" standard cert request)
                        auditMessage = CMS.getLogMessage(
                                    AuditEvent.NON_PROFILE_CERT_REQUEST,
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditRequesterID,
                                    auditServiceID,
                                    auditCertificateSubjectName);

                        audit(auditMessage);

                        throw new ECMSGWException(e.toString());
                    }

                    if (cInfoArray != null && cInfoArray.length != 0) {
                        logger.debug("EnrollServlet: cInfoArray Length " + cInfoArray.length);

                        certInfoArray = cInfoArray;
                        gotEncCert = true;
                    }

                    if (gotEncCert == false) {
                        // encryption cert not found, bail
                        logger.error(CMS.getLogMessage(
                                        "CMSGW_ENCRYPTION_CERT_NOT_FOUND"));

                        // store a message in the signed audit log file
                        // (either an "admin" cert request for an admin
                        //  certificate, an "agent" cert request for
                        //  "bulk enrollment", or an "EE" standard cert request)
                        auditMessage = CMS.getLogMessage(
                                    AuditEvent.NON_PROFILE_CERT_REQUEST,
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditRequesterID,
                                    auditServiceID,
                                    auditCertificateSubjectName);

                        audit(auditMessage);

                        throw new ECMSGWException(
                                CMS.getUserMessage("CMS_GW_ENCRYPTION_CERT_NOT_FOUND"));
                    }

                } else if (certauthEnrollType.equals(CERT_AUTH_ENCRYPTION)) {

                    // first, make sure the client cert is indeed a
                    // signing only cert

                    try {

                        checkClientCertSigningOnly(sslClientCert);
                    } catch (ECMSGWException e) {
                        // store a message in the signed audit log file
                        // (either an "admin" cert request for an admin
                        //  certificate, an "agent" cert request for
                        //  "bulk enrollment", or an "EE" standard cert request)
                        auditMessage = CMS.getLogMessage(
                                    AuditEvent.NON_PROFILE_CERT_REQUEST,
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditRequesterID,
                                    auditServiceID,
                                    auditCertificateSubjectName);

                        audit(auditMessage);

                        throw new ECMSGWException(e.toString());
                    }

                    /*
                     * either crmf or keyGenInfo
                     */
                    if (keyGenInfo != null) {
                        KeyGenProcessor keyGenProc = new KeyGenProcessor(cmsReq,
                                this);

                        keyGenProc.fillCertInfo(null, certInfo,
                                authToken, httpParams);

                        req.setExtData(CLIENT_ISSUER,
                                sslClientCert.getIssuerDN().toString());
                        logger.debug("EnrollServlet: sslClientCert issuerDN = " +
                                sslClientCert.getIssuerDN().toString());
                    } else if (crmf != null && crmf != "") {
                        CRMFProcessor crmfProc = new CRMFProcessor(cmsReq, this, enforcePop);

                        certInfoArray = crmfProc.fillCertInfoArray(crmf,
                                    authToken,
                                    httpParams,
                                    req);

                        req.setExtData(CLIENT_ISSUER,
                                sslClientCert.getIssuerDN().toString());
                        logger.debug("EnrollServlet: sslClientCert issuerDN = " +
                                sslClientCert.getIssuerDN().toString());
                    } else {
                        logger.error(CMS.getLogMessage("CMSGW_CANT_PROCESS_ENROLL_REQ") +
                                        CMS.getLogMessage("CMSGW_MISSING_KEYGEN_INFO"));

                        // store a message in the signed audit log file
                        // (either an "admin" cert request for an admin
                        //  certificate, an "agent" cert request for
                        //  "bulk enrollment", or an "EE" standard cert request)
                        auditMessage = CMS.getLogMessage(
                                    AuditEvent.NON_PROFILE_CERT_REQUEST,
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditRequesterID,
                                    auditServiceID,
                                    auditCertificateSubjectName);

                        audit(auditMessage);

                        throw new ECMSGWException(
                                CMS.getUserMessage(getLocale(httpReq), "CMS_GW_MISSING_KEYGEN_INFO"));
                    }

                } else if (certauthEnrollType.equals(CERT_AUTH_SINGLE)) {

                    // have to be buried here to handle the issuer

                    if (keyGenInfo != null) {
                        KeyGenProcessor keyGenProc = new KeyGenProcessor(cmsReq,
                                this);

                        keyGenProc.fillCertInfo(null, certInfo,
                                authToken, httpParams);
                    } else if (pkcs10 != null) {
                        PKCS10Processor pkcs10Proc = new PKCS10Processor(cmsReq,
                                this);

                        pkcs10Proc.fillCertInfo(pkcs10, certInfo,
                                authToken, httpParams);
                    } else if (cmc != null && cmc != "") {
                        CMCProcessor cmcProc = new CMCProcessor(cmsReq, this, enforcePop);

                        certInfoArray = cmcProc.fillCertInfoArray(cmc,
                                    authToken,
                                    httpParams,
                                    req);
                    } else if (crmf != null && crmf != "") {
                        CRMFProcessor crmfProc = new CRMFProcessor(cmsReq, this, enforcePop);

                        certInfoArray = crmfProc.fillCertInfoArray(crmf,
                                    authToken,
                                    httpParams,
                                    req);
                    } else {
                        logger.error(CMS.getLogMessage("CMSGW_CANT_PROCESS_ENROLL_REQ") +
                                        CMS.getLogMessage("CMSGW_MISSING_KEYGEN_INFO"));

                        // store a message in the signed audit log file
                        // (either an "admin" cert request for an admin
                        //  certificate, an "agent" cert request for
                        //  "bulk enrollment", or an "EE" standard cert request)
                        auditMessage = CMS.getLogMessage(
                                    AuditEvent.NON_PROFILE_CERT_REQUEST,
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditRequesterID,
                                    auditServiceID,
                                    auditCertificateSubjectName);

                        audit(auditMessage);

                        throw new ECMSGWException(
                                CMS.getUserMessage(getLocale(httpReq), "CMS_GW_MISSING_KEYGEN_INFO"));
                    }
                    req.setExtData(CLIENT_ISSUER,
                            sslClientCert.getIssuerDN().toString());
                }

            } else if (keyGenInfo != null) {

                logger.debug("EnrollServlet: Trying KeyGen with no cert auth.");
                KeyGenProcessor keyGenProc = new KeyGenProcessor(cmsReq, this);

                keyGenProc.fillCertInfo(null, certInfo, authToken, httpParams);
            } else if (pkcs10 != null) {
                logger.debug("EnrollServlet: Trying PKCS10 with no cert auth.");
                PKCS10Processor pkcs10Proc = new PKCS10Processor(cmsReq, this);

                pkcs10Proc.fillCertInfo(pkcs10, certInfo, authToken, httpParams);
            } else if (cmc != null) {
                logger.debug("EnrollServlet: Trying CMC with no cert auth.");
                CMCProcessor cmcProc = new CMCProcessor(cmsReq, this, enforcePop);

                certInfoArray = cmcProc.fillCertInfoArray(cmc, authToken,
                            httpParams, req);
            } else if (crmf != null && crmf != "") {
                logger.debug("EnrollServlet: Trying CRMF with no cert auth.");
                CRMFProcessor crmfProc = new CRMFProcessor(cmsReq, this, enforcePop);

                certInfoArray = crmfProc.fillCertInfoArray(crmf, authToken,
                            httpParams, req);
            } else {
                logger.error(CMS.getLogMessage("CMSGW_CANT_PROCESS_ENROLL_REQ") +
                                CMS.getLogMessage("CMSGW_MISSING_KEYGEN_INFO"));

                // store a message in the signed audit log file
                // (either an "admin" cert request for an admin certificate,
                //  an "agent" cert request for "bulk enrollment", or
                //  an "EE" standard cert request)
                auditMessage = CMS.getLogMessage(
                            AuditEvent.NON_PROFILE_CERT_REQUEST,
                            auditSubjectID,
                            ILogger.FAILURE,
                            auditRequesterID,
                            auditServiceID,
                            auditCertificateSubjectName);

                audit(auditMessage);

                throw new ECMSGWException(CMS.getUserMessage(getLocale(httpReq), "CMS_GW_MISSING_KEYGEN_INFO"));
            }

            // if ca, fill in default signing alg here

            try {
                CertificateAuthority caSub = engine.getCA();
                if (certInfoArray != null && caSub != null) {
                    for (int ix = 0; ix < certInfoArray.length; ix++) {
                        X509CertInfo ci = certInfoArray[ix];
                        String defaultSig = caSub.getDefaultAlgorithm();
                        AlgorithmId algid = AlgorithmId.get(defaultSig);
                        ci.set(X509CertInfo.ALGORITHM_ID,
                                new CertificateAlgorithmId(algid));
                    }
                }
            } catch (Exception e) {
                logger.warn("ErrorServlet: Failed to set signing alg to certinfo: " + e.getMessage(), e);
            }

            req.setExtData(Request.CERT_INFO, certInfoArray);

            if (challengePassword != null && !challengePassword.equals("")) {
                String pwd = hashPassword(challengePassword);

                req.setExtData(CHALLENGE_PASSWORD, pwd);
            }

            // store a message in the signed audit log file
            // (either an "admin" cert request for an admin certificate,
            //  an "agent" cert request for "bulk enrollment", or
            //  an "EE" standard cert request)
            auditMessage = CMS.getLogMessage(
                        AuditEvent.NON_PROFILE_CERT_REQUEST,
                        auditSubjectID,
                        ILogger.SUCCESS,
                        auditRequesterID,
                        auditServiceID,
                        auditCertificateSubjectName);

            audit(auditMessage);

        } catch (EBaseException eAudit1) {
            // store a message in the signed audit log file
            // (either an "admin" cert request for an admin certificate,
            //  an "agent" cert request for "bulk enrollment", or
            //  an "EE" standard cert request)
            auditMessage = CMS.getLogMessage(
                        AuditEvent.NON_PROFILE_CERT_REQUEST,
                        auditSubjectID,
                        ILogger.FAILURE,
                        auditRequesterID,
                        auditServiceID,
                        auditCertificateSubjectName);

            audit(auditMessage);

            throw eAudit1;
        }

        X509CertImpl[] issuedCerts = null;

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            // send request to request queue.
            mRequestQueue.processRequest(req);
            // process result.

            // render OLD_CERT_TYPE's response differently, we
            // do not want any javascript in HTML, and need to
            // override the default render.
            if (httpParams.getValueAsString(OLD_CERT_TYPE, null) != null) {
                try {
                    renderServerEnrollResult(cmsReq);
                    cmsReq.setStatus(CMSRequest.SUCCESS); // no default render

                    issuedCerts =
                            cmsReq.getRequest().getExtDataInCertArray(
                                    Request.ISSUED_CERTS);

                    for (int i = 0; i < issuedCerts.length; i++) {
                        // (automated "agent" cert request processed
                        //  - "accepted")
                        audit(CertRequestProcessedEvent.createSuccessEvent(
                                    auditSubjectID,
                                    auditRequesterID,
                                    ILogger.SIGNED_AUDIT_ACCEPTANCE,
                                    issuedCerts[i]));
                    }
                } catch (Exception ex) {
                    cmsReq.setStatus(CMSRequest.ERROR);

                    // (automated "agent" cert request processed - "rejected")
                    audit(CertRequestProcessedEvent.createFailureEvent(
                                auditSubjectID,
                                auditRequesterID,
                                ILogger.SIGNED_AUDIT_REJECTION,
                                SIGNED_AUDIT_AUTOMATED_REJECTION_REASON[0]));
                }

                return;
            }

            boolean completed = handleEnrollAuditLog(req, cmsReq,
                    mAuthMgr, authToken,
                    certInfo, startTime);

            if (completed == false) {
                // (automated "agent" cert request processed - "rejected")
                audit(CertRequestProcessedEvent.createFailureEvent(
                            auditSubjectID,
                            auditRequesterID,
                            ILogger.SIGNED_AUDIT_REJECTION,
                            SIGNED_AUDIT_AUTOMATED_REJECTION_REASON[1]));

                return;
            }

            // service success
            cmsReq.setStatus(CMSRequest.SUCCESS);
            issuedCerts = req.getExtDataInCertArray(Request.ISSUED_CERTS);

            String initiative = null;
            String agentID;

            if (authToken == null) {
                // request is from eegateway, so fromUser.
                initiative = AuditFormat.FROMUSER;
            } else {
                agentID = authToken.getInString("userid");
                initiative = AuditFormat.FROMAGENT + " agentID: " + agentID;
            }

            // audit log the success.
            long endTime = new Date().getTime();

            logger.info(
                    AuditFormat.ENROLLMENTFORMAT,
                    req.getRequestId().toHexString(),
                    initiative,
                    mAuthMgr,
                    "completed",
                    issuedCerts[0].getSubjectDN(),
                    "cert issued serial number: 0x" +
                            issuedCerts[0].getSerialNumber().toString(16) +
                            " time: " +
                            (endTime - startTime)
            );

            // handle initial admin enrollment if in adminEnroll mode.
            checkAdminEnroll(cmsReq, issuedCerts);

            // return cert as mime type binary if requested.
            if (checkImportCertToNav(cmsReq.getHttpResp(),
                    httpParams, issuedCerts[0])) {
                cmsReq.setStatus(CMSRequest.SUCCESS);

                for (int i = 0; i < issuedCerts.length; i++) {
                    // (automated "agent" cert request processed - "accepted")
                    audit(CertRequestProcessedEvent.createSuccessEvent(
                                auditSubjectID,
                                auditRequesterID,
                                ILogger.SIGNED_AUDIT_ACCEPTANCE,
                                issuedCerts[i]));
                }

                return;
            }

            // use success template.
            try {
                cmsReq.setResult(issuedCerts);
                renderTemplate(cmsReq, mEnrollSuccessTemplate,
                        mEnrollSuccessFiller);
                cmsReq.setStatus(CMSRequest.SUCCESS);

                for (int i = 0; i < issuedCerts.length; i++) {
                    // (automated "agent" cert request processed - "accepted")
                    audit(CertRequestProcessedEvent.createSuccessEvent(
                                auditSubjectID,
                                auditRequesterID,
                                ILogger.SIGNED_AUDIT_ACCEPTANCE,
                                issuedCerts[i]));
                }
            } catch (IOException e) {
                logger.error(CMS.getLogMessage("CMSGW_TEMP_REND_ERR",
                                mEnrollSuccessFiller.toString(),
                                e.toString()), e);

                // (automated "agent" cert request processed - "rejected")
                audit(CertRequestProcessedEvent.createFailureEvent(
                            auditSubjectID,
                            auditRequesterID,
                            ILogger.SIGNED_AUDIT_REJECTION,
                            SIGNED_AUDIT_AUTOMATED_REJECTION_REASON[2]));

                throw new ECMSGWException(
                        CMS.getUserMessage("CMS_GW_RETURNING_RESULT_ERROR"), e);
            }
        } catch (EBaseException eAudit1) {
            // store a message in the signed audit log file
            // (automated "agent" cert request processed - "rejected")
            audit(CertRequestProcessedEvent.createFailureEvent(
                        auditSubjectID,
                        auditRequesterID,
                        ILogger.SIGNED_AUDIT_REJECTION,
                        SIGNED_AUDIT_AUTOMATED_REJECTION_REASON[3]));

            throw eAudit1;
        }

        return;
    }

    /**
     * check if this is first enroll from admin enroll.
     * If so disable admin enroll from here on.
     */
    protected void checkAdminEnroll(CMSRequest cmsReq, X509CertImpl[] issuedCerts)
            throws EBaseException {
        // this is special case, get the admin certificate
        if (mAuthMgr != null && mAuthMgr.equals(AuthSubsystem.PASSWDUSERDB_AUTHMGR_ID)) {
            addAdminAgent(cmsReq, issuedCerts);
            CMSGateway.disableAdminEnroll();
        }
    }

    protected void addAdminAgent(CMSRequest cmsReq, X509CertImpl[] issuedCerts)
            throws EBaseException {
        String userid = cmsReq.getHttpParams().getValueAsString("uid");
        CAEngine engine = CAEngine.getInstance();
        UGSubsystem ug = engine.getUGSubsystem();

        User adminuser = ug.createUser(userid);
        adminuser.setX509Certificates(issuedCerts);
        ug.addUserCert(userid, issuedCerts[0]);

        Group agentGroup = ug.getGroupFromName(CA_AGENT_GROUP);

        if (agentGroup != null) {
            // add user to the group if necessary
            if (!agentGroup.isMember(userid)) {
                agentGroup.addMemberName(userid);
                ug.modifyGroup(agentGroup);

                logger.info(
                        AuditFormat.ADDUSERGROUPFORMAT,
                        userid,
                        userid,
                        CA_AGENT_GROUP
                );
            }

        } else {
            String msg = "Cannot add admin to the " +
                    CA_AGENT_GROUP +
                    " group: Group does not exist.";

            logger.error("EnrollServlet: " + msg);
            throw new ECMSGWException(CMS.getUserMessage("CMS_GW_ADDING_ADMIN_ERROR"));
        }
    }

    protected void renderServerEnrollResult(CMSRequest cmsReq) throws Exception {
        HttpServletResponse httpResp = cmsReq.getHttpResp();

        httpResp.setContentType("text/html");
        ServletOutputStream out = null;

        out = httpResp.getOutputStream();

        // get template based on request status
        out.println("<HTML>");
        out.println("<TITLE>");
        out.println("Server Enrollment");
        out.println("</TITLE>");
        // out.println("<BODY BGCOLOR=white>");

        if (cmsReq.getRequest().getRequestStatus().equals(RequestStatus.COMPLETE)) {
            out.println("<H1>");
            out.println("SUCCESS");
            out.println("</H1>");
            out.println("Your request is submitted and approved. Please cut and paste the certificate into your server."); // XXX - localize the message
            out.println("<P>");
            out.println("Request Creation Time: ");
            out.println(cmsReq.getRequest().getCreationTime().toString());
            out.println("<P>");
            out.println("Request Status: ");
            out.println(cmsReq.getStatus().toString());
            out.println("<P>");
            out.println("Request ID: ");
            out.println(cmsReq.getRequest().getRequestId().toString());
            out.println("<P>");
            out.println("Certificate: ");
            out.println("<P>");
            out.println("<PRE>");
            X509CertImpl certs[] =
                    cmsReq.getRequest().getExtDataInCertArray(Request.ISSUED_CERTS);

            out.println(CertUtil.toPEM(certs[0]));
            out.println("</PRE>");
            out.println("<P>");
            out.println("<!HTTP_OUTPUT REQUEST_CREATION_TIME=" +
                    cmsReq.getRequest().getCreationTime().toString() + ">");
            out.println("<!HTTP_OUTPUT REQUEST_STATUS=" +
                    cmsReq.getStatus().toString() + ">");
            out.println("<!HTTP_OUTPUT REQUEST_ID=" +
                    cmsReq.getRequest().getRequestId().toString() + ">");
            out.println("<!HTTP_OUTPUT X509_CERTIFICATE=" +
                    CertUtil.toPEM(certs[0]) + ">");
        } else if (cmsReq.getRequest().getRequestStatus().equals(RequestStatus.PENDING)) {
            out.println("<H1>");
            out.println("PENDING");
            out.println("</H1>");
            out.println("Your request is submitted. You can check on the status of your request with an authorized agent or local administrator by referring to the request ID."); // XXX - localize the message
            out.println("<P>");
            out.println("Request Creation Time: ");
            out.println(cmsReq.getRequest().getCreationTime().toString());
            out.println("<P>");
            out.println("Request Status: ");
            out.println(cmsReq.getStatus().toString());
            out.println("<P>");
            out.println("Request ID: ");
            out.println(cmsReq.getRequest().getRequestId().toString());
            out.println("<P>");
            out.println("<!HTTP_OUTPUT REQUEST_CREATION_TIME=" +
                    cmsReq.getRequest().getCreationTime().toString() + ">");
            out.println("<!HTTP_OUTPUT REQUEST_STATUS=" +
                    cmsReq.getStatus().toString() + ">");
            out.println("<!HTTP_OUTPUT REQUEST_ID=" +
                    cmsReq.getRequest().getRequestId().toString() + ">");
        } else {
            out.println("<H1>");
            out.println("ERROR");
            out.println("</H1>");
            out.println("<!INFO>");
            out.println("Please consult your local administrator for assistance."); // XXX - localize the message
            out.println("<!/INFO>");
            out.println("<P>");
            out.println("Request Status: ");
            out.println(cmsReq.getStatus().toString());
            out.println("<P>");
            out.println("Error: ");
            out.println(cmsReq.getError()); // XXX - need to parse in Locale
            out.println("<P>");
            out.println("<!HTTP_OUTPUT REQUEST_STATUS=" +
                    cmsReq.getStatus().toString() + ">");
            out.println("<!HTTP_OUTPUT ERROR=" +
                    cmsReq.getError() + ">");
        }

        /**
         * // include all the input data
         * ArgBlock args = cmsReq.getHttpParams();
         * Enumeration ele = args.getElements();
         * while (ele.hasMoreElements()) {
         * String eleT = (String)ele.nextElement();
         * out.println("<!HTTP_INPUT " + eleT + "=" +
         * args.get(eleT) + ">");
         * }
         **/

        out.println("</HTML>");
    }

    // XXX ALERT !!
    // Remove the following and calls to them when we bundle a cartman
    // later than alpha1.
    // These are here to cover up problem in cartman where the
    // key usage extension always ends up being digital signature only
    // and for rsa-ex ends up having no bits set.

    @SuppressWarnings("unused")
    private boolean mIsTestBed;

    private void init_testbed_hack(ConfigStore config) throws EBaseException {
        mIsTestBed = config.getBoolean("isTestBed", true);
    }
}
