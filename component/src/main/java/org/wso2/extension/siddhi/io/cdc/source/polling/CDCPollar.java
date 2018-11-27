/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.extension.siddhi.io.cdc.source.polling;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.log4j.Logger;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.wso2.carbon.datasource.core.api.DataSourceService;
import org.wso2.carbon.datasource.core.exception.DataSourceException;
import org.wso2.extension.siddhi.io.cdc.source.CDCSource;
import org.wso2.extension.siddhi.io.cdc.source.config.QueryConfiguration;
import org.wso2.extension.siddhi.io.cdc.source.config.QueryConfigurationEntry;
import org.wso2.extension.siddhi.io.cdc.util.CDCPollingUtil;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.exception.SiddhiAppRuntimeException;
import org.wso2.siddhi.core.stream.input.source.SourceEventListener;
import org.wso2.siddhi.core.util.config.ConfigReader;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

/**
 * Polls a given table for changes. Use {@code pollingColumn} to poll on.
 */
public class CDCPollar implements Runnable {

    private static final Logger log = Logger.getLogger(CDCPollar.class);
    private static final String CONNECTION_PROPERTY_JDBC_URL = "jdbcUrl";
    private static final String CONNECTION_PROPERTY_DATASOURCE_USER = "dataSource.user";
    private static final String CONNECTION_PROPERTY_DATASOURCE_PASSWORD = "dataSource.password";
    private static final String CONNECTION_PROPERTY_DRIVER_CLASSNAME = "driverClassName";
    private static final String PLACE_HOLDER_TABLE_NAME = "{{TABLE_NAME}}";
    private static final String PLACE_HOLDER_FIELD_LIST = "{{FIELD_LIST}}";
    private static final String PLACE_HOLDER_CONDITION = "{{CONDITION}}";
    private static final String SELECT_QUERY_CONFIG_FILE = "query-config.xml";
    private static final String RECORD_SELECT_QUERY = "recordSelectQuery";
    private String selectQueryStructure = "";
    private String url;
    private String tableName;
    private String username;
    private String password;
    private String driverClassName;
    private HikariDataSource dataSource;
    private String lastOffset;
    private SourceEventListener sourceEventListener;
    private String pollingColumn;
    private String datasourceName;
    private int pollingInterval;
    private boolean usingDatasourceName;
    private CompletionCallback completionCallback;
    private boolean paused = false;
    private ReentrantLock lock = new ReentrantLock();
    private Condition condition = lock.newCondition();
    private ConfigReader configReader;

    public CDCPollar(String url, String username, String password, String tableName, String driverClassName,
                     String lastOffset, String pollingColumn, int pollingInterval,
                     SourceEventListener sourceEventListener, ConfigReader configReader) {
        this.url = url;
        this.tableName = tableName;
        this.username = username;
        this.password = password;
        this.driverClassName = driverClassName;
        this.lastOffset = lastOffset;
        this.sourceEventListener = sourceEventListener;
        this.pollingColumn = pollingColumn;
        this.pollingInterval = pollingInterval;
        this.usingDatasourceName = false;
        this.configReader = configReader;
    }

    public CDCPollar(String datasourceName, String tableName, String lastOffset, String pollingColumn,
                     int pollingInterval, SourceEventListener sourceEventListener, ConfigReader configReader) {
        this.datasourceName = datasourceName;
        this.tableName = tableName;
        this.lastOffset = lastOffset;
        this.sourceEventListener = sourceEventListener;
        this.pollingColumn = pollingColumn;
        this.pollingInterval = pollingInterval;
        this.usingDatasourceName = true;
        this.configReader = configReader;
    }

    public void setCompletionCallback(CompletionCallback completionCallback) {
        this.completionCallback = completionCallback;
    }

    private void initializeDatasource() {
        if (!usingDatasourceName) {
            Properties connectionProperties = new Properties();

            connectionProperties.setProperty(CONNECTION_PROPERTY_JDBC_URL, url);
            connectionProperties.setProperty(CONNECTION_PROPERTY_DATASOURCE_USER, username);
            if (!CDCPollingUtil.isEmpty(password)) {
                connectionProperties.setProperty(CONNECTION_PROPERTY_DATASOURCE_PASSWORD, password);
            }
            connectionProperties.setProperty(CONNECTION_PROPERTY_DRIVER_CLASSNAME, driverClassName);

            HikariConfig config = new HikariConfig(connectionProperties);
            this.dataSource = new HikariDataSource(config);
        } else {
            try {
                BundleContext bundleContext = FrameworkUtil.getBundle(DataSourceService.class).getBundleContext();
                ServiceReference serviceRef = bundleContext.getServiceReference(DataSourceService.class.getName());
                if (serviceRef == null) {
                    throw new SiddhiAppCreationException("DatasourceService : '" +
                            DataSourceService.class.getCanonicalName() + "' cannot be found.");
                } else {
                    DataSourceService dataSourceService = (DataSourceService) bundleContext.getService(serviceRef);
                    this.dataSource = (HikariDataSource) dataSourceService.getDataSource(datasourceName);

                    if (log.isDebugEnabled()) {
                        log.debug("Lookup for datasource '" + datasourceName + "' completed through " +
                                "DataSource Service lookup.");
                    }
                }
            } catch (DataSourceException e) {
                throw new SiddhiAppCreationException("Datasource '" + datasourceName + "' cannot be connected.", e);
            }
        }
    }

