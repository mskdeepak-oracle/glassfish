/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2010 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
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

package com.sun.gjc.spi;

import com.sun.enterprise.util.i18n.StringManager;
import com.sun.gjc.common.DataSourceObjectBuilder;
import com.sun.gjc.common.DataSourceSpec;
import com.sun.gjc.util.SecurityUtils;
import com.sun.logging.LogDomains;

import javax.resource.ResourceException;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.ResourceAllocationException;
import javax.resource.spi.security.PasswordCredential;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.resource.spi.ConnectionDefinition;


/**
 * Connection Pool <code>ManagedConnectionFactory</code> implementation for Generic JDBC Connector.
 *
 * @author Evani Sai Surya Kiran
 * @version 1.0, 02/07/30
 */
@ConnectionDefinition(
    connectionFactory = javax.sql.DataSource.class,
    connectionFactoryImpl = com.sun.gjc.spi.base.DataSource.class,
    connection = java.sql.Connection.class,
    connectionImpl = com.sun.gjc.spi.base.ConnectionHolder.class
)
public class CPManagedConnectionFactory extends ManagedConnectionFactory {

    private transient javax.sql.ConnectionPoolDataSource cpDataSourceObj;

    private static Logger _logger;

    static {
        _logger = LogDomains.getLogger(CPManagedConnectionFactory.class, LogDomains.RSR_LOGGER);
    }

    /**
     * Returns the underlying datasource
     *
     * @return DataSource of jdbc vendor
     * @throws ResourceException
     */
    public javax.sql.ConnectionPoolDataSource getDataSource() throws ResourceException {
        if (cpDataSourceObj == null) {
            try {
                cpDataSourceObj = (javax.sql.ConnectionPoolDataSource) super.getDataSource();
            } catch (ClassCastException cce) {
                _logger.log(Level.SEVERE, "jdbc.exc_cce_CP", cce);
                throw new ResourceException(cce.getMessage());
            }
        }
        return cpDataSourceObj;
    }

    /**
     * Creates a new physical connection to the underlying EIS resource
     * manager.
     *
     * @param subject       <code>Subject</code> instance passed by the application server
     * @param cxRequestInfo <code>ConnectionRequestInfo</code> which may be created
     *                      as a result of the invocation <code>getConnection(user, password)</code>
     *                      on the <code>DataSource</code> object
     * @return <code>ManagedConnection</code> object created
     * @throws ResourceException           if there is an error in instantiating the
     *                                     <code>DataSource</code> object used for the
     *                                     creation of the <code>ManagedConnection</code> object
     * @throws SecurityException           if there ino <code>PasswordCredential</code> object
     *                                     satisfying this request
     * @throws ResourceAllocationException if there is an error in allocating the
     *                                     physical connection
     */
    public javax.resource.spi.ManagedConnection createManagedConnection(javax.security.auth.Subject subject,
                                                                        ConnectionRequestInfo cxRequestInfo) throws ResourceException {
        logFine("In createManagedConnection");
        PasswordCredential pc = SecurityUtils.getPasswordCredential(this, subject, cxRequestInfo);

        javax.sql.ConnectionPoolDataSource dataSource = getDataSource();

        javax.sql.PooledConnection cpConn = null;

        try {
            /* For the case where the user/passwd of the connection pool is
            * equal to the PasswordCredential for the connection request
            * get a connection from this pool directly.
            * for all other conditions go create a new connection
            */
            if (isEqual(pc, getUser(), getPassword())) {
                cpConn = dataSource.getPooledConnection();
            } else {
                cpConn = dataSource.getPooledConnection(pc.getUserName(),
                        new String(pc.getPassword()));
            }

        } catch (java.sql.SQLException sqle) {
            //_logger.log(Level.SEVERE, "jdbc.exc_create_ds_conn",sqle);
            _logger.log(Level.FINE, "jdbc.exc_create_ds_conn", sqle);
            StringManager sm =
                    StringManager.getManager(DataSourceObjectBuilder.class);
            String msg = sm.getString("jdbc.cannot_allocate_connection", sqle.getMessage());
            ResourceAllocationException rae = new ResourceAllocationException(
                    msg, sqle);
            throw rae;
        }


        com.sun.gjc.spi.ManagedConnection mc = constructManagedConnection(
                cpConn, null, pc, this);

        mc.initializeConnectionType(ManagedConnection.ISPOOLEDCONNECTION);

        //GJCINT
        validateAndSetIsolation(mc);
        return mc;
    }

