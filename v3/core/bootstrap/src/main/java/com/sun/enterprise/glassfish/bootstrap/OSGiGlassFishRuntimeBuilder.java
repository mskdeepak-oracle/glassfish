/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.glassfish.bootstrap;

import org.glassfish.embeddable.spi.RuntimeBuilder;
import org.glassfish.embeddable.*;
import org.osgi.framework.launch.Framework;

/**
 * This RuntimeBuilder can only handle GlassFish_Platform of following types:
 * {@link org.glassfish.embeddable.BootstrapProperties.Platform#Felix},
 * {@link org.glassfish.embeddable.BootstrapProperties.Platform#Equinox},
 * and {@link org.glassfish.embeddable.BootstrapProperties.Platform#Knopflerfish}.
 *
 * It can't handle {@link org.glassfish.embeddable.BootstrapProperties.Platform#GenericOSGi} platform,
 * because it reads framework configuration from a framework specific file when it calls
 * {@link ASMainHelper#buildStartupContext(java.util.Properties)}.
 *
 * This class is responsible for
 * a) setting up OSGi framework,
 * b) installing glassfish bundles,
 * c) starting the primordial GlassFish bundle (i.e., GlassFish Kernel bundle),
 * d) obtaining a reference to GlassFishRuntime OSGi service.
 * <p/>
 * Steps #b & #c are handled via {@link AutoProcessor}.
 * We specify our provisioning bundle details in the properties object that's used to boostrap
 * the system. AutoProcessor installs and starts such bundles, The provisioning bundle is also configured
 * via the same properties object.
 * <p/>
 * If caller does not pass in a properly populated properties object, we assume that we are
 * running against an existing installation of glassfish and set appropriate default values.
 * <p/>
 * <p/>
 * This class is registered as a provider of RuntimeBuilder using META-INF/services file.
 *
 * @author Sanjeeb.Sahoo@Sun.COM
 */
public final class OSGiGlassFishRuntimeBuilder implements RuntimeBuilder {
    private Framework framework;

    /**
     * Default constructor needed for meta-inf/service lookup to work
     */
    public OSGiGlassFishRuntimeBuilder() {}

    public GlassFishRuntime build(BootstrapProperties bsProps) throws GlassFishException {
        ASMainHelper.buildStartupContext(bsProps.getProperties());
        final OSGiFrameworkLauncher fwLauncher = new OSGiFrameworkLauncher(bsProps.getProperties());
        try {
            this.framework = fwLauncher.launchOSGiFrameWork();
            debug("Initialized " + framework);
            return fwLauncher.getService(GlassFishRuntime.class);
        } catch (Exception e) {
            throw new GlassFishException(e);
        }
    }

    public boolean handles(BootstrapProperties bsProps) {
        /*
         * This builder can't handle GOSGi platform, because we read framework configuration from a framework
         * specific file in ASMainHelper.buildStartupContext(properties);
         */
        BootstrapProperties.Platform platform = bsProps.getPlatform();
        if (platform == null) {
            return false;
        }
        switch (platform) {
            case Felix:
            case Equinox:
            case Knopflerfish:
                return true;
        }
        return false;
    }

    private static void debug(String s) {
        System.out.println("OSGiGlassFishRuntime: " + s);
    }

}
