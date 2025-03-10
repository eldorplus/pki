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
package com.netscape.cms.servlet.admin;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.IExtendedPluginInfo;
import com.netscape.certsrv.common.Constants;
import com.netscape.certsrv.common.NameValuePairs;
import com.netscape.certsrv.common.OpDef;
import com.netscape.certsrv.common.ScopeDef;
import com.netscape.certsrv.logging.AuditEvent;
import com.netscape.certsrv.logging.ELogException;
import com.netscape.certsrv.logging.ELogNotFound;
import com.netscape.certsrv.logging.ELogPluginNotFound;
import com.netscape.certsrv.logging.LogEventListener;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.certsrv.logging.LogPlugin;
import com.netscape.certsrv.logging.event.ConfigSignedAuditEvent;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.apps.CMSEngine;
import com.netscape.cmscore.base.ConfigStore;
import com.netscape.cmscore.logging.LogSubsystem;
import com.netscape.cmscore.logging.LoggerConfig;
import com.netscape.cmscore.logging.LoggerPluginsConfig;
import com.netscape.cmscore.logging.LoggersConfig;
import com.netscape.cmscore.logging.LoggingConfig;

/**
 * A class representings an administration servlet for logging
 * subsystem. This servlet is responsible to serve
 * logging administrative operation such as configuration
 * parameter updates and log retriever.
 *
 * @version $Revision$, $Date$
 */
