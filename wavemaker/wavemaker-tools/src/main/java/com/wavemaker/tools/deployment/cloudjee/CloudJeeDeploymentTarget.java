/*
 *  Copyright (C) 2012-2013 CloudJee, Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.tools.deployment.cloudjee;

import com.wavemaker.common.WMRuntimeException;
import com.wavemaker.runtime.RuntimeAccess;
import com.wavemaker.runtime.data.util.DataServiceConstants;
import com.wavemaker.tools.cloudfoundry.CloudFoundryUtils;
import com.wavemaker.tools.cloudfoundry.spinup.ProjectnameWithRandomApplicationNamingStrategy;
import com.wavemaker.tools.cloudfoundry.spinup.authentication.AuthenticationToken;
import com.wavemaker.tools.cloudfoundry.spinup.authentication.SharedSecret;
import com.wavemaker.tools.cloudfoundry.spinup.authentication.SharedSecretPropagation;
import com.wavemaker.tools.cloudfoundry.spinup.authentication.TransportToken;
import com.wavemaker.tools.data.BaseDataModelSetup;
import com.wavemaker.tools.data.DataModelConfiguration;
import com.wavemaker.tools.data.DataModelManager;
import com.wavemaker.tools.deployment.DeploymentDB;
import com.wavemaker.tools.deployment.DeploymentInfo;
import com.wavemaker.tools.deployment.DeploymentStatusException;
import com.wavemaker.tools.deployment.DeploymentTarget;
import com.wavemaker.tools.deployment.cloudfoundry.LoggingStatusCallback;
import com.wavemaker.tools.deployment.cloudfoundry.LoggingStatusCallback.Timer;
import com.wavemaker.tools.deployment.cloudfoundry.WebAppAssembler;
import com.wavemaker.tools.deployment.cloudfoundry.archive.ContentModifier;
import com.wavemaker.tools.deployment.cloudfoundry.archive.ModifiedContentApplicationArchive;
import com.wavemaker.tools.deployment.cloudfoundry.archive.StringReplaceContentModifier;
import com.wavemaker.tools.io.local.LocalFile;
import com.wavemaker.tools.project.DeploymentManager;
import com.wavemaker.tools.project.Project;
import com.wavemaker.tools.service.cloujeewrapper.CloudJeeClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.client.lib.CloudApplication;
import org.cloudfoundry.client.lib.CloudFoundryClient;
import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.lib.CloudService;
import org.cloudfoundry.client.lib.archive.ApplicationArchive;
import org.cloudfoundry.client.lib.archive.ZipApplicationArchive;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.util.WebUtils;

import javax.servlet.http.Cookie;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

public class CloudJeeDeploymentTarget implements DeploymentTarget {

    public static final String SUCCESS_RESULT = "SUCCESS";

    public static final String TOKEN_EXPIRED_RESULT = "ERROR: CloudFoundry login token expired";

    public static final String VMC_USERNAME_PROPERTY = "username";

    public static final String VMC_PASSWORD_PROPERTY = "password";

    public static final String VMC_URL_PROPERTY = "url";

    public static final Map<String, String> CONFIGURABLE_PROPERTIES;

    private static final String DEFAULT_URL = "https://api.cloudfoundry.com";

    private static final String SERVICE_TYPE = "database";

    public static final String MYSQL_SERVICE_VENDOR = "mysql";

    public static final String MYSQL_SERVICE_VERSION = "5.1";

    public static final String POSTGRES_SERVICE_VENDOR = "postgresql";

    public static final String POSTGRES_SERVICE_VERSION = "9.0";

    private static final String SERVICE_TIER = "free";

    private static final String TESTRUN_APP_NAME = "TestRun";

    private static final Log log = LogFactory.getLog(CloudJeeDeploymentTarget.class);

    private DataModelManager dataModelManager;

    private WebAppAssembler webAppAssembler;

    private final SharedSecretPropagation propagation = new SharedSecretPropagation();

    static {
        Map<String, String> props = new LinkedHashMap<String, String>();
        props.put(VMC_USERNAME_PROPERTY, "username@mydomain.com");
        props.put(VMC_PASSWORD_PROPERTY, "password");
        props.put(VMC_URL_PROPERTY, DEFAULT_URL);
        CONFIGURABLE_PROPERTIES = Collections.unmodifiableMap(props);
    }

    public void setDataModelManager(DataModelManager dataModelManager) {
        this.dataModelManager = dataModelManager;
    }

    public void setWebAppAssembler(WebAppAssembler webAppAssembler) {
        this.webAppAssembler = webAppAssembler;
    }

    @Override
    public void validateDeployment(DeploymentInfo deploymentInfo) throws DeploymentStatusException {
        CloudJeeClient client = getClient(deploymentInfo);
        /*createApplication(client, deploymentInfo, true);*/
    }

