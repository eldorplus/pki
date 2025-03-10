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
package com.netscape.cms.profile.def;

import java.util.Locale;

import org.dogtagpki.server.ca.CAEngine;
import org.mozilla.jss.netscape.security.x509.AlgorithmId;
import org.mozilla.jss.netscape.security.x509.CertificateAlgorithmId;
import org.mozilla.jss.netscape.security.x509.X509CertInfo;

import com.netscape.ca.CertificateAuthority;
import com.netscape.certsrv.profile.EProfileException;
import com.netscape.certsrv.property.Descriptor;
import com.netscape.certsrv.property.EPropertyException;
import com.netscape.certsrv.property.IDescriptor;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.request.Request;

/**
 * This class implements an enrollment default policy
 * that populates a signing algorithm
 * into the certificate template.
 *
 * @version $Revision$, $Date$
 */
public class SigningAlgDefault extends EnrollDefault {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SigningAlgDefault.class);

    public static final String CONFIG_ALGORITHM = "signingAlg";

    public static final String VAL_ALGORITHM = "signingAlg";
    public static final String DEF_CONFIG_ALGORITHMS =
            "-,SHA1withRSA,SHA256withRSA,SHA384withRSA,SHA512withRSA";

    public SigningAlgDefault() {
        super();
        addConfigName(CONFIG_ALGORITHM);
        addValueName(VAL_ALGORITHM);
    }

    @Override
    public IDescriptor getConfigDescriptor(Locale locale, String name) {
        if (name.equals(CONFIG_ALGORITHM)) {
            return new Descriptor(IDescriptor.CHOICE, DEF_CONFIG_ALGORITHMS,
                    "SHA256withRSA",
                    CMS.getUserMessage(locale, "CMS_PROFILE_SIGNING_ALGORITHM"));
        } else {
            return null;
        }
    }

    public String getSigningAlg() {

        CAEngine engine = CAEngine.getInstance();
        String signingAlg = getConfig(CONFIG_ALGORITHM);
        // if specified, use the specified one. Otherwise, pick
        // the best selection for the user
        if (signingAlg == null || signingAlg.equals("") ||
                signingAlg.equals("-")) {
            // best pick for the user
            CertificateAuthority ca = engine.getCA();
            return ca.getDefaultAlgorithm();
        } else {
            return signingAlg;
        }
    }

    public String getDefSigningAlgorithms() {
        StringBuffer allowed = new StringBuffer();

        CAEngine engine = CAEngine.getInstance();
        CertificateAuthority ca = engine.getCA();

        String algos[] = ca.getCASigningAlgorithms();
        for (int i = 0; i < algos.length; i++) {
            if (allowed.length() == 0) {
                allowed.append(algos[i]);
            } else {
                allowed.append(",");
                allowed.append(algos[i]);
            }
        }
        return allowed.toString();
    }

    @Override
    public IDescriptor getValueDescriptor(Locale locale, String name) {
        if (name.equals(VAL_ALGORITHM)) {
            String allowed = getDefSigningAlgorithms();
            return new Descriptor(IDescriptor.CHOICE,
                    allowed, null,
                    CMS.getUserMessage(locale, "CMS_PROFILE_SIGNING_ALGORITHM"));
        }
        return null;
    }

    @Override
    public void setValue(String name, Locale locale,
            X509CertInfo info, String value)
            throws EPropertyException {
        if (name == null) {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }
        if (name.equals(VAL_ALGORITHM)) {
            try {
                info.set(X509CertInfo.ALGORITHM_ID,
                        new CertificateAlgorithmId(
                                AlgorithmId.get(value)));
            } catch (Exception e) {
                logger.error("SigningAlgDefault: setValue " + e.getMessage(), e);
                throw new EPropertyException(CMS.getUserMessage(
                            locale, "CMS_INVALID_PROPERTY", name));
            }
        } else {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }
    }

    @Override
    public String getValue(String name, Locale locale,
            X509CertInfo info)
            throws EPropertyException {

        if (name == null)
            throw new EPropertyException("Invalid name " + name);

        if (name.equals(VAL_ALGORITHM)) {
            CertificateAlgorithmId algId = null;

            try {
                algId = (CertificateAlgorithmId)
                        info.get(X509CertInfo.ALGORITHM_ID);
                AlgorithmId id = (AlgorithmId)
                        algId.get(CertificateAlgorithmId.ALGORITHM);

                return id.toString();
            } catch (Exception e) {
                logger.warn("SigningAlgDefault: getValue " + e.getMessage(), e);
            }
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        } else {
            throw new EPropertyException(CMS.getUserMessage(
                        locale, "CMS_INVALID_PROPERTY", name));
        }

    }

    @Override
    public String getText(Locale locale) {
        return CMS.getUserMessage(locale, "CMS_PROFILE_DEF_SIGNING_ALGORITHM",
                getSigningAlg());
    }

    /**
     * Populates the request with this policy default.
     */
    @Override
    public void populate(Request request, X509CertInfo info)
            throws EProfileException {
        try {
            info.set(X509CertInfo.ALGORITHM_ID,
                    new CertificateAlgorithmId(AlgorithmId.get(getSigningAlg())));
        } catch (Exception e) {
            logger.warn("SigningAlgDefault: populate " + e.getMessage(), e);
        }
    }
}
