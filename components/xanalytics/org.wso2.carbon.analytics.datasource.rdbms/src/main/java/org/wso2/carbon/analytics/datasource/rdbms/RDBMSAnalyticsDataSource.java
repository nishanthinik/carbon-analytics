/*
 *  Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.analytics.datasource.rdbms;

import java.io.ByteArrayInputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.wso2.carbon.analytics.datasource.core.AnalyticsDataSource;
import org.wso2.carbon.analytics.datasource.core.AnalyticsDataSourceException;
import org.wso2.carbon.analytics.datasource.core.AnalyticsLockException;
import org.wso2.carbon.analytics.datasource.core.DirectAnalyticsDataSource;
import org.wso2.carbon.analytics.datasource.core.Record;
import org.wso2.carbon.analytics.datasource.core.Record.Column;
import org.wso2.carbon.analytics.datasource.core.fs.FileSystem;
import org.wso2.carbon.analytics.datasource.core.lock.LockProvider;
import org.wso2.carbon.analytics.datasource.core.util.GenericUtils;

/**
 * Abstract RDBMS database backed implementation of {@link AnalyticsDataSource}.
 */
public class RDBMSAnalyticsDataSource extends DirectAnalyticsDataSource {

    private static final String ANALYTICS_USER_TABLE_PREFIX = "ANX";

    private static final String RECORD_IDS_PLACEHOLDER = "{{RECORD_IDS}}";

    private static final String TABLE_NAME_PLACEHOLDER = "{{TABLE_NAME}}";

    private DataSource dataSource;
    
    private Map<String, String> properties;
    
    private QueryConfiguration queryConfiguration;
    
    public RDBMSAnalyticsDataSource() {
        this(null);
    }
    
    public RDBMSAnalyticsDataSource(QueryConfiguration queryConfiguration) {
        this.queryConfiguration = queryConfiguration;
    }
    
    @Override
    public void init(Map<String, String> properties)
            throws AnalyticsDataSourceException {
        this.properties = properties;
        String dsName = properties.get(RDBMSAnalyticsDSConstants.DATASOURCE);
        if (dsName == null) {
            throw new AnalyticsDataSourceException("The property '" + 
                    RDBMSAnalyticsDSConstants.DATASOURCE + "' is required");
        }
        try {
            this.dataSource = (DataSource) InitialContext.doLookup(dsName);
        } catch (NamingException e) {
            throw new AnalyticsDataSourceException("Error in looking up data source: " + 
                    e.getMessage(), e);
        }
        /* create the system tables */
        this.checkAndCreateSystemTables();
    }
    
    public QueryConfiguration getQueryConfiguration() {
        return queryConfiguration;
    }

    private void checkAndCreateSystemTables() throws AnalyticsDataSourceException {
        Connection conn = null;
        try {
            conn = this.getConnection(false);
            Statement stmt;
            if (!this.checkSystemTables(conn)) {
            	for (String query : this.getFsTableInitSQLQueries()) {
            		stmt = conn.createStatement();
            		stmt.executeUpdate(query);
            		stmt.close();
            	}
            }
            conn.commit();
        } catch (SQLException e) {
        	RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in creating system tables: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }
    
    private boolean checkSystemTables(Connection conn) {
    	Statement stmt = null;
    	try {
    		stmt = conn.createStatement();
    		stmt.execute(this.getSystemTableCheckQuery());
    		return true;
    	} catch (SQLException ignore) {
    		RDBMSUtils.cleanupConnection(null, stmt, null);
    		return false;
    	}
    }
    
    private String[] getFsTableInitSQLQueries() {
    	return this.getQueryConfiguration().getFsTableInitQueries();
    }
    
    private String getSystemTableCheckQuery() {
    	return this.getQueryConfiguration().getFsTablesCheckQuery();
    }
    
    private String[] getRecordTableInitQueries(long tableCategoryId, String tableName) {
        String[] queries = this.getQueryConfiguration().getRecordTableInitQueries();
        String[] result = new String[queries.length];
        for (int i = 0; i < queries.length; i++) {
            result[i] = this.translateQueryWithTableInfo(queries[i], tableCategoryId, tableName);
        }
        return result;
    }
    
    private String[] getRecordTableDeleteQueries(long tableCategoryId, String tableName) {
        String[] queries = this.getQueryConfiguration().getRecordTableDeleteQueries();
        String[] result = new String[queries.length];
        for (int i = 0; i < queries.length; i++) {
            result[i] = this.translateQueryWithTableInfo(queries[i], tableCategoryId, tableName);
        }
        return result;
    }
    
    public Map<String, String> getProperties() {
        return properties;
    }
    
    public DataSource getDataSource() {
        return dataSource;
    }
    
    private Connection getConnection() throws SQLException {
        return this.getConnection(true);
    }
    
    private Connection getConnection(boolean autoCommit) throws SQLException {
        Connection conn = this.getDataSource().getConnection();
        conn.setAutoCommit(autoCommit);
        return conn;
    }
    
    @Override
    public void put(List<Record> records) throws AnalyticsDataSourceException {
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = this.getConnection(false);
            stmt = conn.prepareStatement(this.getRecordInsertSQL());
            for (Record record : records) {
                stmt.setString(1, record.getId());
                stmt.setLong(4, record.getTimestamp());
                stmt.setBlob(5, new ByteArrayInputStream(GenericUtils.encodeRecordValues(record.getValues())));
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in adding records: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, conn);
        }
    }
    
