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
// (C) 2012 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cms.servlet.cert;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.dogtagpki.server.authentication.AuthToken;

import com.netscape.certsrv.authentication.AuthCredentials;
import com.netscape.certsrv.base.BadRequestDataException;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.EPropertyNotFound;
import com.netscape.certsrv.base.SessionContext;
import com.netscape.certsrv.ca.AuthorityID;
import com.netscape.certsrv.cert.CertEnrollmentRequest;
import com.netscape.certsrv.profile.ProfileAttribute;
import com.netscape.certsrv.profile.ProfileInput;
import com.netscape.cms.profile.ProfileAuthenticator;
import com.netscape.cms.profile.common.EnrollProfile;
import com.netscape.cms.profile.common.Profile;
import com.netscape.cms.servlet.common.CMSTemplate;
import com.netscape.cms.servlet.profile.SSLClientCertProvider;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.request.Request;
import com.netscape.cmsutil.ldap.LDAPUtil;

public class EnrollmentProcessor extends CertProcessor {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EnrollmentProcessor.class);

    public EnrollmentProcessor(String id, Locale locale) throws EPropertyNotFound, EBaseException {
        super(id, locale);
    }

    private void setInputsIntoContext(CertEnrollmentRequest data, Profile profile, Map<String, String> ctx) {
        // put profile inputs into a local map
        HashMap<String, String> dataInputs = new HashMap<>();
        for (ProfileInput input : data.getInputs()) {
            for (ProfileAttribute attr : input.getAttributes()) {
                dataInputs.put(attr.getName(), attr.getValue());
            }
        }

        // iterate through inputs in profile and put those in context
        Enumeration<String> inputIds = profile.getProfileInputIds();
        if (inputIds != null) {
            while (inputIds.hasMoreElements()) {
                String inputId = inputIds.nextElement();
                com.netscape.cms.profile.common.ProfileInput profileInput = profile.getProfileInput(inputId);
                Enumeration<String> inputNames = profileInput.getValueNames();

                while (inputNames.hasMoreElements()) {
                    String inputName = inputNames.nextElement();
                    if (dataInputs.containsKey(inputName)) {
                        // all subject name parameters start with sn_, no other input parameters do
                        if (inputName.matches("^sn_.*")) {
                            ctx.put(inputName, LDAPUtil.escapeRDNValue(dataInputs.get(inputName)));
                        } else {
                            ctx.put(inputName, dataInputs.get(inputName));
                        }
                    }
                }
            }
        }

    }

    public HashMap<String, Object> processEnrollment(
            CertEnrollmentRequest data,
            HttpServletRequest request,
            AuthorityID aid,
            AuthCredentials credentials)
            throws Exception {
        return processEnrollment(data, request, aid, credentials, null);
    }

    /**
     * Process the HTTP request
     * <P>
     *
     * (Certificate Request Processed - either an automated "EE" profile based cert acceptance, or an automated "EE"
     * profile based cert rejection)
     * <P>
     *
     * <ul>
     * <li>http.param profileId ID of profile to use to process request
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CERT_REQUEST_PROCESSED used when a certificate request has just been
     * through the approval process
     * </ul>
     *
     * @exception Exception an error has occurred
     */
    public HashMap<String, Object> processEnrollment(
            CertEnrollmentRequest data,
            HttpServletRequest request,
            AuthorityID aid,
            AuthCredentials credentials,
            AuthToken authToken)
        throws Exception {

        try {
            if (logger.isDebugEnabled()) {
                HashMap<String,String> params = data.toParams();
                printParameterValues(params);
            }

            logger.debug("EnrollmentProcessor: isRenewal false");
            startTiming("enrollment");

            // if we did not configure profileId in xml file,
            // then accept the user-provided one
            String profileId = (this.profileID == null) ? data.getProfileId() : this.profileID;
            logger.debug("EnrollmentProcessor: profileId " + profileId);

            Profile profile = ps.getProfile(profileId);
            if (profile == null) {
                logger.error(CMS.getUserMessage(locale, "CMS_PROFILE_NOT_FOUND", CMSTemplate.escapeJavaScriptStringHTML(profileId)));
                throw new BadRequestDataException(CMS.getUserMessage(locale, "CMS_PROFILE_NOT_FOUND", CMSTemplate.escapeJavaScriptStringHTML(profileId)));
            }
            if (!ps.isProfileEnable(profileId)) {
                logger.error("EnrollmentProcessor: Profile " + profileId + " not enabled");
                throw new BadRequestDataException("Profile " + profileId + " not enabled");
            }

            Map<String, String> ctx = new HashMap<>();

            // set arbitrary user data into request, if any
            String userData = null;
            if (request != null)
                userData = request.getParameter("user-data");
            if (userData != null)
                ctx.put(EnrollProfile.REQUEST_USER_DATA, userData);

            if (aid != null)
                ctx.put(EnrollProfile.REQUEST_AUTHORITY_ID, aid.toString());

            logger.debug("EnrollmentProcessor: set Inputs into profile Context");
            setInputsIntoContext(data, profile, ctx);

            ProfileAuthenticator authenticator = ps.getProfileAuthenticator(profile);
            if (authenticator != null) {
                logger.debug("EnrollmentProcessor: authenticator " + authenticator.getName() + " found");
                setCredentialsIntoContext(request, credentials, authenticator, ctx);
            }

            // for ssl authentication; pass in servlet for retrieving ssl client certificates
            // insert profile context so that input parameter can be retrieved
            SessionContext context = SessionContext.getContext();
            context.put("profileContext", ctx);
            context.put("sslClientCertProvider", new SSLClientCertProvider(request));
            logger.debug("EnrollmentProcessor: set sslClientCertProvider");

            // before creating the request, authenticate the request
            if (authToken == null && authenticator != null) {
                authToken = authenticate(request, null, authenticator, context, false, credentials);
            }

            // authentication success, now authorize
            authorize(profileId, profile, authToken);

            ///////////////////////////////////////////////
            // create and populate request
            ///////////////////////////////////////////////
            startTiming("request_population");
            Request[] reqs = profile.createRequests(ctx, locale);
            populateRequests(data, false, locale, null, null, null, profileId, profile,
                    ctx, authenticator, authToken, reqs);
            endTiming("request_population");

            ///////////////////////////////////////////////
            // validate realm (if present)
            ///////////////////////////////////////////////
            for (Request req : reqs) {
                String realm = req.getRealm();
                if (StringUtils.isNotBlank(realm)) {
                    authz.checkRealm(realm, authToken, null,
                            "certServer.ca.request.enrollment", "submit");
                }
            }

            ///////////////////////////////////////////////
            // submit request
            ///////////////////////////////////////////////
            String errorCode = submitRequests(locale, profile, authToken, reqs);
            String errorReason = null;

            List<String> errors = new ArrayList<>();
            if (errorCode != null) {
                for (Request req: reqs) {
                    String error = req.getError(locale);
                    if (error != null) {
                        String code = req.getErrorCode(locale);
                        errors.add(codeToReason(locale, code, error, req.getRequestId()));
                    }
                }
                errorReason = StringUtils.join(errors, '\n');
            }

            HashMap<String, Object> ret = new HashMap<>();
            ret.put(ARG_REQUESTS, reqs);
            ret.put(ARG_ERROR_CODE, errorCode);
            ret.put(ARG_ERROR_REASON, errorReason);
            ret.put(ARG_PROFILE, profile);

            logger.debug("EnrollmentSubmitter: done serving");
            endTiming("enrollment");

            return ret;
        } finally {
            SessionContext.releaseContext();
            endAllEvents();
        }
    }




}
