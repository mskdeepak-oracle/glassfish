/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.webservices.metroglue;

import java.io.File;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.MessageFormat;

import com.sun.enterprise.config.serverbeans.AvailabilityService;
import com.sun.enterprise.config.serverbeans.ServerTags;
import com.sun.enterprise.deployment.WebServiceEndpoint;
import com.sun.enterprise.util.io.FileUtils;
import com.sun.logging.LogDomains;
import com.sun.xml.ws.api.ha.HighAvailabilityProvider;

import org.glassfish.api.ActionReport;
import org.glassfish.api.container.Container;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.Deployer;
import org.glassfish.api.deployment.OpsParams;
import org.glassfish.deployment.common.DeploymentProperties;
import org.glassfish.gms.bootstrap.GMSAdapterService;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import org.glassfish.server.ServerEnvironmentImpl;
import org.glassfish.webservices.WebServiceDeploymentListener;
import org.glassfish.webservices.WebServicesDeployer;

import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PostConstruct;
import org.jvnet.hk2.component.Singleton;

/**
 * @author Marek Potociar
 */
@Service(name = "org.glassfish.webservices.metroglue.MetroContainer")
@Scoped(Singleton.class)
public class MetroContainer implements PostConstruct, Container, WebServiceDeploymentListener {

    private static final Logger logger = LogDomains.getLogger(MetroContainer.class, LogDomains.WEBSERVICES_LOGGER);
    private static final ResourceBundle rb = logger.getResourceBundle();
    //
    private static final String WSTX_SERVICES_APP_NAME = "wstx-services";
    private static final String METRO_APPS_INSTALL_ROOT = "lib/install/applications/metro";
    private static final Object lock = new Object();
    private final AtomicBoolean wstxServicesDeployed = new AtomicBoolean(false);
    private final AtomicBoolean wstxServicesDeploying = new AtomicBoolean(false);
    //
    @Inject
    private Habitat habitat;
    @Inject
    private ServerContext serverContext;
    @Inject
    private ServerEnvironmentImpl env;
    @Inject
    GMSAdapterService gmsAdapterService;
    @Inject(optional = true)
    private AvailabilityService availabilityService;

    @Override
    public void postConstruct() {
        WebServicesDeployer.getDeploymentNotifier().addListener(this);
        logger.info("endpoint.event.listener.registered");

        if (isCluster() && isHaEnabled()) {
            final String clusterName = gmsAdapterService.getGMSAdapter().getClusterName();
            final String instanceName = gmsAdapterService.getGMSAdapter().getModule().getInstanceName();

            HighAvailabilityProvider.INSTANCE.initHaEnvironment(clusterName, instanceName);
            logger.info("metro.ha.environemt.initialized");
        }
    }

    @Override
    public Class<? extends Deployer> getDeployer() {
        return MetroDeployer.class;
    }

    @Override
    public String getName() {
        return "metro";
    }

    @Override
    public void onDeployed(WebServiceEndpoint endpoint) {
        logger.finest("endpoint.event.deployed");
        if (!wstxServicesDeployed.get() && !wstxServicesDeploying.get()) {
            deployWsTxServices();
        }

    }

    @Override
    public void onUndeployed(WebServiceEndpoint endpoint) {
        logger.finest("endpoint.event.undeployed");
        // noop
    }

    public void deployWsTxServices() {
        deployWsTxServices(null);
    }

    public void deployWsTxServices(String target) {
        synchronized (lock) {
            if (wstxServicesDeployed.get() || !wstxServicesDeploying.compareAndSet(false, true)) {
                return;
            }

            Deployment deployment = habitat.getByContract(Deployment.class);
            boolean isRegistered = deployment.isRegistered(WSTX_SERVICES_APP_NAME);

            if (isRegistered) {
                logger.log(Level.WARNING, "wstx.service.deployed.explicitly");
            } else {
                logger.log(Level.INFO, "wstx.service.loading");

                File root = serverContext.getInstallRoot();
                File app = null;
                try {
                    app = FileUtils.getManagedFile(WSTX_SERVICES_APP_NAME + ".war", new File(root, METRO_APPS_INSTALL_ROOT));
                } catch (Exception e) {
                    logger.log(Level.WARNING, "wstx.service.unexpected.exception", e);
                }

                if (app == null || !app.exists()) {
                    logger.log(Level.WARNING, format("wstx.service.cannot.deploy", "Required WAR file (" + WSTX_SERVICES_APP_NAME + ".war) is not installed"));
                } else {
                    ActionReport report = habitat.getComponent(ActionReport.class, "plain");
                    DeployCommandParameters params = new DeployCommandParameters(app);
                    String appName = WSTX_SERVICES_APP_NAME;
                    params.name = appName;

                    try {
                        File rootScratchDir = env.getApplicationStubPath();
                        File appScratchDir = new File(rootScratchDir, appName);
                        //                      String resourceName = getTimerResource(target);
                        if (isDas() && appScratchDir.createNewFile()) {
                            params.origin = OpsParams.Origin.deploy;
                            if (target != null) {
                                params.target = target;
                            }
                        } else {
                            params.origin = OpsParams.Origin.load;
                            params.target = env.getInstanceName();
                        }

                        ExtendedDeploymentContext dc = deployment.getBuilder(logger, params, report).source(app).build();
                        Properties appProps = dc.getAppProps();
                        appProps.setProperty(ServerTags.OBJECT_TYPE, DeploymentProperties.SYSTEM_ALL);

                        deployment.deploy(dc);

                        if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS) {
                            logger.log(Level.WARNING, format("wstx.service.cannot.deploy", report.getMessage()), report.getFailureCause());
                        }

                        logger.log(Level.INFO, "wstx.service.started");

                    } catch (Exception ex) {
                        logger.log(Level.WARNING, format("wstx.service.cannot.deploy", ex.getLocalizedMessage()), ex);
                    }
                }
            }

            wstxServicesDeployed.set(true);
            wstxServicesDeploying.set(false);
        }
    }

    private String format(String key, String... values) {
        return MessageFormat.format(rb.getString(key), (Object[]) values);
    }

    /**
     * Embedded is a single-instance like DAS
     */
    private boolean isDas() {
        return env.isDas() || env.isEmbedded();
    }

    private boolean isCluster() {
        return !env.isDas() && !env.isEmbedded() && gmsAdapterService.isGmsEnabled();
    }

    private boolean isHaEnabled() {
        boolean haEnabled = false;
        if (availabilityService != null) {
            haEnabled = Boolean.valueOf(availabilityService.getAvailabilityEnabled());
        }

//        if (haEnabled) {
//            DeploymentContext dc = getDynamicDeploymentContext();
//            if (dc != null) {
//                DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);
//                if (params != null) {
//                    haEnabled = params.availabilityenabled;
//                }
//            }
//        }

        return haEnabled;
    }
}