    private String getRecordInsertSQL() {
    	return this.getQueryConfiguration().getRecordInsertQuery();
    }

    @Override
    public List<Record> getRecords(long tableCategoryId, String tableName, List<String> columns,
            long timeFrom, long timeTo, int recordsFrom, 
            int recordsCount) throws AnalyticsDataSourceException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getConnection(false);
            stmt = conn.prepareStatement(this.getRecordRetrievalQuery(tableCategoryId, tableName));
            if (timeFrom == -1) {
                timeFrom = Long.MIN_VALUE;
            }
            if (timeTo == -1) {
                timeTo = Long.MAX_VALUE;
            }
            if (recordsFrom == -1) {
                recordsFrom = 0;
            }
            if (recordsCount == -1) {
                recordsCount = Integer.MAX_VALUE;
            }
            stmt.setLong(3, timeFrom);
            stmt.setLong(4, timeTo);
            stmt.setInt(6, this.adjustRecordsFromForProvider(recordsFrom));
            stmt.setInt(7, this.adjustRecordsCountForProvider(recordsFrom, recordsCount));            
            rs = stmt.executeQuery();
            List<Record> result = this.processRecordResultSet(tableCategoryId, tableName, rs, columns);
            conn.commit();
            return result;
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in retrieving records: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(rs, stmt, conn);
        }
    }
    
    private int adjustRecordsFromForProvider(int recordsFrom) {
        if (!this.getQueryConfiguration().isPaginationFirstZeroIndexed()) {
            recordsFrom++;
        }
        if (!this.getQueryConfiguration().isPaginationFirstInclusive()) {
            recordsFrom++;
        }
        return recordsFrom;
    }
    
    private int adjustRecordsCountForProvider(int recordsFrom, int recordsCount) {
        if (!this.getQueryConfiguration().isPaginationSecondLength() && recordsCount != Integer.MAX_VALUE) {
            if (!this.getQueryConfiguration().isPaginationSecondZeroIndexed()) {
                recordsCount++;
            }
            if (!this.getQueryConfiguration().isPaginationSecondInclusive()) {
                recordsCount++;
            }
        }
        return recordsCount;
    }
    
    private List<Record> processRecordResultSet(long tableCategoryId, String tableName, ResultSet rs, 
            List<String> columns) throws SQLException, AnalyticsDataSourceException {
        List<Record> result = new ArrayList<Record>();
        Record record;
        Blob blob;
        List<Column> values;
        Set<String> colSet = null;
        if (columns != null && columns.size() > 0) {
            colSet = new HashSet<String>(columns);
        }
        while (rs.next()) {
            blob = rs.getBlob(3);
            values = GenericUtils.decodeRecordValues(blob.getBytes(1, (int) blob.length()), colSet);
            record = new Record(rs.getString(1), tableCategoryId, tableName, values, rs.getLong(2));
            result.add(record);            
        }
        return result;
    }

    @Override
    public List<Record> getRecords(long tableCategoryId, String tableName, List<String> columns,
            List<String> ids) throws AnalyticsDataSourceException {
        String recordGetSQL = this.generateGetRecordRetrievalWithIdQuery(tableCategoryId, tableName, ids.size());
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getConnection(false);
            stmt = conn.prepareStatement(recordGetSQL);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 4, ids.get(i));
            }
            rs = stmt.executeQuery();
            List<Record> result = this.processRecordResultSet(tableCategoryId, tableName, rs, columns);
            conn.commit();
            return result;
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in retrieving records: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(rs, stmt, conn);
        }
    }
    
    @Override
    public void delete(long tableCategoryId, String tableName, long timeFrom, long timeTo)
            throws AnalyticsDataSourceException {
        String sql = this.getRecordDeletionQuery();
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = this.getConnection(false);
            stmt = conn.prepareStatement(sql);
            if (timeFrom == -1) {
                timeFrom = Long.MIN_VALUE;
            }
            if (timeTo == -1) {
                timeTo = Long.MAX_VALUE;
            }
            stmt.setLong(3, timeFrom);
            stmt.setLong(4, timeTo);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in deleting records: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, conn);
        }
    }
        
    @Override
    public void delete(long tableCatelogId, String tableName, List<String> ids) throws AnalyticsDataSourceException {
        if (ids.size() == 0) {
            return;
        }
        String sql = this.generateRecordDeletionRecordsWithIdsQuery(tableCatelogId, tableName, ids.size());
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = this.getConnection();
            stmt = conn.prepareStatement(sql);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 4, ids.get(i));
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new AnalyticsDataSourceException("Error in deleting records: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, conn);
        }
    }
    
    private String generateTablePrefix(long tableCategoryId) {
        return RDBMSAnalyticsDataSource.ANALYTICS_USER_TABLE_PREFIX + "_" + tableCategoryId + "_";
    }
    
    private String generateTargetTableName(long tableCategoryId, String tableName) {
        return this.generateTablePrefix(tableCategoryId) + tableName;
    }
    
    private String translateQueryWithTableInfo(String query, long tableCategoryId, String tableName) {
        return query.replace(TABLE_NAME_PLACEHOLDER, this.generateTargetTableName(tableCategoryId, tableName));
    }
    
    private String translateQueryWithRecordIdsInfo(String query, int recordCount) {
        return query.replace(RECORD_IDS_PLACEHOLDER, this.getDynamicSQLParams(recordCount));
    }
    
    private String getRecordRetrievalQuery(long tableCategoryId, String tableName) {
        String query = this.getQueryConfiguration().getRecordRetrievalQuery();
        return this.translateQueryWithTableInfo(query, tableCategoryId, tableName);
    }
    
    private String generateGetRecordRetrievalWithIdQuery(long tableCategoryId, String tableName, int recordCount) {
        String query = this.getQueryConfiguration().getRecordRetrievalWithIdsQuery();
        query = this.translateQueryWithTableInfo(query, tableCategoryId, tableName);
        query = this.translateQueryWithRecordIdsInfo(query, recordCount);
        return query;
    }
    
    private String generateRecordDeletionRecordsWithIdsQuery(long tableCategoryId, String tableName, int recordCount) {
        String query = this.getQueryConfiguration().getRecordDeletionWithIdsQuery();
        query = this.translateQueryWithTableInfo(query, tableCategoryId, tableName);
        query = this.translateQueryWithRecordIdsInfo(query, recordCount);
        return query;
    }
    
    private String getDynamicSQLParams(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i == 0) {
                builder.append("?");
            } else {
                builder.append(",?");
            }
        }
        return builder.toString();
    }
    
    private String getRecordDeletionQuery() {
        return this.getQueryConfiguration().getRecordDeletionQuery();
    }

    @Override
    public void deleteTable(long tableCategoryId, String tableName) throws AnalyticsDataSourceException {
        Connection conn = null;
        try {
            conn = this.getConnection(false);
            String[] tableInitQueries = this.getRecordTableDeleteQueries(tableCategoryId, tableName);
            for (String query : tableInitQueries) {
                query = this.translateQueryWithTableInfo(query, tableCategoryId, tableName);
                this.executeUpdate(conn, query);
            }
            conn.commit();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in deleting table: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }

    @Override
    public FileSystem getFileSystem() throws AnalyticsDataSourceException {
        return new RDBMSFileSystem(this.getQueryConfiguration(), this.getDataSource());
    }

    @Override
    public LockProvider getLockProvider() throws AnalyticsLockException {
        return null;
    }
    
    @Override
    public void createTable(long tableCategoryId, String tableName) throws AnalyticsDataSourceException {
        Connection conn = null;
        try {
            conn = this.getConnection(false);
            String[] tableInitQueries = this.getRecordTableInitQueries(tableCategoryId, tableName);
            for (String query : tableInitQueries) {
                this.executeUpdate(conn, query);
            }
            conn.commit();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsDataSourceException("Error in creating table: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }
    
    private void executeUpdate(Connection conn, String query) throws SQLException {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            stmt.executeUpdate(query);
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, null);
        }
    }
    
    @Override
    public boolean tableExists(long tableCategoryId, String tableName) throws AnalyticsDataSourceException {
        tableName = this.normalizeTableName(tableName);
        Connection conn = null;
        ResultSet rs = null;
        try {
            conn = this.getConnection();
            DatabaseMetaData dbm = conn.getMetaData();
            String prefix = this.generateTablePrefix(tableCategoryId);
            String srcTable;
            rs = dbm.getTables(null, null, "%", null);
            while (rs.next()) {
                srcTable = rs.getString("TABLE_NAME");
                if (srcTable.startsWith(ANALYTICS_USER_TABLE_PREFIX)) {
                    srcTable = srcTable.substring(prefix.length());
                    srcTable = this.normalizeTableName(srcTable);
                }
                if (tableName.equals(srcTable)) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new AnalyticsDataSourceException("Error in checking table existence: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(rs, null, conn);
        }
    }

    private String normalizeTableName(String tableName) {
        return tableName.toUpperCase();
    }
    
    @Override
    public List<String> listTables(long tableCategoryId) throws AnalyticsDataSourceException {
        List<String> result = new ArrayList<String>();
        Connection conn = null;
        ResultSet rs = null;
        String tableName;
        String prefix = this.generateTablePrefix(tableCategoryId);
        try {
            conn = this.getConnection();
            DatabaseMetaData dbm = conn.getMetaData();
            rs = dbm.getTables(null, null, "%", null);
            while (rs.next()) {
                tableName = rs.getString("TABLE_NAME");
                if (tableName.startsWith(ANALYTICS_USER_TABLE_PREFIX)) {
                    tableName = tableName.substring(prefix.length());
                    tableName = this.normalizeTableName(tableName);
                    result.add(tableName);
                }
            }
            return result;
        } catch (SQLException e) {
            throw new AnalyticsDataSourceException("Error in listing tables: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(rs, null, conn);
        }
    }

}