public class LogAdminServlet extends AdminServlet {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LogAdminServlet.class);

    private static final long serialVersionUID = -99699953656847603L;

    private final static String INFO = "LogAdminServlet";

    private LogSubsystem logSubsystem;

    private final static String SIGNED_AUDIT_LOG_TYPE = "SignedAudit";

    /**
     * Constructs Log servlet.
     */
    public LogAdminServlet() {
        super();
    }

    public static Hashtable<String, String> toHashtable(HttpServletRequest req) {
        Hashtable<String, String> httpReqHash = new Hashtable<>();
        Enumeration<?> names = req.getParameterNames();

        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();

            httpReqHash.put(name, req.getParameter(name));
        }
        return httpReqHash;
    }

    /**
     * Initializes this servlet.
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        CMSEngine engine = CMS.getCMSEngine();
        logSubsystem = engine.getLogSubsystem();
    }

    /**
     * Returns serlvet information.
     */
    @Override
    public String getServletInfo() {
        return INFO;
    }

    /**
     * Serves HTTP admin request.
     */
    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        super.service(req, resp);

        String op = req.getParameter(Constants.OP_TYPE);

        if (op == null) {
            sendResponse(ERROR,
                    CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_PROTOCOL"),
                    null, resp);
            return;
        }

        super.authenticate(req);

        try {
            // perform operation based on scope
            String scope = req.getParameter(Constants.OP_SCOPE);

            if (scope != null) {
                AUTHZ_RES_NAME = "certServer.log.configuration";
                if (scope.equals(ScopeDef.SC_EXTENDED_PLUGIN_INFO)) {
                    mOp = "read";
                    if ((mToken = super.authorize(req)) == null) {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                null, resp);
                        return;
                    }
                    try {
                        getExtendedPluginInfo(req, resp);
                        return;
                    } catch (EBaseException e) {
                        sendResponse(ERROR, e.toString(getLocale(req)), null, resp);
                        return;
                    }
                }

                if (op.equals(OpDef.OP_READ)) {
                    mOp = "read";
                    if ((mToken = super.authorize(req)) == null) {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                null, resp);
                        return;
                    }

                    if (scope.equals(ScopeDef.SC_LOG_IMPLS)) {
                        getConfig(req, resp);
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_RULES)) {
                        getInstConfig(req, resp);
                        return;
                    } else if (scope.equals(ScopeDef.SC_GENERAL)) {
                        getGeneralConfig(req, resp);
                    } else {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_OP_SCOPE"),
                                null, resp);
                        return;
                    }
                } else if (op.equals(OpDef.OP_DELETE)) {
                    mOp = "modify";
                    if ((mToken = super.authorize(req)) == null) {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                null, resp);
                        return;
                    }

                    if (scope.equals(ScopeDef.SC_LOG_IMPLS)) {
                        delLogPlugin(req, resp, scope);
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_RULES)) {
                        delLogInst(req, resp, scope);
                        return;
                    } else {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_OP_SCOPE"),
                                null, resp);
                        return;
                    }
                } else if (op.equals(OpDef.OP_ADD)) {
                    mOp = "modify";
                    if ((mToken = super.authorize(req)) == null) {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                null, resp);
                        return;
                    }

                    if (scope.equals(ScopeDef.SC_LOG_IMPLS)) {
                        addLogPlugin(req, resp, scope);
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_RULES)) {
                        addLogInst(req, resp, scope);
                        return;
                    } else {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_OP_SCOPE"),
                                null, resp);
                        return;
                    }
                } else if (op.equals(OpDef.OP_MODIFY)) {
                    AUTHZ_RES_NAME = "certServer.log.configuration";
                    mOp = "modify";
                    if ((mToken = super.authorize(req)) == null) {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                null, resp);
                        return;
                    }

                    if (scope.equals(ScopeDef.SC_LOG_RULES)) {
                        modLogInst(req, resp, scope);
                        return;
                    } else if (scope.equals(ScopeDef.SC_GENERAL)) {
                        setGeneralConfig(req, resp);
                    } else {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_OP_SCOPE"),
                                null, resp);
                        return;
                    }
                } else if (op.equals(OpDef.OP_SEARCH)) {
                    mOp = "read";
                    if ((mToken = super.authorize(req)) == null) {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                null, resp);
                        return;
                    }
                    if (scope.equals(ScopeDef.SC_LOG_IMPLS)) {
                        listLogPlugins(req, resp);
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_RULES)) {
                        listLogInsts(req, resp, true);
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_INSTANCES)) {
                        listLogInsts(req, resp, false);
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_CONTENT)) {
                        String instName = req.getParameter(Constants.PR_LOG_INSTANCE);

                        if (instName.equals("System")) {
                            AUTHZ_RES_NAME = "certServer.log.content.system";
                        } else if (instName.equals("Transactions")) {
                            AUTHZ_RES_NAME = "certServer.log.content.transactions";
                        } else if (instName.equals(Constants.PR_LOG_SIGNED_AUDIT)) {
                            AUTHZ_RES_NAME = "certServer.log.content.signedAudit";
                        }

                        mOp = "read";
                        if ((mToken = super.authorize(req)) == null) {
                            sendResponse(ERROR,
                                    CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                    null, resp);
                            return;
                        }

                        LogEventListener loginst =
                                logSubsystem.getLogInstance(instName);

                        if (loginst != null) {
                            NameValuePairs nvps = loginst.retrieveLogContent(toHashtable(req));

                            sendResponse(SUCCESS, null, nvps, resp);
                        }
                        return;
                    } else if (scope.equals(ScopeDef.SC_LOG_ARCH)) {
                        String instName = req.getParameter(Constants.PR_LOG_INSTANCE);

                        if (instName.equals("System")) {
                            AUTHZ_RES_NAME = "certServer.log.content.system";
                        } else if (instName.equals("Transactions")) {
                            AUTHZ_RES_NAME = "certServer.log.content.transactions";
                        } else if (instName.equals(Constants.PR_LOG_SIGNED_AUDIT)) {
                            AUTHZ_RES_NAME = "certServer.log.content.signedAudit";
                        }

                        mOp = "read";
                        if ((mToken = super.authorize(req)) == null) {
                            sendResponse(ERROR,
                                    CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                    null, resp);
                            return;
                        }
                        LogEventListener loginst =
                                logSubsystem.getLogInstance(instName);

                        if (loginst != null) {
                            NameValuePairs nvps = loginst.retrieveLogList(toHashtable(req));

                            sendResponse(SUCCESS, null, nvps, resp);
                        }
                        return;
                    } else {
                        sendResponse(ERROR,
                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_OP_SCOPE"),
                                null, resp);
                        return;
                    }
                } else {
                    sendResponse(ERROR,
                            CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_OP_TYPE", op),
                            null, resp);
                    return;
                }
            }
        } catch (EBaseException e) {
            // if it is EBaseException, we can output better
            sendResponse(ERROR, e.toString(getLocale(req)), null, resp);
        } catch (Exception e) {
            System.out.println("XXX >>>" + e.toString() + "<<<");
            e.printStackTrace();
            sendResponse(ERROR,
                    CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_INVALID_PROTOCOL"),
                    null, resp);
        }

        return;
    }

    private synchronized void listLogInsts(HttpServletRequest req,
            HttpServletResponse resp, boolean all) throws ServletException,
            IOException, EBaseException {

        NameValuePairs params = new NameValuePairs();
        CMSEngine engine = CMS.getCMSEngine();
        Enumeration<String> e = logSubsystem.getLogInsts().keys();

        for (; e.hasMoreElements();) {
            String name = e.nextElement();
            LogEventListener value = logSubsystem.getLogInstance(name);

            if (value == null)
                continue;
            String pName = logSubsystem.getLogPluginName(value);
            LogPlugin pClass = logSubsystem.getLogPlugins().get(pName);
            String c = pClass.getClassPath();

            // not show ntEventlog here
            if (all || (!all && !c.endsWith("NTEventLog")))
                params.put(name, pName + ";visible");
        }
        sendResponse(SUCCESS, null, params, resp);
        return;
    }

    /**
     * retrieve extended plugin info such as brief description, type info
     * from logging
     */
    private void getExtendedPluginInfo(HttpServletRequest req,
            HttpServletResponse resp) throws ServletException,
            IOException, EBaseException {
        String id = req.getParameter(Constants.RS_ID);

        int colon = id.indexOf(':');

        String implName = id.substring(colon + 1);
        NameValuePairs params = getExtendedPluginInfo(implName);

        sendResponse(SUCCESS, null, params, resp);
    }

    private NameValuePairs getExtendedPluginInfo(String implName) {
        IExtendedPluginInfo ext_info = null;
        Object impl = null;
        LogPlugin lp = logSubsystem.getLogPlugins().get(implName);

        if (lp != null) {
            impl = getClassByNameAsExtendedPluginInfo(lp.getClassPath());
        }
        if (impl != null) {
            if (impl instanceof IExtendedPluginInfo) {
                ext_info = (IExtendedPluginInfo) impl;
            }
        }

        NameValuePairs nvps = null;

        if (ext_info == null) {
            nvps = new NameValuePairs();
        } else {
            nvps = convertStringArrayToNVPairs(ext_info.getExtendedPluginInfo());
        }

        return nvps;

    }

    /**
     * Add log plug-in
     * <P>
     *
     * <ul>
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT used when configuring signedAudit
     * </ul>
     *
     * @param req HTTP servlet request
     * @param resp HTTP servlet response
     * @param scope string used to obtain the contents of the log's substore
     * @exception ServletException a servlet error has occurred
     * @exception IOException an input/output error has occurred
     * @exception EBaseException an error has occurred
     */
    @SuppressWarnings("unchecked")
    private synchronized void addLogPlugin(HttpServletRequest req,
            HttpServletResponse resp, String scope)
            throws ServletException, IOException, EBaseException {

        String auditSubjectID = auditSubjectID();

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            String logType = null;
            String id = req.getParameter(Constants.RS_ID);

            // if this "required" parameter is not present,
            // always log messages to the signed audit log
            logType = id;
            if (logType == null) {
                logType = SIGNED_AUDIT_LOG_TYPE;
            }

            if (id == null) {
                //System.out.println("SRVLT_NULL_RS_ID");

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                        null, resp);
                return;
            }

            // is the log id unique?
            if (logSubsystem.getLogPlugins().containsKey(id)) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        new ELogException(CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ILL_PLUGIN_ID", id))
                                .toString(),
                        null, resp);
                return;
            }

            String classPath = req.getParameter(Constants.PR_LOG_CLASS);

            if (classPath == null) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_NULL_CLASS"),
                        null, resp);
                return;
            }

            LoggingConfig destStore = mConfig.getLoggingConfig();
            LoggerPluginsConfig instancesConfig = destStore.getLoggerPluginsConfig();

            // Does the class exist?
            Class<LogEventListener> newImpl = null;

            try {
                newImpl = (Class<LogEventListener>) Class.forName(classPath);
            } catch (ClassNotFoundException e) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_NO_CLASS"),
                        null, resp);
                return;
            } catch (IllegalArgumentException e) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_NO_CLASS"),
                        null, resp);
                return;
            }

            // is the class an ILogEventListner?
            try {
                if (LogEventListener.class.isAssignableFrom(newImpl) == false) {
                    // store a message in the signed audit log file
                    if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                        audit(new ConfigSignedAuditEvent(
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditParams(req)));
                    }

                    sendResponse(ERROR,
                            CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ILL_CLASS"),
                            null, resp);
                    return;
                }
            } catch (NullPointerException e) { // unlikely, only if newImpl null.
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ILL_CLASS"),
                        null, resp);
                return;
            }

            ConfigStore substore = instancesConfig.makeSubStore(id);

            substore.put(Constants.PR_LOG_CLASS, classPath);

            // commiting
            try {
                mConfig.commit(true);
            } catch (EBaseException e) {
                //System.out.println("SRVLT_FAIL_COMMIT");

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_COMMIT_FAILED"),
                        null, resp);
                return;
            }

            // add log to registry.
            LogPlugin plugin = new LogPlugin(id, classPath);

            logSubsystem.getLogPlugins().put(id, plugin);

            NameValuePairs params = new NameValuePairs();

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.SUCCESS,
                            auditParams(req)));
            }

            sendResponse(SUCCESS, null, params, resp);
            return;
            // } catch( EBaseException eAudit1 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit1;
        } catch (IOException eAudit2) {

            audit(new ConfigSignedAuditEvent(
                        auditSubjectID,
                        ILogger.FAILURE,
                        auditParams(req)));

            // rethrow the specific exception to be handled later
            throw eAudit2;
            // } catch( ServletException eAudit3 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit3;
        }
    }

    private boolean isValidID(String id) {
        if (id == null)
            return false;
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isLetterOrDigit(id.charAt(i)))
                return false;
        }
        return true;
    }

    /**
     * Add log instance
     * <P>
     *
     * <ul>
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT used when configuring signedAudit
     * </ul>
     *
     * @param req HTTP servlet request
     * @param resp HTTP servlet response
     * @param scope string used to obtain the contents of the log's substore
     * @exception ServletException a servlet error has occurred
     * @exception IOException an input/output error has occurred
     * @exception EBaseException an error has occurred
     */
    private synchronized void addLogInst(HttpServletRequest req,
            HttpServletResponse resp, String scope)
            throws ServletException, IOException, EBaseException {

        String auditSubjectID = auditSubjectID();

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            String logType = null;
            String id = req.getParameter(Constants.RS_ID);

            // if this "required" parameter is not present,
            // always log messages to the signed audit log
            logType = id;
            if (logType == null) {
                logType = SIGNED_AUDIT_LOG_TYPE;
            }

            if (id == null) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                        null, resp);
                return;
            }

            if (!isValidID(id)) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR, "Invalid ID '" + id + "'",
                        null, resp);
                return;
            }

            if (logSubsystem.getLogInsts().containsKey(id)) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ILL_INST_ID"),
                        null, resp);
                return;
            }

            // get required parameters
            String implname = req.getParameter(
                    Constants.PR_LOG_IMPL_NAME);

            if (implname == null) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ADD_MISSING_PARAMS"),
                        null, resp);
                return;
            }

            // check if implementation exists.
            LogPlugin plugin =
                    logSubsystem.getLogPlugins().get(
                    implname);

            if (plugin == null) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(
                        ERROR,
                        new ELogPluginNotFound(CMS.getUserMessage(getLocale(req), "CMS_LOG_PLUGIN_NOT_FOUND", implname))
                                .toString(),
                        null, resp);
                return;
            }

            Vector<String> configParams = logSubsystem.getLogDefaultParams(implname);

            LoggingConfig destStore = mConfig.getLoggingConfig();
            LoggersConfig instancesConfig = destStore.getLoggersConfig();
            LoggerConfig substore = instancesConfig.createLoggerConfig(id);

            if (configParams != null) {
                for (int i = 0; i < configParams.size(); i++) {
                    String kv = configParams.elementAt(i);
                    int index = kv.indexOf('=');
                    String val = req.getParameter(kv.substring(0, index));

                    if (val == null) {
                        substore.put(kv.substring(0, index),
                                kv.substring(index + 1));
                    } else {
                        substore.put(kv.substring(0, index),
                                val);
                    }
                }
            }
            substore.put("pluginName", implname);

            // Fix Blackflag Bug #615603:  Currently, although expiring log
            // files is no longer supported, it is still a required parameter
            // that must be present during the creation and modification of
            // custom log plugins.
            substore.put(Constants.PR_LOG_EXPIRED_TIME, "0");

            // Instantiate an object for this implementation
            String className = plugin.getClassPath();
            LogEventListener logInst = null;

            try {
                logInst = (LogEventListener) Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                instancesConfig.removeSubStore(id);

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        new ELogException(CMS.getUserMessage(getLocale(req), "CMS_LOG_LOAD_CLASS_FAIL", className))
                                .toString(),
                        null, resp);
                return;
            }

            // initialize the log
            try {
                logInst.init(logSubsystem, substore);
            } catch (EBaseException e) {
                // don't commit in this case and cleanup the new substore.
                instancesConfig.removeSubStore(id);

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR, e.toString(getLocale(req)), null, resp);
                return;
            } catch (Throwable e) {
                instancesConfig.removeSubStore(id);

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR, e.toString(), null, resp);
                return;
            }

            // commiting
            try {
                mConfig.commit(true);
            } catch (EBaseException e) {
                // clean up.
                instancesConfig.removeSubStore(id);

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_COMMIT_FAILED"),
                        null, resp);
                return;
            }

            // inited and commited ok. now add log instance to list.
            logSubsystem.getLogInsts().put(id, logInst);

            NameValuePairs params = new NameValuePairs();

            params.put(Constants.PR_LOG_IMPL_NAME, implname);

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.SUCCESS,
                            auditParams(req)));
            }

            sendResponse(SUCCESS, null, params, resp);
            return;
            // } catch( EBaseException eAudit1 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit1;
        } catch (IOException eAudit2) {

            audit(new ConfigSignedAuditEvent(
                        auditSubjectID,
                        ILogger.FAILURE,
                        auditParams(req)));

            // rethrow the specific exception to be handled later
            throw eAudit2;
            // } catch( ServletException eAudit3 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit3;
        }
    }

    private synchronized void listLogPlugins(HttpServletRequest req,
            HttpServletResponse resp) throws ServletException,
            IOException, EBaseException {
        NameValuePairs params = new NameValuePairs();
        Enumeration<String> e = logSubsystem.getLogPlugins().keys();

        while (e.hasMoreElements()) {
            String name = e.nextElement();
            LogPlugin value = logSubsystem.getLogPlugins().get(name);
            // get Description
            String c = value.getClassPath();
            String desc = "unknown";

            try {
                LogEventListener lp = (LogEventListener) Class.forName(c).getDeclaredConstructor().newInstance();

                desc = lp.getDescription();
            } catch (Exception exp) {
                sendResponse(ERROR, exp.toString(), null,
                        resp);
                return;
            }
            params.put(name, value.getClassPath() + "," + desc);
        }
        sendResponse(SUCCESS, null, params, resp);
        return;
    }

    public String getLogPluginName(LogEventListener log) {
        ConfigStore cs = log.getConfigStore();

        try {
            return cs.getString("pluginName", "");
        } catch (EBaseException e) {
            return "";
        }
    }

    /**
     * Delete log instance
     * <P>
     *
     * <ul>
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT used when configuring signedAudit
     * </ul>
     *
     * @param req HTTP servlet request
     * @param resp HTTP servlet response
     * @param scope string used to obtain the contents of the log's substore
     * @exception ServletException a servlet error has occurred
     * @exception IOException an input/output error has occurred
     * @exception EBaseException an error has occurred
     */
    private synchronized void delLogInst(HttpServletRequest req,
            HttpServletResponse resp, String scope)
            throws ServletException, IOException, EBaseException {

        String auditSubjectID = auditSubjectID();

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            String logType = null;
            NameValuePairs params = new NameValuePairs();
            String id = req.getParameter(Constants.RS_ID);

            // if this "required" parameter is not present,
            // always log messages to the signed audit log
            logType = id;
            if (logType == null) {
                logType = SIGNED_AUDIT_LOG_TYPE;
            }

            if (id == null) {
                //System.out.println("SRVLT_NULL_RS_ID");

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                        null, resp);
                return;
            }

            // Does the log instance exist?
            if (logSubsystem.getLogInsts().containsKey(id) == false) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        new ELogNotFound(CMS.getUserMessage(getLocale(req), "CMS_LOG_INSTANCE_NOT_FOUND", id))
                                .toString(),
                        null, resp);
                return;
            }

            // only remove from memory
            // cannot shutdown because we don't keep track of whether it's
            // being used.
            logSubsystem.getLogInsts().remove(id);

            // remove the configuration.
            LoggingConfig destStore = mConfig.getLoggingConfig();
            LoggersConfig instancesConfig = destStore.getLoggersConfig();
            instancesConfig.removeLoggerConfig(id);

            // commiting
            try {
                mConfig.commit(true);
            } catch (EBaseException e) {
                //System.out.println("SRVLT_FAIL_COMMIT");

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_COMMIT_FAILED"),
                        null, resp);
                return;
            }

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.SUCCESS,
                            auditParams(req)));
            }

            sendResponse(SUCCESS, null, params, resp);
            return;
            // } catch( EBaseException eAudit1 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit1;
        } catch (IOException eAudit2) {

            audit(new ConfigSignedAuditEvent(
                        auditSubjectID,
                        ILogger.FAILURE,
                        auditParams(req)));

            // rethrow the specific exception to be handled later
            throw eAudit2;
            // } catch( ServletException eAudit3 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit3;
        }
    }

    /**
     * Delete log plug-in
     * <P>
     *
     * <ul>
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT used when configuring signedAudit
     * </ul>
     *
     * @param req HTTP servlet request
     * @param resp HTTP servlet response
     * @param scope string used to obtain the contents of the log's substore
     * @exception ServletException a servlet error has occurred
     * @exception IOException an input/output error has occurred
     * @exception EBaseException an error has occurred
     */
    private synchronized void delLogPlugin(HttpServletRequest req,
            HttpServletResponse resp, String scope)
            throws ServletException, IOException, EBaseException {

        String auditSubjectID = auditSubjectID();

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            String logType = null;
            NameValuePairs params = new NameValuePairs();
            String id = req.getParameter(Constants.RS_ID);

            // if this "required" parameter is not present,
            // always log messages to the signed audit log
            logType = id;
            if (logType == null) {
                logType = SIGNED_AUDIT_LOG_TYPE;
            }

            if (id == null) {
                //System.out.println("SRVLT_NULL_RS_ID");

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                        null, resp);
                return;
            }

            if (logSubsystem.getLogPlugins().containsKey(id) == false) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        new ELogPluginNotFound(CMS.getUserMessage(getLocale(req), "CMS_LOG_PLUGIN_NOT_FOUND", id))
                                .toString(),
                        null, resp);
                return;
            }

            // first check if any instances from this log
            // DON'T remove log if any instance
            for (Enumeration<String> e = logSubsystem.getLogInsts().keys(); e.hasMoreElements();) {
                String name = e.nextElement();
                LogEventListener log = logSubsystem.getLogInstance(name);

                if (getLogPluginName(log) == id) {
                    // store a message in the signed audit log file
                    if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                        audit(new ConfigSignedAuditEvent(
                                    auditSubjectID,
                                    ILogger.FAILURE,
                                    auditParams(req)));
                    }

                    sendResponse(ERROR,
                            CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_IN_USE"),
                            null, resp);
                    return;
                }
            }

            // then delete this log
            logSubsystem.getLogPlugins().remove(id);

            LoggingConfig destStore = mConfig.getLoggingConfig();
            LoggerPluginsConfig instancesConfig = destStore.getLoggerPluginsConfig();

            instancesConfig.removeSubStore(id);
            // commiting
            try {
                mConfig.commit(true);
            } catch (EBaseException e) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_COMMIT_FAILED"),
                        null, resp);
                return;
            }

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.SUCCESS,
                            auditParams(req)));
            }

            sendResponse(SUCCESS, null, params, resp);
            return;
            // } catch( EBaseException eAudit1 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit1;
        } catch (IOException eAudit2) {

            audit(new ConfigSignedAuditEvent(
                        auditSubjectID,
                        ILogger.FAILURE,
                        auditParams(req)));

            // rethrow the specific exception to be handled later
            throw eAudit2;
            // } catch( ServletException eAudit3 ) {
            //     // store a message in the signed audit log file
            //     auditMessage = CMS.getLogMessage(
            //                        LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                        auditSubjectID,
            //                        ILogger.FAILURE,
            //                        auditParams( req ) );
            //
            //     audit( auditMessage );
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit3;
        }
    }

    /**
     * Modify log instance
     * <P>
     *
     * <ul>
     * <li>signed.audit LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT used when configuring signedAudit
     * <li>signed.audit LOGGING_SIGNED_AUDIT_LOG_PATH_CHANGE used when log file name (including any path changes) for
     * any of audit, system, transaction, or other customized log file change is attempted (authorization should not
     * allow, but make sure it's written after the attempt)
     * <li>signed.audit LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE used when log expiration time change is attempted
     * (authorization should not allow, but make sure it's written after the attempt)
     * </ul>
     *
     * @param req HTTP servlet request
     * @param resp HTTP servlet response
     * @param scope string used to obtain the contents of the log's substore
     * @exception ServletException a servlet error has occurred
     * @exception IOException an input/output error has occurred
     * @exception EBaseException an error has occurred
     */
    private synchronized void modLogInst(HttpServletRequest req,
            HttpServletResponse resp, String scope)
            throws ServletException, IOException, EBaseException {

        String auditMessage = null;
        String auditSubjectID = auditSubjectID();
        String logType = null;
        String origLogPath = req.getParameter(Constants.PR_LOG_FILENAME);
        String newLogPath = origLogPath;
        String origExpirationTime = req.getParameter(
                Constants.PR_LOG_EXPIRED_TIME);
        String newExpirationTime = origExpirationTime;

        // ensure that any low-level exceptions are reported
        // to the signed audit log and stored as failures
        try {
            String id = req.getParameter(Constants.RS_ID);

            // if this "required" parameter is not present,
            // always log messages to the signed audit log
            logType = id;
            if (logType == null) {
                logType = SIGNED_AUDIT_LOG_TYPE;
            }

            if (origLogPath != null) {
                origLogPath = origLogPath.trim();
                newLogPath = newLogPath.trim();
            } else {
                origLogPath = "";
                newLogPath = "";
            }

            if (origExpirationTime != null) {
                origExpirationTime = origExpirationTime.trim();
                newExpirationTime = newExpirationTime.trim();
            } else {
                origExpirationTime = "";
                newExpirationTime = "";
            }

            if (id == null) {
                //System.out.println("SRVLT_NULL_RS_ID");

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                        null, resp);
                return;
            }

            // Does the manager instance exist?
            if (!logSubsystem.getLogInsts().containsKey(id)) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ILL_INST_ID"),
                        null, resp);
                return;
            }

            // get new implementation (same or different.)
            String implname = req.getParameter(Constants.PR_LOG_IMPL_NAME);

            if (implname == null) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_LOG_SRVLT_ADD_MISSING_PARAMS"),

                        null, resp);
                return;
            }
            // get plugin for implementation
            LogPlugin plugin =
                    logSubsystem.getLogPlugins().get(implname);

            if (plugin == null) {
                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(
                        ERROR,
                        new ELogPluginNotFound(CMS.getUserMessage(getLocale(req), "CMS_LOG_PLUGIN_NOT_FOUND", implname))
                                .toString(), null, resp);
                return;
            }

            // save old instance substore params in case new one fails.

            LogEventListener oldinst =
                    logSubsystem.getLogInstance(id);
            Vector<String> oldConfigParms = oldinst.getInstanceParams();
            NameValuePairs saveParams = new NameValuePairs();

            // implName is always required so always include it it.
            saveParams.put("pluginName", implname);
            if (oldConfigParms != null) {
                for (int i = 0; i < oldConfigParms.size(); i++) {
                    String kv = oldConfigParms.elementAt(i);
                    int index = kv.indexOf('=');

                    saveParams.put(kv.substring(0, index),
                            kv.substring(index + 1));
                }
            }

            // on to the new instance.

            // remove old substore.

            LoggingConfig destStore = mConfig.getLoggingConfig();
            LoggersConfig instancesConfig = destStore.getLoggersConfig();

            // create new substore.

            Vector<String> configParams = logSubsystem.getLogInstanceParams(id);

            //instancesConfig.removeSubStore(id);

            LoggerConfig substore = instancesConfig.createLoggerConfig(id);

            substore.put("pluginName", implname);

            // Fix Blackflag Bug #615603:  Currently, although expiring log
            // files is no longer supported, it is still a required parameter
            // that must be present during the creation and modification of
            // custom log plugins.
            substore.put(Constants.PR_LOG_EXPIRED_TIME, "0");

            // IMPORTANT:  save a copy of the original log file path
            origLogPath = substore.getString(Constants.PR_LOG_FILENAME);
            newLogPath = origLogPath;

            if (origLogPath != null) {
                origLogPath = origLogPath.trim();
                newLogPath = newLogPath.trim();
            } else {
                origLogPath = "";
                newLogPath = "";
            }

            // IMPORTANT:  save a copy of the original log expiration time
            origExpirationTime = substore.getString(
                        Constants.PR_LOG_EXPIRED_TIME);
            newExpirationTime = origExpirationTime;

            if (origExpirationTime != null) {
                origExpirationTime = origExpirationTime.trim();
                newExpirationTime = newExpirationTime.trim();
            } else {
                origExpirationTime = "";
                newExpirationTime = "";
            }

            if (configParams != null) {
                for (int i = 0; i < configParams.size(); i++) {
                    AUTHZ_RES_NAME =
                            "certServer.log.configuration";
                    String kv = configParams.elementAt(i);
                    int index = kv.indexOf('=');
                    String key = kv.substring(0, index);
                    String val = req.getParameter(key);

                    if (key.equals("level")) {
                        if (val.equals(ILogger.LL_DEBUG_STRING))
                            val = "0";
                        else if (val.equals(ILogger.LL_INFO_STRING))
                            val = "1";
                        else if (val.equals(ILogger.LL_WARN_STRING))
                            val = "2";
                        else if (val.equals(ILogger.LL_FAILURE_STRING))
                            val = "3";
                        else if (val.equals(ILogger.LL_MISCONF_STRING))
                            val = "4";
                        else if (val.equals(ILogger.LL_CATASTRPHE_STRING))
                            val = "5";
                        else if (val.equals(ILogger.LL_SECURITY_STRING))
                            val = "6";

                    }

                    if (key.equals(Constants.PR_LOG_ROLLEROVER_INTERVAL)) {
                        if (val.equals("Hourly"))
                            val = Integer.toString(60 * 60);
                        else if (val.equals("Daily"))
                            val = Integer.toString(60 * 60 * 24);
                        else if (val.equals("Weekly"))
                            val = Integer.toString(60 * 60 * 24 * 7);
                        else if (val.equals("Monthly"))
                            val = Integer.toString(60 * 60 * 24 * 30);
                        else if (val.equals("Yearly"))
                            val = Integer.toString(60 * 60 * 24 * 365);
                    }

                    if (val != null) {
                        if (key.equals("fileName")) {
                            String origVal = substore.getString(key);

                            val = val.trim();
                            newLogPath = val;
                            if (!val.equals(origVal.trim())) {
                                AUTHZ_RES_NAME =
                                        "certServer.log.configuration.fileName";
                                mOp = "modify";
                                if ((mToken = super.authorize(req)) == null) {
                                    // store a message in the signed audit log
                                    // file (regardless of logType)
                                    if (!(newLogPath.equals(origLogPath))) {
                                        auditMessage = CMS.getLogMessage(
                                                    AuditEvent.LOG_PATH_CHANGE,
                                                    auditSubjectID,
                                                    ILogger.FAILURE,
                                                    logType,
                                                    newLogPath);

                                        audit(auditMessage);
                                    }

                                    // store a message in the signed audit log
                                    // file
                                    if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                                        audit(new ConfigSignedAuditEvent(
                                                    auditSubjectID,
                                                    ILogger.FAILURE,
                                                    auditParams(req)));
                                    }

                                    sendResponse(ERROR,
                                            CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                            null, resp);
                                    return;
                                }
                            }
                        }
                        /*
                                                if (key.equals(Constants.PR_LOG_EXPIRED_TIME)) {
                                                    String origVal = substore.getString(key);

                                                    val = val.trim();
                                                    newExpirationTime = val;
                                                    if (!val.equals(origVal.trim())) {
                                                        if (id.equals(SIGNED_AUDIT_LOG_TYPE)) {
                                                            AUTHZ_RES_NAME =
                                                                    "certServer.log.configuration.signedAudit.expirationTime";
                                                        }
                                                        mOp = "modify";
                                                        if ((mToken = super.authorize(req)) == null) {
                                                            // store a message in the signed audit log
                                                            // file (regardless of logType)
                                                            if (!(newExpirationTime.equals(origExpirationTime))) {
                                                                auditMessage = CMS.getLogMessage(
                                                                            LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
                                                                            auditSubjectID,
                                                                            ILogger.FAILURE,
                                                                            logType,
                                                                            newExpirationTime);

                                                                audit(auditMessage);
                                                            }

                                                            // store a message in the signed audit log
                                                            // file
                                                            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {
                                                                auditMessage = CMS.getLogMessage(
                                                                            LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
                                                                            auditSubjectID,
                                                                            ILogger.FAILURE,
                                                                            auditParams(req));

                                                                audit(auditMessage);
                                                            }

                                                            sendResponse(ERROR,
                                                                CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_AUTHZ_FAILED"),
                                                                null, resp);
                                                            return;
                                                        }
                                                    }
                                                }
                        */
                        substore.put(key, val);
                    }
                }
            }

            // Instantiate an object for new implementation

            String className = plugin.getClassPath();
            @SuppressWarnings("unused")
            LogEventListener newMgrInst = null;

            try {
                newMgrInst = (LogEventListener) Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                // check to see if the log file path parameter was changed
                newLogPath = auditCheckLogPath(req);

                // check to see if the log expiration time parameter was changed
                //newExpirationTime = auditCheckLogExpirationTime(req);

                restore(instancesConfig, id, saveParams);

                // store a message in the signed audit log file
                // (regardless of logType)
                if (!(newLogPath.equals(origLogPath))) {
                    auditMessage = CMS.getLogMessage(
                                AuditEvent.LOG_PATH_CHANGE,
                                auditSubjectID,
                                ILogger.FAILURE,
                                logType,
                                newLogPath);

                    audit(auditMessage);
                }

                // store a message in the signed audit log file
                // (regardless of logType)
                /* if (!(newExpirationTime.equals(origExpirationTime))) {
                    auditMessage = CMS.getLogMessage(
                                LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
                                auditSubjectID,
                                ILogger.FAILURE,
                                logType,
                                newExpirationTime);

                    audit(auditMessage);
                } */

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        new ELogException(CMS.getUserMessage(getLocale(req), "CMS_LOG_LOAD_CLASS_FAIL", className))
                                .toString(),
                        null, resp);
                return;
            }
            // initialize the log

            // initialized ok.  commiting
            try {
                mConfig.commit(true);
            } catch (EBaseException e) {
                // check to see if the log file path parameter was changed
                newLogPath = auditCheckLogPath(req);

                // check to see if the log expiration time parameter was changed
                // newExpirationTime = auditCheckLogExpirationTime(req);

                // clean up.
                restore(instancesConfig, id, saveParams);
                //System.out.println("SRVLT_FAIL_COMMIT");

                // store a message in the signed audit log file
                // (regardless of logType)
                if (!(newLogPath.equals(origLogPath))) {
                    auditMessage = CMS.getLogMessage(
                                AuditEvent.LOG_PATH_CHANGE,
                                auditSubjectID,
                                ILogger.FAILURE,
                                logType,
                                newLogPath);

                    audit(auditMessage);
                }

                // store a message in the signed audit log file
                // (regardless of logType)
                /* if (!(newExpirationTime.equals(origExpirationTime))) {
                    auditMessage = CMS.getLogMessage(
                                LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
                                auditSubjectID,
                                ILogger.FAILURE,
                                logType,
                                newExpirationTime);

                    audit(auditMessage);
                }*/

                // store a message in the signed audit log file
                if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                    audit(new ConfigSignedAuditEvent(
                                auditSubjectID,
                                ILogger.FAILURE,
                                auditParams(req)));
                }

                sendResponse(ERROR,
                        CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_COMMIT_FAILED"),
                        null, resp);
                return;
            }

            // commited ok. replace instance.

            // REMOVED - we didn't do anything to shut off the old instance
            // so, it will still be running at this point. You'd have two
            // log isntances writing to the same file - this would be a big PROBLEM!!!

            //mSys.getLogInsts().put(id, newMgrInst);

            NameValuePairs params = new NameValuePairs();

            // check to see if the log file path parameter was changed
            newLogPath = auditCheckLogPath(req);

            // check to see if the log expiration time parameter was changed
            //newExpirationTime = auditCheckLogExpirationTime(req);

            // store a message in the signed audit log file
            // (regardless of logType)
            if (!(newLogPath.equals(origLogPath))) {
                auditMessage = CMS.getLogMessage(
                            AuditEvent.LOG_PATH_CHANGE,
                            auditSubjectID,
                            ILogger.SUCCESS,
                            logType,
                            newLogPath);

                audit(auditMessage);
            }

            // store a message in the signed audit log file
            // (regardless of logType)
            /*if (!(newExpirationTime.equals(origExpirationTime))) {
                auditMessage = CMS.getLogMessage(
                            LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
                            auditSubjectID,
                            ILogger.SUCCESS,
                            logType,
                            newExpirationTime);

                audit(auditMessage);
            }*/

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.SUCCESS,
                            auditParams(req)));
            }

            sendResponse(RESTART, null, params, resp);
            return;
        } catch (EBaseException eAudit1) {
            // check to see if the log file path parameter was changed
            newLogPath = auditCheckLogPath(req);

            // check to see if the log expiration time parameter was changed
            // newExpirationTime = auditCheckLogExpirationTime(req);

            // store a message in the signed audit log file
            // (regardless of logType)
            if (!(newLogPath.equals(origLogPath))) {
                auditMessage = CMS.getLogMessage(
                            AuditEvent.LOG_PATH_CHANGE,
                            auditSubjectID,
                            ILogger.FAILURE,
                            logType,
                            newLogPath);

                audit(auditMessage);
            }

            // store a message in the signed audit log file
            // (regardless of logType)
            /* if (!(newExpirationTime.equals(origExpirationTime))) {
                auditMessage = CMS.getLogMessage(
                            LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
                            auditSubjectID,
                            ILogger.FAILURE,
                            logType,
                            newExpirationTime);

                audit(auditMessage);
            } */

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.FAILURE,
                            auditParams(req)));
            }

            // rethrow the specific exception to be handled later
            throw eAudit1;
        } catch (IOException eAudit2) {
            // check to see if the log file path parameter was changed
            newLogPath = auditCheckLogPath(req);

            // check to see if the log expiration time parameter was changed
            // newExpirationTime = auditCheckLogExpirationTime(req);

            // store a message in the signed audit log file
            // (regardless of logType)
            if (!(newLogPath.equals(origLogPath))) {
                auditMessage = CMS.getLogMessage(
                            AuditEvent.LOG_PATH_CHANGE,
                            auditSubjectID,
                            ILogger.FAILURE,
                            logType,
                            newLogPath);

                audit(auditMessage);
            }

            // store a message in the signed audit log file
            // (regardless of logType)
            /*if (!(newExpirationTime.equals(origExpirationTime))) {
                auditMessage = CMS.getLogMessage(
                            LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
                            auditSubjectID,
                            ILogger.FAILURE,
                            logType,
                            newExpirationTime);

                audit(auditMessage);
            }*/

            // store a message in the signed audit log file
            if (logType.equals(SIGNED_AUDIT_LOG_TYPE)) {

                audit(new ConfigSignedAuditEvent(
                            auditSubjectID,
                            ILogger.FAILURE,
                            auditParams(req)));
            }

            // rethrow the specific exception to be handled later
            throw eAudit2;
            // } catch( ServletException eAudit3 ) {
            //     // check to see if the log file path parameter was changed
            //     newLogPath = auditCheckLogPath( req );
            //
            //     // check to see if the log expiration time parameter was changed
            //     newExpirationTime = auditCheckLogExpirationTime( req );
            //
            //     // store a message in the signed audit log file
            //     // (regardless of logType)
            //     if( !( newLogPath.equals( origLogPath ) ) ) {
            //         auditMessage = CMS.getLogMessage(
            //                            LOGGING_SIGNED_AUDIT_LOG_PATH_CHANGE,
            //                            auditSubjectID,
            //                            ILogger.FAILURE,
            //                            logType,
            //                            newLogPath );
            //
            //         audit( auditMessage );
            //     }
            //
            //     // store a message in the signed audit log file
            //     // (regardless of logType)
            //     if( !( newExpirationTime.equals( origExpirationTime ) ) ) {
            //         auditMessage = CMS.getLogMessage(
            //                            LOGGING_SIGNED_AUDIT_LOG_EXPIRATION_CHANGE,
            //                            auditSubjectID,
            //                            ILogger.FAILURE,
            //                            logType,
            //                            newExpirationTime );
            //
            //         audit( auditMessage );
            //     }
            //
            //     // store a message in the signed audit log file
            //     if( logType.equals( SIGNED_AUDIT_LOG_TYPE ) ) {
            //         auditMessage = CMS.getLogMessage(
            //                            LOGGING_SIGNED_AUDIT_CONFIG_SIGNED_AUDIT,
            //                            auditSubjectID,
            //                            ILogger.FAILURE,
            //                            auditParams( req ) );
            //
            //         audit( auditMessage );
            //     }
            //
            //     // rethrow the specific exception to be handled later
            //     throw eAudit3;
        }
    }

    /**
     * used for getting the required configuration parameters (with
     * possible default values) for a particular plugin
     * implementation name specified in the RS_ID. Actually, there is
     * no logic in here to set any default value here...there's no
     * default value for any parameter in this log subsystem
     * at this point. Later, if we do have one (or some), it can be
     * added. The interface remains the same.
     */
    private synchronized void getConfig(HttpServletRequest req,
            HttpServletResponse resp)
            throws ServletException, IOException, EBaseException {

        String implname = req.getParameter(Constants.RS_ID);

        if (implname == null) {
            //System.out.println("SRVLT_NULL_RS_ID");
            sendResponse(ERROR,
                    CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                    null, resp);
            return;
        }

        Vector<String> configParams = logSubsystem.getLogDefaultParams(implname);
        NameValuePairs params = new NameValuePairs();

        // implName is always required so always send it.
        params.put(Constants.PR_LOG_IMPL_NAME, "");
        if (configParams != null) {
            for (int i = 0; i < configParams.size(); i++) {
                String kv = configParams.elementAt(i);
                int index = kv.indexOf('=');

                if (index == -1) {
                    params.put(kv, "");
                } else {
                    params.put(kv.substring(0, index),
                            kv.substring(index + 1));
                }
            }
        }
        sendResponse(0, null, params, resp);
        return;
    }

    private synchronized void getInstConfig(HttpServletRequest req,
            HttpServletResponse resp) throws ServletException,
            IOException, EBaseException {

        String id = req.getParameter(Constants.RS_ID);

        if (id == null) {
            //System.out.println("SRVLT_NULL_RS_ID");
            sendResponse(ERROR,
                    CMS.getUserMessage(getLocale(req), "CMS_ADMIN_SRVLT_NULL_RS_ID"),
                    null, resp);
            return;
        }

        // does log instance exist?
        if (logSubsystem.getLogInsts().containsKey(id) == false) {
            sendResponse(ERROR,
                    new ELogNotFound(CMS.getUserMessage(getLocale(req), "CMS_LOG_INSTANCE_NOT_FOUND", id)).toString(),
                    null, resp);
            return;
        }

        LogEventListener logInst = logSubsystem.getLogInstance(id);
        Vector<String> configParams = logInst.getInstanceParams();
        NameValuePairs params = new NameValuePairs();

        params.put(Constants.PR_LOG_IMPL_NAME,
                getLogPluginName(logInst));
        // implName is always required so always send it.
        if (configParams != null) {
            for (int i = 0; i < configParams.size(); i++) {
                String kv = configParams.elementAt(i);
                int index = kv.indexOf('=');

                params.put(kv.substring(0, index),
                        kv.substring(index + 1));
            }
        }

        sendResponse(SUCCESS, null, params, resp);
        return;
    }

    // convenience routine.
    private static void restore(LoggersConfig store,
            String id, NameValuePairs saveParams) {
        store.removeLoggerConfig(id);
        LoggerConfig rstore = store.createLoggerConfig(id);

        for (String key : saveParams.keySet()) {
            String value = saveParams.get(key);

            if (value != null)
                rstore.put(key, value);
        }
    }

    /**
     * Signed Audit Check Log Path
     *
     * This method is called to extract the log file path.
     * <P>
     *
     * @param req http servlet request
     * @return a string containing the log file path
     */
    private String auditCheckLogPath(HttpServletRequest req) {
        // check to see if the log file path parameter was changed
        String logPath = req.getParameter(Constants.PR_LOG_FILENAME);

        if (logPath == null) {
            logPath = "";
        }

        logPath = logPath.trim();

        return logPath;
    }

    private void getGeneralConfig(HttpServletRequest req,
            HttpServletResponse resp) throws ServletException,
            IOException, EBaseException {

        NameValuePairs params = new NameValuePairs();
        String value = mConfig.getString(Constants.PR_DEBUG_LOG_LEVEL, "10"); // default: INFORM
        params.put(Constants.PR_DEBUG_LOG_LEVEL, value);

        sendResponse(SUCCESS, null, params, resp);
    }

    private void setGeneralConfig(HttpServletRequest req,
            HttpServletResponse resp) throws ServletException,
            IOException, EBaseException {

        Enumeration<String> enum1 = req.getParameterNames();
        boolean restart = false;

        while (enum1.hasMoreElements()) {
            String key = enum1.nextElement();
            String value = req.getParameter(key);

            if (key.equals(Constants.PR_DEBUG_LOG_LEVEL)) {
                try {
                    Integer.parseInt(value); // check for errors
                    mConfig.putString(Constants.PR_DEBUG_LOG_LEVEL, value);
                } catch (NumberFormatException e) {
                    logger.error("setGeneralConfig: Invalid value for " + Constants.PR_DEBUG_LOG_LEVEL + ": " + value + ": " + e.getMessage(), e);
                    throw new EBaseException("Invalid value for " + Constants.PR_DEBUG_LOG_LEVEL);
                }
            }
        }

        mConfig.commit(true);

        if (restart)
            sendResponse(RESTART, null, null, resp);
        else
            sendResponse(SUCCESS, null, null, resp);
    }

}