    /**
     * Check if this <code>ManagedConnectionFactory</code> is equal to
     * another <code>ManagedConnectionFactory</code>.
     *
     * @param other <code>ManagedConnectionFactory</code> object for checking equality with
     * @return true    if the property sets of both the
     *         <code>ManagedConnectionFactory</code> objects are the same
     *         false	otherwise
     */
    public boolean equals(Object other) {
        logFine("In equals");
        /**
         * The check below means that two ManagedConnectionFactory objects are equal
         * if and only if their properties are the same.
         */
        if (other instanceof com.sun.gjc.spi.CPManagedConnectionFactory) {
            com.sun.gjc.spi.CPManagedConnectionFactory otherMCF =
                    (com.sun.gjc.spi.CPManagedConnectionFactory) other;
            return this.spec.equals(otherMCF.spec);
        }
        return false;
    }


    /**
     * Sets the max statements.
     *
     * @param maxStmts <code>String</code>
     * @see <code>getMaxStatements</code>
     */
    public void setMaxStatements(String maxStmts) {
        spec.setDetail(DataSourceSpec.MAXSTATEMENTS, maxStmts);
    }

    /**
     * Gets the max statements.
     *
     * @return maxStmts
     * @see <code>setMaxStatements</code>
     */
    public String getMaxStatements() {
        return spec.getDetail(DataSourceSpec.MAXSTATEMENTS);
    }

    /**
     * Sets the initial pool size.
     *
     * @param initPoolSz <code>String</code>
     * @see <code>getInitialPoolSize</code>
     */
    public void setInitialPoolSize(String initPoolSz) {
        spec.setDetail(DataSourceSpec.INITIALPOOLSIZE, initPoolSz);
    }

    /**
     * Gets the initial pool size.
     *
     * @return initPoolSz
     * @see <code>setInitialPoolSize</code>
     */
    public String getInitialPoolSize() {
        return spec.getDetail(DataSourceSpec.INITIALPOOLSIZE);
    }

    /**
     * Sets the minimum pool size.
     *
     * @param minPoolSz <code>String</code>
     * @see <code>getMinPoolSize</code>
     */
    public void setMinPoolSize(String minPoolSz) {
        spec.setDetail(DataSourceSpec.MINPOOLSIZE, minPoolSz);
    }

    /**
     * Gets the minimum pool size.
     *
     * @return minPoolSz
     * @see <code>setMinPoolSize</code>
     */
    public String getMinPoolSize() {
        return spec.getDetail(DataSourceSpec.MINPOOLSIZE);
    }

    /**
     * Sets the maximum pool size.
     *
     * @param maxPoolSz <code>String</code>
     * @see <code>getMaxPoolSize</code>
     */
    public void setMaxPoolSize(String maxPoolSz) {
        spec.setDetail(DataSourceSpec.MAXPOOLSIZE, maxPoolSz);
    }

    /**
     * Gets the maximum pool size.
     *
     * @return maxPoolSz
     * @see <code>setMaxPoolSize</code>
     */
    public String getMaxPoolSize() {
        return spec.getDetail(DataSourceSpec.MAXPOOLSIZE);
    }

    /**
     * Sets the maximum idle time.
     *
     * @param maxIdleTime String
     * @see <code>getMaxIdleTime</code>
     */
    public void setMaxIdleTime(String maxIdleTime) {
        spec.setDetail(DataSourceSpec.MAXIDLETIME, maxIdleTime);
    }

    /**
     * Gets the maximum idle time.
     *
     * @return maxIdleTime
     * @see <code>setMaxIdleTime</code>
     */
    public String getMaxIdleTime() {
        return spec.getDetail(DataSourceSpec.MAXIDLETIME);
    }

    /**
     * Sets the property cycle.
     *
     * @param propCycle <code>String</code>
     * @see <code>getPropertyCycle</code>
     */
    public void setPropertyCycle(String propCycle) {
        spec.setDetail(DataSourceSpec.PROPERTYCYCLE, propCycle);
    }

    /**
     * Gets the property cycle.
     *
     * @return propCycle
     * @see <code>setPropertyCycle</code>
     */
    public String getPropertyCycle() {
        return spec.getDetail(DataSourceSpec.PROPERTYCYCLE);
    }
}    