/*    @Deprecated
    String deploy(File webapp, DeploymentInfo deploymentInfo) throws DeploymentStatusException {
        try {
            validateWar(webapp);
            ZipFile zipFile = new ZipFile(webapp);
            ApplicationArchive applicationArchive = new ZipApplicationArchive(zipFile);
            return doDeploy(applicationArchive, deploymentInfo);
        } catch (IOException e) {
            throw new WMRuntimeException(e);
        }
    }*/

    @Override
    public String deploy(Project project, DeploymentInfo deploymentInfo, File tempWebAppRoot) throws DeploymentStatusException {
        LocalFile warFile = (LocalFile)project.getRootFolder().getFile(DeploymentManager.DIST_DIR_DEFAULT + project.getProjectName() + ".war");
        if(deploymentInfo.getApplicationName() != project.getProjectName()){
            warFile = (LocalFile)warFile.rename(deploymentInfo.getApplicationName()+".war");

        }
        CloudJeeClient client =   getClient(deploymentInfo);
        try {
            String response = client.deploy(warFile.getLocalFile(), deploymentInfo.getApplicationName());
            /* Changing back to the original name so as to decrease the size of folder */
            if(deploymentInfo.getApplicationName() != project.getProjectName()){
                warFile = (LocalFile)warFile.rename(project.getProjectName()+".war");
            }

            return response;
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }


    }

    /**
     * Initiate the test/run operation for the given project on the cloud foundry instance that is running studio. This
     * method is not part of the {@link com.wavemaker.tools.deployment.DeploymentTarget} interface but is called directly from
     * {@link com.wavemaker.tools.project.CloudFoundryDeploymentManager#testRunStart()} in order to reuse CloudFoundry deployment code.
     *
     * @param project
     * @return
     */
    public String testRunStart(Project project) {
        try {
            ApplicationArchive applicationArchive = this.webAppAssembler.assemble(project);
            applicationArchive = modifyApplicationArchive(applicationArchive);
            return " " ;//doDeploy(applicationArchive, getSelfDeploymentInfo(project), false);
        } catch (Exception e) {
            throw new WMRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * undeploy the given project from the cloud foundry instance that is running studio. This method is not part of the
     * {@link com.wavemaker.tools.deployment.DeploymentTarget} interface but is called directly from {@link com.wavemaker.tools.project.CloudFoundryDeploymentManager} in order to
     * reuse CloudFoundry deployment code.
     * 
     * @param project
     */
    public void undeploy(Project project) {
        try {
            undeploy(getSelfDeploymentInfo(project), false);
        } catch (DeploymentStatusException e) {
            throw new WMRuntimeException(e.getMessage(), e);
        }
    }

    private DeploymentInfo getSelfDeploymentInfo(Project project) {
        try {
            String cloudControllerUrl = CloudFoundryUtils.getControllerUrl();
            AuthenticationToken token = getAuthenticationToken();
            if (token == null) {
                // FIXME remove hard coded details
                String email = "wavemaker@vmware.com";
                String password = "password";
                CloudFoundryClient cloudFoundryClient = new CloudFoundryClient(email, password, cloudControllerUrl);
                token = new AuthenticationToken(cloudFoundryClient.login());
            }
            DeploymentInfo deploymentInfo = new DeploymentInfo();
            deploymentInfo.setToken(token.toString());
            deploymentInfo.setApplicationName(project.getProjectName());
            deploymentInfo.setTarget(cloudControllerUrl);
            deploymentInfo.setApplicationName(TESTRUN_APP_NAME);
            return deploymentInfo;
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
    }

    private AuthenticationToken getAuthenticationToken() {
        RuntimeAccess runtimeAccess = RuntimeAccess.getInstance();
        if (runtimeAccess == null) {
            return null;
        }
        Cookie cookie = WebUtils.getCookie(runtimeAccess.getRequest(), "wavemaker_authentication_token");
        if (cookie == null || !StringUtils.hasLength(cookie.getValue())) {
            return null;
        }
        SharedSecret sharedSecret = this.propagation.getForSelf(false);
        if (sharedSecret == null) {
            return null;
        }
        AuthenticationToken authenticationToken = sharedSecret.decrypt(TransportToken.decode(cookie.getValue()));
        return authenticationToken;
    }

    private ApplicationArchive modifyApplicationArchive(ApplicationArchive applicationArchive) {
        ContentModifier modifier = new StringReplaceContentModifier().forEntryName("index.html", "config.js", "login.html").replaceAll(
            "\\/wavemaker\\/", "/");
        return new ModifiedContentApplicationArchive(applicationArchive, modifier);
    }

    private void uploadAppliation(CloudFoundryClient client, String appName, ApplicationArchive applicationArchive) throws DeploymentStatusException {
        Timer timer = new Timer();
        try {
            client.uploadApplication(appName, applicationArchive, new LoggingStatusCallback(timer));
            log.info("Application upload completed in " + timer.stop() + "ms");
        } catch (HttpServerErrorException e) {
            throw new DeploymentStatusException("ERROR in upload application: " + e.getLocalizedMessage(), e);
        } catch (IOException ex) {
            throw new WMRuntimeException("Error ocurred while trying to upload WAR file.", ex);
        }
    }





    private Boolean appNameInUse(CloudFoundryClient client, String appName) {
        try {
            if (client.getApplication(appName) != null) {
                log.info("ApplicationName: " + appName + " is already in use");
                return true;
            } else {
                log.info("ApplicatonName with name: " + appName + "was NOT found");
                return false;
            }
        } catch (HttpClientErrorException e) {
            log.info("Failed to find aplicatonName with name: " + appName + ". Response was: " + e.getLocalizedMessage());
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                return true;
            } else {
                return false;
            }
        }
    }

    private String createApplication(CloudFoundryClient client, DeploymentInfo deploymentInfo, boolean checkExist) throws DeploymentStatusException {
        String appName = deploymentInfo.getApplicationName();
        if (appName == TESTRUN_APP_NAME) {
            if (appNameInUse(client, appName)) {
                deploymentInfo.setDeploymentUrl(client.getApplication(appName).getUris().get(0));
            } else {
                deploymentInfo.setDeploymentUrl(getUrl(deploymentInfo));
            }
        }
        if (deploymentInfo.getDeploymentUrl() == null || deploymentInfo.getDeploymentUrl().isEmpty()) {
            deploymentInfo.setDeploymentUrl(getUrl(deploymentInfo));
        }
        String url = deploymentInfo.getDeploymentUrl();

        if (checkExist && appNameInUse(client, appName)) {
            try {
                client.deleteApplication(appName);
            } catch (Exception e) {
                e.printStackTrace();
                throw new DeploymentStatusException("ERROR: Unable to delete existing application by same name. Choose another name");
            }
        }
        try {
            client.createApplication(appName, CloudApplication.SPRING, client.getDefaultApplicationMemory(CloudApplication.SPRING),
                Collections.singletonList(url), null, true);
            return url;
        } catch (CloudFoundryException e) {
            throw new DeploymentStatusException("ERROR in createApplication: " + e.getDescription(), e);
        }
    }

    /**
     * @param deploymentInfo
     * @return String generated URL
     */
    @Override
    public String getUrl(DeploymentInfo deploymentInfo) {
        String url = deploymentInfo.getTarget();
        if (!StringUtils.hasText(url)) {
            url = DEFAULT_URL;
        }
        ProjectnameWithRandomApplicationNamingStrategy pNameStrat = new ProjectnameWithRandomApplicationNamingStrategy(
            deploymentInfo.getApplicationName());
        return url.replace("api", pNameStrat.generateName());
    }

    /**
     * @param deploymentInfo
     */
    private void setupServices(CloudFoundryClient client, DeploymentInfo deploymentInfo) {
        if (CollectionUtils.isEmpty(deploymentInfo.getDatabases())) {
            return;
        }

        CloudApplication app = client.getApplication(deploymentInfo.getApplicationName());

        for (DeploymentDB db : deploymentInfo.getDatabases()) {
            if (app.getServices().contains(db.getDbName()) && !db.isUpdateSchema()) {
                // service binding already exists
                continue;
            }

            if (db.isUpdateSchema()) {
            	try {
            		client.deleteService(db.getDbName());
            	} catch (Exception ex) {
            	}
            }

            String dbType = "NONE";
            DataModelConfiguration config = this.dataModelManager.getDataModel(db.getDataModelId());
            String url = config.readConnectionProperties().getProperty(DataServiceConstants.DB_URL_KEY, "");
            if (StringUtils.hasText(url)) {
                dbType = BaseDataModelSetup.getDBTypeFromURL(url);
            }
            boolean serviceToBind = false;
            try {
                CloudService service = client.getService(db.getDbName());
                if (dbType.equals(MYSQL_SERVICE_VENDOR)) {
                    Assert.state(MYSQL_SERVICE_VENDOR.equals(service.getVendor()),
                        "There is already a service provisioned with the name '" + db.getDbName() + "' but it is not a MySQL service.");
                } else if (dbType.equals(POSTGRES_SERVICE_VENDOR)) {
                    Assert.state(POSTGRES_SERVICE_VENDOR.equals(service.getVendor()),
                        "There is already a service provisioned with the name '" + db.getDbName() + "' but it is not a PostgreSQL service.");
                }
                serviceToBind = true;
            } catch (CloudFoundryException ex) {
                if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                    throw ex;
                }

                if (dbType.equals(MYSQL_SERVICE_VENDOR)) {
                    client.createService(createMySqlService(db));
                    serviceToBind = true;
                } else if (dbType.equals(POSTGRES_SERVICE_VENDOR)) {
                    client.createService(createPostgresqlService(db));
                    serviceToBind = true;
                }
            }
            if (serviceToBind) {
                client.bindService(deploymentInfo.getApplicationName(), db.getDbName());
            }
        }
    }

    /**
     * @param db
     * @return
     */
    private CloudService createMySqlService(DeploymentDB db) {
        return createMySqlService(db.getDbName());
    }

    /**
     * @param dbName
     * @return
     */
    public static CloudService createMySqlService(String dbName) {
        CloudService mysql = new CloudService();
        mysql.setType(SERVICE_TYPE);
        mysql.setVendor(MYSQL_SERVICE_VENDOR);
        mysql.setTier(SERVICE_TIER);
        mysql.setVersion(MYSQL_SERVICE_VERSION);
        mysql.setName(dbName);
        return mysql;
    }

    /**
     * @param db
     * @return
     */
    public static CloudService createPostgresqlService(DeploymentDB db) {
        return createPostgresqlService(db.getDbName());
    }

    /**
     * @param dbName
     * @return
     */
    public static CloudService createPostgresqlService(String dbName) {
        CloudService postgresql = new CloudService();
        postgresql.setType(SERVICE_TYPE);
        postgresql.setVendor(POSTGRES_SERVICE_VENDOR);
        postgresql.setTier(SERVICE_TIER);
        postgresql.setVersion(POSTGRES_SERVICE_VERSION);
        postgresql.setName(dbName);
        return postgresql;
    }

    @Override
    public void undeploy(DeploymentInfo deploymentInfo, boolean deleteServices) throws DeploymentStatusException {
        CloudJeeClient client = getClient(deploymentInfo);
        log.info("Deleting application " + deploymentInfo.getApplicationName());
        Timer timer = new Timer();
        timer.start();
        try {
            client.undeploy(deploymentInfo.getApplicationName());
        } catch (Exception e) {
            throw new WMRuntimeException(e);
        }
        /*try {
           if (deleteServices) {
                CloudApplication app = client.getApplication(deploymentInfo.getApplicationName());
                for (String service : app.getServices()) {
                    client.deleteService(service);
                }
            }
            client.deleteApplication(deploymentInfo.getApplicationName());
            log.info("Application " + deploymentInfo.getApplicationName() + " deleted successfully in " + timer.stop() + "ms");
        } catch (CloudFoundryException ex) {
            if (HttpStatus.FORBIDDEN == ex.getStatusCode()) {
                throw new DeploymentStatusException(TOKEN_EXPIRED_RESULT, ex);
            } else {
                throw ex;
            }
        }*/
    }

    private void doRestart(DeploymentInfo deploymentInfo, CloudFoundryClient client) {
        log.info("Restarting application " + deploymentInfo.getApplicationName() + " as " + deploymentInfo.getDeploymentUrl());
        Timer timer = new Timer();
        timer.start();
        CloudFoundryUtils.restartApplicationAndWaitUntilRunning(client, deploymentInfo.getApplicationName());
        log.info("Application " + deploymentInfo.getApplicationName() + " restarted successfully in " + timer.stop() + "ms");
    }

    private void doStart(DeploymentInfo deploymentInfo, CloudFoundryClient client) {
        log.info("Starting application " + deploymentInfo.getApplicationName() + " as " + deploymentInfo.getDeploymentUrl());
        Timer timer = new Timer();
        timer.start();
        CloudFoundryUtils.startApplicationAndWaitUntilRunning(client, deploymentInfo.getApplicationName());
        log.info("Application " + deploymentInfo.getApplicationName() + " started successfully in " + timer.stop() + "ms");
    }

    private CloudJeeClient getClient(DeploymentInfo deploymentInfo) {
        Assert.hasText(deploymentInfo.getToken(), "CloudJee login token not supplied.");
       /* String url = deploymentInfo.getTarget();
        if (!StringUtils.hasText(url)) {
            url = DEFAULT_URL;
        }*/
        try {
            CloudJeeClient client = new CloudJeeClient(deploymentInfo.getToken());
            return client;
        } catch (Exception e) {
            throw new WMRuntimeException("CloudjeeToken is invalid", e);
        }
    }

    private void validateWar(File war) {
        Assert.notNull(war, "war cannot be null");
        Assert.isTrue(war.exists(), "war does not exist");
        Assert.isTrue(!war.isDirectory(), "war cannot be a directory");
    }
}
