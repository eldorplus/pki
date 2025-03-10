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

package org.dogtagpki.server.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import com.netscape.certsrv.base.BadRequestException;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.PKIException;
import com.netscape.certsrv.base.ResourceNotFoundException;
import com.netscape.certsrv.logging.AuditConfig;
import com.netscape.certsrv.logging.AuditFile;
import com.netscape.certsrv.logging.AuditFileCollection;
import com.netscape.certsrv.logging.AuditResource;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.certsrv.logging.event.ConfigSignedAuditEvent;
import com.netscape.cms.servlet.base.SubsystemService;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.apps.CMSEngine;
import com.netscape.cmscore.apps.EngineConfig;
import com.netscape.cmscore.logging.LogSubsystem;
import com.netscape.cmscore.logging.LoggerConfig;
import com.netscape.cmscore.logging.LoggersConfig;

/**
 * @author Endi S. Dewata
 */
public class AuditService extends SubsystemService implements AuditResource {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AuditService.class);

    public AuditService() {
        logger.debug("AuditService.<init>()");
    }

    public AuditConfig createAuditConfig() throws UnsupportedEncodingException, EBaseException {
        return createAuditConfig(null);
    }

    public AuditConfig createAuditConfig(Map<String, String> auditParams)
            throws UnsupportedEncodingException, EBaseException {

        CMSEngine engine = CMS.getCMSEngine();
        EngineConfig cs = engine.getConfig();
        LoggersConfig loggersConfig = cs.getLoggingConfig().getLoggersConfig();
        LoggerConfig loggerConfig = loggersConfig.getLoggerConfig("SignedAudit");

        AuditConfig auditConfig = new AuditConfig();
        String val = null;
        Boolean boolval = false;
        Integer integerval;

        val = loggerConfig.getBoolean("enable", false) ? "Enabled" : "Disabled";
        auditConfig.setStatus(val);
        if (auditParams != null)
            auditParams.put("enable", val);

        boolval = loggerConfig.getBoolean("logSigning", false);
        if (auditParams != null)
            auditParams.put("logSigning", boolval ? "true" : "false");
        auditConfig.setSigned(boolval);

        integerval = loggerConfig.getInteger("flushInterval", 5);
        auditConfig.setInterval(integerval);
        if (auditParams != null)
            auditParams.put("flushInterval", integerval.toString());

        integerval = loggerConfig.getInteger("bufferSize", 512);
        auditConfig.setBufferSize(integerval);
        if (auditParams != null)
            auditParams.put("bufferSize", integerval.toString());

        Map<String, String> eventConfigs = new TreeMap<>();

        LogSubsystem logSubsystem = engine.getLogSubsystem();

        // load all audit events as disabled initially
        for (String name : logSubsystem.getAuditEvents()) {
            eventConfigs.put(name, "disabled");
        }

        // overwrite with enabled events
        val = loggerConfig.getString("events", "");
        if (auditParams != null)
            auditParams.put("events", val);
        for (String event : StringUtils.split(val, ", ")) {
            eventConfigs.put(event.trim(), "enabled");
        }

        // overwrite with mandatory events
        val = loggerConfig.getString("mandatory.events", "");
        if (auditParams != null)
            auditParams.put("mandatory.events", val);
        for (String event : StringUtils.split(val, ", ")) {
            eventConfigs.put(event.trim(), "mandatory");
        }

        auditConfig.setEventConfigs(eventConfigs);

        return auditConfig;
    }

    @Override
    public Response getAuditConfig() {

        logger.debug("AuditService.getAuditConfig()");

        try {
            return createOKResponse(createAuditConfig());

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response updateAuditConfig(AuditConfig auditConfig) {
        Map<String, String> auditModParams = new HashMap<>();

        if (auditConfig == null) {
            BadRequestException e = new BadRequestException("Missing audit configuration");
            auditModParams.put("Info", e.toString());
            auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
            throw e;
        }

        logger.info("AuditService: Updating audit configuration:");

        CMSEngine engine = CMS.getCMSEngine();
        EngineConfig cs = engine.getConfig();
        LoggersConfig loggersConfig = cs.getLoggingConfig().getLoggersConfig();
        LoggerConfig loggerConfig = loggersConfig.getLoggerConfig("SignedAudit");

        try {
            AuditConfig currentAuditConfig = createAuditConfig();
            Map<String, String> currentEventConfigs = currentAuditConfig.getEventConfigs();

            if (auditConfig.getSigned() != null) {
                logger.info("AuditService: - log signing: " + auditConfig.getSigned());
                loggerConfig.putBoolean("logSigning", auditConfig.getSigned());
            }

            if (auditConfig.getInterval() != null) {
                logger.info("AuditService: - flush interval: " + auditConfig.getInterval());
                loggerConfig.putInteger("flushInterval", auditConfig.getInterval());
            }

            if (auditConfig.getBufferSize() != null) {
                logger.info("AuditService: - buffer size: " + auditConfig.getBufferSize());
                loggerConfig.putInteger("bufferSize", auditConfig.getBufferSize());
            }

            Map<String, String> eventConfigs = auditConfig.getEventConfigs();

            if (eventConfigs != null) {
                logger.info("AuditService: Updating audit events:");
                Collection<String> selected = new TreeSet<>();

                for (Map.Entry<String, String> entry : eventConfigs.entrySet()) {
                    String name = entry.getKey();
                    String value = entry.getValue();
                    logger.info("AuditService: - " + name + ": " + value);
                    String currentValue = currentEventConfigs.get(name);

                    // make sure no event is added
                    if (currentValue == null) {
                        PKIException e = new PKIException("Unable to add event: " + name);
                        auditModParams.put("Info", e.toString());
                        auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
                        throw e;
                    }

                    // make sure no optional event becomes mandatory
                    if ("mandatory".equals(value)) {
                        if (!"mandatory".equals(currentValue)) {
                            PKIException e = new PKIException("Unable to add mandatory event: " + name);
                            auditModParams.put("Info", e.toString());
                            auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
                            throw e;
                        }
                        continue;
                    }

                    // make sure no mandatory event becomes optional
                    if ("mandatory".equals(currentValue)) {
                        PKIException e = new PKIException("Unable to remove mandatory event: " + name);
                        auditModParams.put("Info", e.toString());
                        auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
                        throw e;
                    }

                    if ("enabled".equals(value)) {
                        selected.add(name);

                    } else if ("disabled".equals(value)) {
                        // do not add disabled event into list of enabled events

                    } else {
                        PKIException e = new PKIException("Invalid event configuration: " + name + "=" + value);
                        auditModParams.put("Info", e.toString());
                        auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
                        throw e;
                    }
                }

                loggerConfig.putString("events", StringUtils.join(selected, ","));
            }

            for (String name : currentEventConfigs.keySet()) {
                // make sure no event is removed
                if (!eventConfigs.containsKey(name)) {
                    PKIException e = new PKIException("Unable to remove event: " + name);
                    auditModParams.put("Info", e.toString());
                    auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
                    throw e;
                }
            }

            cs.commit(true);

            auditConfig = createAuditConfig(auditModParams);
            auditTPSConfigSignedAudit(ILogger.SUCCESS, auditModParams);

            return createOKResponse(auditConfig);

        } catch (PKIException e) {
            logger.error("Unable to update audit configuration: " + e.getMessage(), e);
            auditModParams.put("Info", e.toString());
            auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
            throw e;

        } catch (Exception e) {
            logger.error("Unable to update audit configuration: " + e.getMessage(), e);
            auditModParams.put("Info", e.toString());
            auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response changeAuditStatus(String action) {
        Map<String, String> auditModParams = new HashMap<>();

        logger.debug("AuditService.changeAuditStatus()");

        CMSEngine engine = CMS.getCMSEngine();
        EngineConfig cs = engine.getConfig();
        LoggersConfig loggersConfig = cs.getLoggingConfig().getLoggersConfig();
        LoggerConfig loggerConfig = loggersConfig.getLoggerConfig("SignedAudit");

        try {
            auditModParams.put("Action", action);

            if ("enable".equals(action)) {
                loggerConfig.putBoolean("enable", true);

            } else if ("disable".equals(action)) {
                loggerConfig.putBoolean("enable", false);

            } else {
                BadRequestException e = new BadRequestException("Invalid action " + action);
                auditModParams.put("Info", e.toString());
                auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
                throw e;
            }

            cs.commit(true);

            AuditConfig auditConfig = createAuditConfig();
            auditTPSConfigSignedAudit(ILogger.SUCCESS, auditModParams);

            return createOKResponse(auditConfig);

        } catch (PKIException e) {
            auditModParams.put("Info", e.toString());
            auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
            e.printStackTrace();
            throw e;

        } catch (Exception e) {
            auditModParams.put("Info", e.toString());
            auditTPSConfigSignedAudit(ILogger.FAILURE, auditModParams);
            e.printStackTrace();
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    public File getCurrentLogFile() {

        CMSEngine engine = CMS.getCMSEngine();
        EngineConfig cs = engine.getConfig();
        LoggersConfig loggersConfig = cs.getLoggingConfig().getLoggersConfig();
        LoggerConfig loggerConfig = loggersConfig.getLoggerConfig("SignedAudit");

        String filename = loggerConfig.get("fileName");
        return new File(filename);
    }

    public File getLogDirectory() {
        File file = getCurrentLogFile();
        return file.getParentFile();
    }

    public List<File> getLogFiles() {

        List<String> filenames = new ArrayList<>();

        File currentFile = getCurrentLogFile();
        String currentFilename = currentFile.getName();
        File logDir = currentFile.getParentFile();

        // add all log files except the current one
        for (String filename : logDir.list()) {
            if (filename.equals(currentFilename)) continue;
            filenames.add(filename);
        }

        // sort log files in ascending order
        Collections.sort(filenames);

        // add the current log file last (i.e. newest)
        filenames.add(currentFilename);

        List<File> files = new ArrayList<>();
        for (String filename : filenames) {
            files.add(new File(logDir, filename));
        }

        return files;
    }

    @Override
    public Response findAuditFiles() {

        AuditFileCollection response = new AuditFileCollection();

        List<File> files = getLogFiles();

        logger.debug("Audit files:");
        for (File file : files) {
            String name = file.getName();
            logger.debug("- " + name);

            AuditFile auditFile = new AuditFile();
            auditFile.setName(name);
            auditFile.setSize(file.length());

            response.addEntry(auditFile);
        }

        response.setTotal(files.size());

        return createOKResponse(response);
    }

    @Override
    public Response getAuditFile(String filename) {

        // make sure filename does not contain path
        if (!new File(filename).getName().equals(filename)) {
            logger.error("Invalid file name: " + filename);
            throw new BadRequestException("Invalid file name: " + filename);
        }

        File logDir = getLogDirectory();
        File file = new File(logDir, filename);

        if (!file.exists()) {
            throw new ResourceNotFoundException("File not found: " + filename);
        }

        StreamingOutput so = new StreamingOutput() {

            @Override
            public void write(OutputStream out) throws IOException, WebApplicationException {

                try (InputStream is = new FileInputStream(file)) {
                    IOUtils.copy(is, out);
                }
            }
        };

        return createOKResponse(so);
    }

    /*
     * in case of failure, "info" should be in the params
     */
    public void auditTPSConfigSignedAudit(String status, Map<String, String> params) {

        signedAuditLogger.log(new ConfigSignedAuditEvent(
                servletRequest.getUserPrincipal().getName(),
                status,
                auditor.getParamString(params)));
    }
}