    public String getLastOffset() {
        return lastOffset;
    }

    private Connection getConnection() {
        Connection conn;
        try {
            conn = this.dataSource.getConnection();
        } catch (SQLException e) {
            throw new SiddhiAppRuntimeException("Error initializing datasource connection: "
                    + e.getMessage(), e);
        }
        return conn;
    }

    private String getSelectQuery(String fieldList, String condition, ConfigReader configReader) {
        String selectQuery;

        if (selectQueryStructure.isEmpty()) {
            //Get the database product name
            String databaseName;
            Connection conn = null;
            try {
                conn = getConnection();
                DatabaseMetaData dmd = conn.getMetaData();
                databaseName = dmd.getDatabaseProductName();
            } catch (SQLException e) {
                throw new SiddhiAppRuntimeException("Error in looking up database type: " + e.getMessage(), e);
            } finally {
                CDCPollingUtil.cleanupConnection(null, null, conn);
            }

            //Read configs from config reader.
            selectQueryStructure = configReader.readConfig(databaseName + "." + RECORD_SELECT_QUERY, "");

            if (selectQueryStructure.isEmpty()) {
                //Read configs from file
                QueryConfiguration queryConfiguration = null;
                InputStream inputStream = null;
                try {
                    JAXBContext ctx = JAXBContext.newInstance(QueryConfiguration.class);
                    Unmarshaller unmarshaller = ctx.createUnmarshaller();
                    ClassLoader classLoader = getClass().getClassLoader();
                    inputStream = classLoader.getResourceAsStream(SELECT_QUERY_CONFIG_FILE);
                    if (inputStream == null) {
                        throw new SiddhiAppRuntimeException(SELECT_QUERY_CONFIG_FILE
                                + " is not found in the classpath");
                    }
                    queryConfiguration = (QueryConfiguration) unmarshaller.unmarshal(inputStream);

                } catch (JAXBException e) {
                    log.error("Query Configuration read error", e);
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException e) {
                            log.error("Failed to close the input stream for " + SELECT_QUERY_CONFIG_FILE);
                        }
                    }
                }

                //Get database related select query structure
                for (QueryConfigurationEntry entry : queryConfiguration.getDatabases()) {
                    if (entry.getDatabaseName().equalsIgnoreCase(databaseName)) {
                        selectQueryStructure = entry.getRecordSelectQuery();
                        break;
                    }
                }
            }

            if (selectQueryStructure.isEmpty()) {
                throw new SiddhiAppRuntimeException("Unsupported database: " + databaseName + ". Configure system" +
                        " parameter: " + databaseName + "." + RECORD_SELECT_QUERY);
            }
        }

        //create the select query with given constraints
        selectQuery = selectQueryStructure.replace(PLACE_HOLDER_TABLE_NAME, tableName)
                .replace(PLACE_HOLDER_FIELD_LIST, fieldList)
                .replace(PLACE_HOLDER_CONDITION, condition);

        return selectQuery;
    }

    /**
     * Poll for inserts and updates.
     */
    private void pollForChanges() {
        initializeDatasource();

        String selectQuery;
        ResultSetMetaData metadata;
        Map<String, Object> detailsMap;
        Connection connection = getConnection();
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try {
            synchronized (new Object()) { //assign null lastOffset atomically.
                //If lastOffset is null, assign it with last record of the table.
                if (lastOffset == null) {
                    selectQuery = getSelectQuery(pollingColumn, "", configReader).trim();
                    statement = connection.prepareStatement(selectQuery);
                    resultSet = statement.executeQuery();
                    while (resultSet.next()) {
                        lastOffset = resultSet.getString(pollingColumn);
                    }
                    //if the table is empty, set last offset to a negative value.
                    if (lastOffset == null) {
                        lastOffset = "-1";
                    }
                }
            }

            selectQuery = getSelectQuery("*", "WHERE " + pollingColumn + " > ?", configReader);
            statement = connection.prepareStatement(selectQuery);

            while (true) {
                statement.setString(1, lastOffset);
                resultSet = statement.executeQuery();
                metadata = resultSet.getMetaData();
                while (resultSet.next()) {
                    detailsMap = new HashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String key = metadata.getColumnName(i);
                        String value = resultSet.getString(key);
                        detailsMap.put(key, value);
                    }
                    lastOffset = resultSet.getString(pollingColumn);
                    handleEvent(detailsMap);
                }

                try {
                    Thread.sleep(pollingInterval * 1000);
                } catch (InterruptedException e) {
                    log.error("Error while polling.", e);
                }
            }
        } catch (SQLException ex) {
            throw new SiddhiAppRuntimeException("Error in polling for changes on " + tableName, ex);
        } finally {
            CDCPollingUtil.cleanupConnection(resultSet, statement, connection);
        }
    }

    private void handleEvent(Map detailsMap) {
        if (paused) {
            lock.lock();
            try {
                while (paused) {
                    condition.await();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
        sourceEventListener.onEvent(detailsMap, null);
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
        try {
            lock.lock();
            condition.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void run() {
        try {
            pollForChanges();
        } catch (SiddhiAppRuntimeException e) {
            completionCallback.handle(e);
        }
    }

    /**
     * A callback function to be notified when {@code CDCPollar} throws an Error.
     */
    public interface CompletionCallback {
        /**
         * Handle errors from {@link CDCPollar}.
         *
         * @param error the error.
         */
        void handle(Throwable error);
    }
}
