// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.impala.catalog.KuduTable;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hive.service.rpc.thrift.TGetColumnsReq;
import org.apache.hive.service.rpc.thrift.TGetFunctionsReq;
import org.apache.hive.service.rpc.thrift.TGetSchemasReq;
import org.apache.hive.service.rpc.thrift.TGetTablesReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.impala.analysis.AnalysisContext;
import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.CreateDataSrcStmt;
import org.apache.impala.analysis.CreateDropRoleStmt;
import org.apache.impala.analysis.CreateUdaStmt;
import org.apache.impala.analysis.CreateUdfStmt;
import org.apache.impala.analysis.DropDataSrcStmt;
import org.apache.impala.analysis.DropFunctionStmt;
import org.apache.impala.analysis.DropStatsStmt;
import org.apache.impala.analysis.DropTableOrViewStmt;
import org.apache.impala.analysis.GrantRevokePrivStmt;
import org.apache.impala.analysis.GrantRevokeRoleStmt;
import org.apache.impala.analysis.InsertStmt;
import org.apache.impala.analysis.QueryStmt;
import org.apache.impala.analysis.ResetMetadataStmt;
import org.apache.impala.analysis.ShowFunctionsStmt;
import org.apache.impala.analysis.ShowGrantRoleStmt;
import org.apache.impala.analysis.ShowRolesStmt;
import org.apache.impala.analysis.TableName;
import org.apache.impala.analysis.TruncateStmt;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.authorization.AuthorizationChecker;
import org.apache.impala.authorization.AuthorizationConfig;
import org.apache.impala.authorization.ImpalaInternalAdminUser;
import org.apache.impala.authorization.PrivilegeRequest;
import org.apache.impala.authorization.PrivilegeRequestBuilder;
import org.apache.impala.authorization.User;
import org.apache.impala.catalog.AuthorizationException;
import org.apache.impala.catalog.Catalog;
import org.apache.impala.catalog.CatalogException;
import org.apache.impala.catalog.Column;
import org.apache.impala.catalog.DataSource;
import org.apache.impala.catalog.DataSourceTable;
import org.apache.impala.catalog.DatabaseNotFoundException;
import org.apache.impala.catalog.Db;
import org.apache.impala.catalog.Function;
import org.apache.impala.catalog.HBaseTable;
import org.apache.impala.catalog.HdfsTable;
import org.apache.impala.catalog.ImpaladCatalog;
import org.apache.impala.catalog.StructType;
import org.apache.impala.catalog.Table;
import org.apache.impala.catalog.TableId;
import org.apache.impala.catalog.Type;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.common.FileSystemUtil;
import org.apache.impala.common.ImpalaException;
import org.apache.impala.common.InternalException;
import org.apache.impala.common.NotImplementedException;
import org.apache.impala.common.RuntimeEnv;
import org.apache.impala.planner.PlanFragment;
import org.apache.impala.planner.Planner;
import org.apache.impala.planner.ScanNode;
import org.apache.impala.thrift.TCatalogOpRequest;
import org.apache.impala.thrift.TCatalogOpType;
import org.apache.impala.thrift.TCatalogServiceRequestHeader;
import org.apache.impala.thrift.TColumn;
import org.apache.impala.thrift.TColumnType;
import org.apache.impala.thrift.TColumnValue;
import org.apache.impala.thrift.TCreateDropRoleParams;
import org.apache.impala.thrift.TDdlExecRequest;
import org.apache.impala.thrift.TDdlType;
import org.apache.impala.thrift.TDescribeOutputStyle;
import org.apache.impala.thrift.TDescribeResult;
import org.apache.impala.thrift.TErrorCode;
import org.apache.impala.thrift.TExecRequest;
import org.apache.impala.thrift.TExplainLevel;
import org.apache.impala.thrift.TExplainResult;
import org.apache.impala.thrift.TFinalizeParams;
import org.apache.impala.thrift.TFunctionCategory;
import org.apache.impala.thrift.TGrantRevokePrivParams;
import org.apache.impala.thrift.TGrantRevokeRoleParams;
import org.apache.impala.thrift.TLineageGraph;
import org.apache.impala.thrift.TLoadDataReq;
import org.apache.impala.thrift.TLoadDataResp;
import org.apache.impala.thrift.TMetadataOpRequest;
import org.apache.impala.thrift.TPlanExecInfo;
import org.apache.impala.thrift.TPlanFragment;
import org.apache.impala.thrift.TPlanFragmentTree;
import org.apache.impala.thrift.TQueryCtx;
import org.apache.impala.thrift.TQueryExecRequest;
import org.apache.impala.thrift.TResetMetadataRequest;
import org.apache.impala.thrift.TResultRow;
import org.apache.impala.thrift.TResultSet;
import org.apache.impala.thrift.TResultSetMetadata;
import org.apache.impala.thrift.TShowFilesParams;
import org.apache.impala.thrift.TStatus;
import org.apache.impala.thrift.TStmtType;
import org.apache.impala.thrift.TTableName;
import org.apache.impala.thrift.TUpdateCatalogCacheRequest;
import org.apache.impala.thrift.TUpdateCatalogCacheResponse;
import org.apache.impala.thrift.TUpdateMembershipRequest;
import org.apache.impala.util.EventSequence;
import org.apache.impala.util.MembershipSnapshot;
import org.apache.impala.util.PatternMatcher;
import org.apache.impala.util.TResultRowBuilder;
import org.apache.impala.util.TSessionStateUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Frontend API for the impalad process.
 * This class allows the impala daemon to create TQueryExecRequest
 * in response to TClientRequests. Also handles management of the authorization
 * policy.
 */
public class Frontend {
  private final static Logger LOG = LoggerFactory.getLogger(Frontend.class);
  // Time to wait for missing tables to be loaded before timing out.
  private final long MISSING_TBL_LOAD_WAIT_TIMEOUT_MS = 2 * 60 * 1000;

  // Max time to wait for a catalog update notification.
  private final long MAX_CATALOG_UPDATE_WAIT_TIME_MS = 2 * 1000;

  //TODO: Make the reload interval configurable.
  private static final int AUTHORIZATION_POLICY_RELOAD_INTERVAL_SECS = 5 * 60;

  private ImpaladCatalog impaladCatalog_;
  private final AuthorizationConfig authzConfig_;
  private final AtomicReference<AuthorizationChecker> authzChecker_;
  private final ScheduledExecutorService policyReader_ =
      Executors.newScheduledThreadPool(1);

  public Frontend(AuthorizationConfig authorizationConfig) {
    this(authorizationConfig, new ImpaladCatalog());
  }

  /**
   * C'tor used by tests to pass in a custom ImpaladCatalog.
   */
  public Frontend(AuthorizationConfig authorizationConfig, ImpaladCatalog catalog) {
    authzConfig_ = authorizationConfig;
    impaladCatalog_ = catalog;
    authzChecker_ = new AtomicReference<AuthorizationChecker>(
        new AuthorizationChecker(authzConfig_, impaladCatalog_.getAuthPolicy()));
    // If authorization is enabled, reload the policy on a regular basis.
    if (authzConfig_.isEnabled() && authzConfig_.isFileBasedPolicy()) {
      // Stagger the reads across nodes
      Random randomGen = new Random(UUID.randomUUID().hashCode());
      int delay = AUTHORIZATION_POLICY_RELOAD_INTERVAL_SECS + randomGen.nextInt(60);

      policyReader_.scheduleAtFixedRate(
          new AuthorizationPolicyReader(authzConfig_),
          delay, AUTHORIZATION_POLICY_RELOAD_INTERVAL_SECS, TimeUnit.SECONDS);
    }
  }

  /**
   * Reads (and caches) an authorization policy from HDFS.
   */
  private class AuthorizationPolicyReader implements Runnable {
    private final AuthorizationConfig config_;

    public AuthorizationPolicyReader(AuthorizationConfig config) {
      config_ = config;
    }

    @Override
    public void run() {
      try {
        LOG.info("Reloading authorization policy file from: " + config_.getPolicyFile());
        authzChecker_.set(new AuthorizationChecker(config_,
            getCatalog().getAuthPolicy()));
      } catch (Exception e) {
        LOG.error("Error reloading policy file: ", e);
      }
    }
  }

  public ImpaladCatalog getCatalog() { return impaladCatalog_; }
  public AuthorizationChecker getAuthzChecker() { return authzChecker_.get(); }

  public TUpdateCatalogCacheResponse updateCatalogCache(
      TUpdateCatalogCacheRequest req) throws CatalogException {
    ImpaladCatalog catalog = impaladCatalog_;

    // If this is not a delta, this update should replace the current
    // Catalog contents so create a new catalog and populate it.
    if (!req.is_delta) catalog = new ImpaladCatalog();

    TUpdateCatalogCacheResponse response = catalog.updateCatalog(req);

    if (!req.is_delta) {
      // This was not a delta update. Now that the catalog has been updated,
      // replace the references to impaladCatalog_/authzChecker_ ensure
      // clients continue don't see the catalog disappear.
      impaladCatalog_ = catalog;
      authzChecker_.set(new AuthorizationChecker(authzConfig_,
          impaladCatalog_.getAuthPolicy()));
    }
    return response;
  }

  /**
   * Update the cluster membership snapshot with the latest snapshot from the backend.
   */
  public void updateMembership(TUpdateMembershipRequest req) {
    MembershipSnapshot.update(req);
  }

  /**
   * Constructs a TCatalogOpRequest and attaches it, plus any metadata, to the
   * result argument.
   */
  private void createCatalogOpRequest(AnalysisContext.AnalysisResult analysis,
      TExecRequest result) throws InternalException {
    TCatalogOpRequest ddl = new TCatalogOpRequest();
    TResultSetMetadata metadata = new TResultSetMetadata();
    if (analysis.isUseStmt()) {
      ddl.op_type = TCatalogOpType.USE;
      ddl.setUse_db_params(analysis.getUseStmt().toThrift());
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isShowTablesStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_TABLES;
      ddl.setShow_tables_params(analysis.getShowTablesStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift())));
    } else if (analysis.isShowDbsStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_DBS;
      ddl.setShow_dbs_params(analysis.getShowDbsStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift()),
          new TColumn("comment", Type.STRING.toThrift())));
    } else if (analysis.isShowDataSrcsStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_DATA_SRCS;
      ddl.setShow_data_srcs_params(analysis.getShowDataSrcsStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift()),
          new TColumn("location", Type.STRING.toThrift()),
          new TColumn("class name", Type.STRING.toThrift()),
          new TColumn("api version", Type.STRING.toThrift())));
    } else if (analysis.isShowStatsStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_STATS;
      ddl.setShow_stats_params(analysis.getShowStatsStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift())));
    } else if (analysis.isShowFunctionsStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_FUNCTIONS;
      ShowFunctionsStmt stmt = (ShowFunctionsStmt)analysis.getStmt();
      ddl.setShow_fns_params(stmt.toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("return type", Type.STRING.toThrift()),
          new TColumn("signature", Type.STRING.toThrift()),
          new TColumn("binary type", Type.STRING.toThrift()),
          new TColumn("is persistent", Type.STRING.toThrift())));
    } else if (analysis.isShowCreateTableStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_CREATE_TABLE;
      ddl.setShow_create_table_params(analysis.getShowCreateTableStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("result", Type.STRING.toThrift())));
    } else if (analysis.isShowCreateFunctionStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_CREATE_FUNCTION;
      ddl.setShow_create_function_params(analysis.getShowCreateFunctionStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("result", Type.STRING.toThrift())));
    } else if (analysis.isShowFilesStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_FILES;
      ddl.setShow_files_params(analysis.getShowFilesStmt().toThrift());
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isDescribeDbStmt()) {
      ddl.op_type = TCatalogOpType.DESCRIBE_DB;
      ddl.setDescribe_db_params(analysis.getDescribeDbStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift()),
          new TColumn("location", Type.STRING.toThrift()),
          new TColumn("comment", Type.STRING.toThrift())));
    } else if (analysis.isDescribeTableStmt()) {
      ddl.op_type = TCatalogOpType.DESCRIBE_TABLE;
      ddl.setDescribe_table_params(analysis.getDescribeTableStmt().toThrift());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift()),
          new TColumn("type", Type.STRING.toThrift()),
          new TColumn("comment", Type.STRING.toThrift())));
    } else if (analysis.isAlterTableStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.ALTER_TABLE);
      req.setAlter_table_params(analysis.getAlterTableStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isAlterViewStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.ALTER_VIEW);
      req.setAlter_view_params(analysis.getAlterViewStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateTableStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_TABLE);
      req.setCreate_table_params(analysis.getCreateTableStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateTableAsSelectStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_TABLE_AS_SELECT);
      req.setCreate_table_params(
          analysis.getCreateTableAsSelectStmt().getCreateStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Arrays.asList(
          new TColumn("summary", Type.STRING.toThrift())));
    } else if (analysis.isCreateTableLikeStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_TABLE_LIKE);
      req.setCreate_table_like_params(analysis.getCreateTableLikeStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateViewStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_VIEW);
      req.setCreate_view_params(analysis.getCreateViewStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateDbStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_DATABASE);
      req.setCreate_db_params(analysis.getCreateDbStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateUdfStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      CreateUdfStmt stmt = (CreateUdfStmt) analysis.getStmt();
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_FUNCTION);
      req.setCreate_fn_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateUdaStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_FUNCTION);
      CreateUdaStmt stmt = (CreateUdaStmt)analysis.getStmt();
      req.setCreate_fn_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isCreateDataSrcStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.CREATE_DATA_SOURCE);
      CreateDataSrcStmt stmt = (CreateDataSrcStmt)analysis.getStmt();
      req.setCreate_data_source_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isComputeStatsStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.COMPUTE_STATS);
      req.setCompute_stats_params(analysis.getComputeStatsStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isDropDbStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.DROP_DATABASE);
      req.setDrop_db_params(analysis.getDropDbStmt().toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isDropTableOrViewStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      DropTableOrViewStmt stmt = analysis.getDropTableOrViewStmt();
      req.setDdl_type(stmt.isDropTable() ? TDdlType.DROP_TABLE : TDdlType.DROP_VIEW);
      req.setDrop_table_or_view_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isTruncateStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      TruncateStmt stmt = analysis.getTruncateStmt();
      req.setDdl_type(TDdlType.TRUNCATE_TABLE);
      req.setTruncate_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isDropFunctionStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.DROP_FUNCTION);
      DropFunctionStmt stmt = (DropFunctionStmt)analysis.getStmt();
      req.setDrop_fn_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isDropDataSrcStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.DROP_DATA_SOURCE);
      DropDataSrcStmt stmt = (DropDataSrcStmt)analysis.getStmt();
      req.setDrop_data_source_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isDropStatsStmt()) {
      ddl.op_type = TCatalogOpType.DDL;
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(TDdlType.DROP_STATS);
      DropStatsStmt stmt = (DropStatsStmt) analysis.getStmt();
      req.setDrop_stats_params(stmt.toThrift());
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isResetMetadataStmt()) {
      ddl.op_type = TCatalogOpType.RESET_METADATA;
      ResetMetadataStmt resetMetadataStmt = (ResetMetadataStmt) analysis.getStmt();
      TResetMetadataRequest req = resetMetadataStmt.toThrift();
      ddl.setReset_metadata_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isShowRolesStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_ROLES;
      ShowRolesStmt showRolesStmt = (ShowRolesStmt) analysis.getStmt();
      ddl.setShow_roles_params(showRolesStmt.toThrift());
      Set<String> groupNames =
          getAuthzChecker().getUserGroups(analysis.getAnalyzer().getUser());
      // Check if the user is part of the group (case-sensitive) this SHOW ROLE
      // statement is targeting. If they are already a member of the group,
      // the admin requirement can be removed.
      Preconditions.checkState(ddl.getShow_roles_params().isSetIs_admin_op());
      if (ddl.getShow_roles_params().isSetGrant_group() &&
          groupNames.contains(ddl.getShow_roles_params().getGrant_group())) {
        ddl.getShow_roles_params().setIs_admin_op(false);
      }
      metadata.setColumns(Arrays.asList(
          new TColumn("role_name", Type.STRING.toThrift())));
    } else if (analysis.isShowGrantRoleStmt()) {
      ddl.op_type = TCatalogOpType.SHOW_GRANT_ROLE;
      ShowGrantRoleStmt showGrantRoleStmt = (ShowGrantRoleStmt) analysis.getStmt();
      ddl.setShow_grant_role_params(showGrantRoleStmt.toThrift());
      Set<String> groupNames =
          getAuthzChecker().getUserGroups(analysis.getAnalyzer().getUser());
      // User must be an admin to execute this operation if they have not been granted
      // this role.
      ddl.getShow_grant_role_params().setIs_admin_op(Sets.intersection(groupNames,
          showGrantRoleStmt.getRole().getGrantGroups()).isEmpty());
      metadata.setColumns(Arrays.asList(
          new TColumn("name", Type.STRING.toThrift())));
    } else if (analysis.isCreateDropRoleStmt()) {
      CreateDropRoleStmt createDropRoleStmt = (CreateDropRoleStmt) analysis.getStmt();
      TCreateDropRoleParams params = createDropRoleStmt.toThrift();
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(params.isIs_drop() ? TDdlType.DROP_ROLE : TDdlType.CREATE_ROLE);
      req.setCreate_drop_role_params(params);
      ddl.op_type = TCatalogOpType.DDL;
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isGrantRevokeRoleStmt()) {
      GrantRevokeRoleStmt grantRoleStmt = (GrantRevokeRoleStmt) analysis.getStmt();
      TGrantRevokeRoleParams params = grantRoleStmt.toThrift();
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(params.isIs_grant() ? TDdlType.GRANT_ROLE : TDdlType.REVOKE_ROLE);
      req.setGrant_revoke_role_params(params);
      ddl.op_type = TCatalogOpType.DDL;
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else if (analysis.isGrantRevokePrivStmt()) {
      GrantRevokePrivStmt grantRevokePrivStmt = (GrantRevokePrivStmt) analysis.getStmt();
      TGrantRevokePrivParams params = grantRevokePrivStmt.toThrift();
      TDdlExecRequest req = new TDdlExecRequest();
      req.setDdl_type(params.isIs_grant() ?
          TDdlType.GRANT_PRIVILEGE : TDdlType.REVOKE_PRIVILEGE);
      req.setGrant_revoke_priv_params(params);
      ddl.op_type = TCatalogOpType.DDL;
      ddl.setDdl_params(req);
      metadata.setColumns(Collections.<TColumn>emptyList());
    } else {
      throw new IllegalStateException("Unexpected CatalogOp statement type.");
    }

    result.setResult_set_metadata(metadata);
    result.setCatalog_op_request(ddl);
    if (ddl.getOp_type() == TCatalogOpType.DDL) {
      TCatalogServiceRequestHeader header = new TCatalogServiceRequestHeader();
      header.setRequesting_user(analysis.getAnalyzer().getUser().getName());
      ddl.getDdl_params().setHeader(header);
    }
  }

  /**
   * Loads a table or partition with one or more data files. If the "overwrite" flag
   * in the request is true, all existing data in the table/partition will be replaced.
   * If the "overwrite" flag is false, the files will be added alongside any existing
   * data files.
   */
  public TLoadDataResp loadTableData(TLoadDataReq request) throws ImpalaException,
      IOException {
    TableName tableName = TableName.fromThrift(request.getTable_name());

    // Get the destination for the load. If the load is targeting a partition,
    // this the partition location. Otherwise this is the table location.
    String destPathString = null;
    if (request.isSetPartition_spec()) {
      destPathString = impaladCatalog_.getHdfsPartition(tableName.getDb(),
          tableName.getTbl(), request.getPartition_spec()).getLocation();
    } else {
      destPathString = impaladCatalog_.getTable(tableName.getDb(), tableName.getTbl())
          .getMetaStoreTable().getSd().getLocation();
    }

    Path destPath = new Path(destPathString);
    Path sourcePath = new Path(request.source_path);
    FileSystem destFs = destPath.getFileSystem(FileSystemUtil.getConfiguration());
    FileSystem sourceFs = sourcePath.getFileSystem(FileSystemUtil.getConfiguration());

    // Create a temporary directory within the final destination directory to stage the
    // file move.
    Path tmpDestPath = FileSystemUtil.makeTmpSubdirectory(destPath);

    int filesLoaded = 0;
    if (sourceFs.isDirectory(sourcePath)) {
      filesLoaded = FileSystemUtil.relocateAllVisibleFiles(sourcePath, tmpDestPath);
    } else {
      FileSystemUtil.relocateFile(sourcePath, tmpDestPath, true);
      filesLoaded = 1;
    }

    // If this is an OVERWRITE, delete all files in the destination.
    if (request.isOverwrite()) {
      FileSystemUtil.deleteAllVisibleFiles(destPath);
    }

    // Move the files from the temporary location to the final destination.
    FileSystemUtil.relocateAllVisibleFiles(tmpDestPath, destPath);
    // Cleanup the tmp directory.
    destFs.delete(tmpDestPath, true);
    TLoadDataResp response = new TLoadDataResp();
    TColumnValue col = new TColumnValue();
    String loadMsg = String.format(
        "Loaded %d file(s). Total files in destination location: %d",
        filesLoaded, FileSystemUtil.getTotalNumVisibleFiles(destPath));
    col.setString_val(loadMsg);
    response.setLoad_summary(new TResultRow(Lists.newArrayList(col)));
    return response;
  }

  /**
   * Parses and plans a query in order to generate its explain string. This method does
   * not increase the query id counter.
   */
  public String getExplainString(TQueryCtx queryCtx) throws ImpalaException {
    StringBuilder stringBuilder = new StringBuilder();
    createExecRequest(queryCtx, stringBuilder);
    return stringBuilder.toString();
  }

  /**
   * Returns all tables in database 'dbName' that match the pattern of 'matcher' and are
   * accessible to 'user'.
   */
  public List<String> getTableNames(String dbName, PatternMatcher matcher,
      User user) throws ImpalaException {
    List<String> tblNames = impaladCatalog_.getTableNames(dbName, matcher);
    if (authzConfig_.isEnabled()) {
      Iterator<String> iter = tblNames.iterator();
      while (iter.hasNext()) {
        String tblName = iter.next();
        PrivilegeRequest privilegeRequest = new PrivilegeRequestBuilder()
            .any().onAnyColumn(dbName, tblName).toRequest();
        if (!authzChecker_.get().hasAccess(user, privilegeRequest)) {
          iter.remove();
        }
      }
    }
    return tblNames;
  }

  /**
   * Returns a list of columns of a table using 'matcher' and are accessible
   * to the given user.
   */
  public List<Column> getColumns(Table table, PatternMatcher matcher,
      User user) throws InternalException {
    Preconditions.checkNotNull(table);
    Preconditions.checkNotNull(matcher);
    List<Column> columns = Lists.newArrayList();
    for (Column column: table.getColumnsInHiveOrder()) {
      String colName = column.getName();
      if (!matcher.matches(colName)) continue;
      if (authzConfig_.isEnabled()) {
        PrivilegeRequest privilegeRequest = new PrivilegeRequestBuilder()
            .any().onColumn(table.getTableName().getDb(), table.getTableName().getTbl(),
            colName).toRequest();
        if (!authzChecker_.get().hasAccess(user, privilegeRequest)) continue;
      }
      columns.add(column);
    }
    return columns;
  }

  /**
   * Returns all databases in catalog cache that match the pattern of 'matcher' and are
   * accessible to 'user'.
   */
  public List<Db> getDbs(PatternMatcher matcher, User user)
      throws InternalException {
    List<Db> dbs = impaladCatalog_.getDbs(matcher);
    // If authorization is enabled, filter out the databases the user does not
    // have permissions on.
    if (authzConfig_.isEnabled()) {
      Iterator<Db> iter = dbs.iterator();
      while (iter.hasNext()) {
        Db db = iter.next();
        if (!isAccessibleToUser(db, user)) iter.remove();
      }
    }
    return dbs;
  }

  /**
   * Check whether database is accessible to given user.
   */
  private boolean isAccessibleToUser(Db db, User user)
      throws InternalException {
    if (db.getName().toLowerCase().equals(Catalog.DEFAULT_DB.toLowerCase())) {
      // Default DB should always be shown.
      return true;
    }
    PrivilegeRequest request = new PrivilegeRequestBuilder()
        .any().onAnyTable(db.getName()).toRequest();
    return authzChecker_.get().hasAccess(user, request);
  }

  /**
   * Returns all data sources that match the pattern. If pattern is null,
   * matches all data sources.
   */
  public List<DataSource> getDataSrcs(String pattern) {
    return impaladCatalog_.getDataSources(
        PatternMatcher.createHivePatternMatcher(pattern));
  }

  /**
   * Generate result set and schema for a SHOW COLUMN STATS command.
   */
  public TResultSet getColumnStats(String dbName, String tableName)
      throws ImpalaException {
    Table table = impaladCatalog_.getTable(dbName, tableName);
    TResultSet result = new TResultSet();
    TResultSetMetadata resultSchema = new TResultSetMetadata();
    result.setSchema(resultSchema);
    resultSchema.addToColumns(new TColumn("Column", Type.STRING.toThrift()));
    resultSchema.addToColumns(new TColumn("Type", Type.STRING.toThrift()));
    resultSchema.addToColumns(
        new TColumn("#Distinct Values", Type.BIGINT.toThrift()));
    resultSchema.addToColumns(new TColumn("#Nulls", Type.BIGINT.toThrift()));
    resultSchema.addToColumns(new TColumn("Max Size", Type.INT.toThrift()));
    resultSchema.addToColumns(new TColumn("Avg Size", Type.DOUBLE.toThrift()));

    for (Column c: table.getColumnsInHiveOrder()) {
      TResultRowBuilder rowBuilder = new TResultRowBuilder();
      // Add name, type, NDVs, numNulls, max size and avg size.
      rowBuilder.add(c.getName()).add(c.getType().toSql())
          .add(c.getStats().getNumDistinctValues()).add(c.getStats().getNumNulls())
          .add(c.getStats().getMaxSize()).add(c.getStats().getAvgSize());
      result.addToRows(rowBuilder.get());
    }
    return result;
  }

  /**
   * Generate result set and schema for a SHOW TABLE STATS command.
   */
  public TResultSet getTableStats(String dbName, String tableName)
      throws ImpalaException {
    Table table = impaladCatalog_.getTable(dbName, tableName);
    if (table instanceof HdfsTable) {
      return ((HdfsTable) table).getTableStats();
    } else if (table instanceof HBaseTable) {
      return ((HBaseTable) table).getTableStats();
    } else if (table instanceof DataSourceTable) {
      return ((DataSourceTable) table).getTableStats();
    } else if (table instanceof KuduTable) {
      return ((KuduTable) table).getTableStats();
    } else {
      throw new InternalException("Invalid table class: " + table.getClass());
    }
  }

  /**
   * Returns all function signatures that match the pattern. If pattern is null,
   * matches all functions. If exactMatch is true, treats fnPattern as a function
   * name instead of pattern and returns exact match only.
   */
  public List<Function> getFunctions(TFunctionCategory category,
      String dbName, String fnPattern, boolean exactMatch)
      throws DatabaseNotFoundException {
    Db db = impaladCatalog_.getDb(dbName);
    if (db == null) {
      throw new DatabaseNotFoundException("Database '" + dbName + "' not found");
    }
    List<Function> fns;
    if (exactMatch) {
      Preconditions.checkNotNull(fnPattern, "Invalid function name");
      fns = db.getFunctions(category, fnPattern);
    } else {
      fns = db.getFunctions(
        category, PatternMatcher.createHivePatternMatcher(fnPattern));
    }
    Collections.sort(fns,
        new Comparator<Function>() {
          @Override
          public int compare(Function f1, Function f2) {
            return f1.signatureString().compareTo(f2.signatureString());
          }
        });
    return fns;
  }

  /**
   * Returns database metadata, in the specified database. Throws an exception if db is
   * not found or if there is an error loading the db metadata.
   */
  public TDescribeResult describeDb(String dbName, TDescribeOutputStyle outputStyle)
      throws ImpalaException {
    Db db = impaladCatalog_.getDb(dbName);
    return DescribeResultFactory.buildDescribeDbResult(db, outputStyle);
  }

  /**
   * Returns table metadata, such as the column descriptors, in the specified table.
   * Throws an exception if the table or db is not found or if there is an error loading
   * the table metadata.
   */
  public TDescribeResult describeTable(String dbName, String tableName,
      TDescribeOutputStyle outputStyle, TColumnType tResultStruct)
          throws ImpalaException {
    if (outputStyle == TDescribeOutputStyle.MINIMAL) {
      StructType resultStruct = (StructType)Type.fromThrift(tResultStruct);
      return DescribeResultFactory.buildDescribeMinimalResult(resultStruct);
    } else {
      Preconditions.checkArgument(outputStyle == TDescribeOutputStyle.FORMATTED ||
          outputStyle == TDescribeOutputStyle.EXTENDED);
      Table table = impaladCatalog_.getTable(dbName, tableName);
      return DescribeResultFactory.buildDescribeFormattedResult(table);
    }
  }

  /**
   * Given a set of table names, returns the set of table names that are missing
   * metadata (are not yet loaded).
   */
  private Set<TableName> getMissingTbls(Set<TableName> tableNames) {
    Set<TableName> missingTbls = new HashSet<TableName>();
    for (TableName tblName: tableNames) {
      Db db = getCatalog().getDb(tblName.getDb());
      if (db == null) continue;
      Table tbl = db.getTable(tblName.getTbl());
      if (tbl == null) continue;
      if (!tbl.isLoaded()) missingTbls.add(tblName);
    }
    return missingTbls;
  }

  /**
   * Requests the catalog server load the given set of tables and waits until
   * these tables show up in the local catalog, or the given timeout has been reached.
   * The timeout is specified in milliseconds, with a value <= 0 indicating no timeout.
   * The exact steps taken are:
   * 1) Collect the tables that are missing (not yet loaded locally).
   * 2) Make an RPC to the CatalogServer to prioritize the loading of these tables.
   * 3) Wait until the local catalog contains all missing tables by (re)checking the
   *    catalog each time a new catalog update is received.
   *
   * Returns true if all missing tables were received before timing out and false if
   * the timeout was reached before all tables were received.
   */
  private boolean requestTblLoadAndWait(Set<TableName> requestedTbls, long timeoutMs)
      throws InternalException {
    Set<TableName> missingTbls = getMissingTbls(requestedTbls);
    // There are no missing tables, return and avoid making an RPC to the CatalogServer.
    if (missingTbls.isEmpty()) return true;

    // Call into the CatalogServer and request the required tables be loaded.
    LOG.info(String.format("Requesting prioritized load of table(s): %s",
        Joiner.on(", ").join(missingTbls)));
    TStatus status = FeSupport.PrioritizeLoad(missingTbls);
    if (status.getStatus_code() != TErrorCode.OK) {
      throw new InternalException("Error requesting prioritized load: " +
          Joiner.on("\n").join(status.getError_msgs()));
    }

    long startTimeMs = System.currentTimeMillis();
    // Wait until all the required tables are loaded in the Impalad's catalog cache.
    while (!missingTbls.isEmpty()) {
      // Check if the timeout has been reached.
      if (timeoutMs > 0 && System.currentTimeMillis() - startTimeMs > timeoutMs) {
        return false;
      }

      LOG.trace(String.format("Waiting for table(s) to complete loading: %s",
          Joiner.on(", ").join(missingTbls)));
      getCatalog().waitForCatalogUpdate(MAX_CATALOG_UPDATE_WAIT_TIME_MS);
      missingTbls = getMissingTbls(missingTbls);
      // TODO: Check for query cancellation here.
    }
    return true;
  }

  /**
   * Overload of requestTblLoadAndWait that uses the default timeout.
   */
  public boolean requestTblLoadAndWait(Set<TableName> requestedTbls)
      throws InternalException {
    return requestTblLoadAndWait(requestedTbls, MISSING_TBL_LOAD_WAIT_TIMEOUT_MS);
  }

  /**
   * Analyzes the SQL statement included in queryCtx and returns the AnalysisResult.
   * Authorizes all catalog object accesses and throws an AuthorizationException
   * if the user does not have privileges to access one or more objects.
   * If a statement fails analysis because table/view metadata was not loaded, an
   * RPC to the CatalogServer will be executed to request loading the missing metadata
   * and analysis will be restarted once the required tables have been loaded
   * in the local Impalad Catalog or the MISSING_TBL_LOAD_WAIT_TIMEOUT_MS timeout
   * is reached.
   * The goal of this timeout is not to analysis, but to restart the analysis/missing
   * table collection process. This helps ensure a statement never waits indefinitely
   * for a table to be loaded in event the table metadata was invalidated.
   * TODO: Also consider adding an overall timeout that fails analysis.
   */
  private AnalysisContext.AnalysisResult analyzeStmt(TQueryCtx queryCtx)
      throws AnalysisException, InternalException, AuthorizationException {
    if (!impaladCatalog_.isReady()) {
      throw new AnalysisException("This Impala daemon is not ready to accept user " +
          "requests. Status: Waiting for catalog update from the StateStore.");
    }

    AnalysisContext analysisCtx = new AnalysisContext(impaladCatalog_, queryCtx,
        authzConfig_);
    LOG.debug("analyze query " + queryCtx.request.stmt);

    // Run analysis in a loop until it any of the following events occur:
    // 1) Analysis completes successfully.
    // 2) Analysis fails with an AnalysisException AND there are no missing tables.
    // 3) Analysis fails with an AuthorizationException.
    try {
      while (true) {
        try {
          analysisCtx.analyze(queryCtx.request.stmt);
          Preconditions.checkState(analysisCtx.getAnalyzer().getMissingTbls().isEmpty());
          return analysisCtx.getAnalysisResult();
        } catch (AnalysisException e) {
          Set<TableName> missingTbls = analysisCtx.getAnalyzer().getMissingTbls();
          // Only re-throw the AnalysisException if there were no missing tables.
          if (missingTbls.isEmpty()) throw e;

          // Some tables/views were missing, request and wait for them to load.
          if (!requestTblLoadAndWait(missingTbls, MISSING_TBL_LOAD_WAIT_TIMEOUT_MS)) {
            LOG.info(String.format("Missing tables were not received in %dms. Load " +
                "request will be retried.", MISSING_TBL_LOAD_WAIT_TIMEOUT_MS));
          }
        }
      }
    } finally {
      // Authorize all accesses.
      // AuthorizationExceptions must take precedence over any AnalysisException
      // that has been thrown, so perform the authorization first.
      analysisCtx.authorize(getAuthzChecker());
    }
  }

  /**
   * Return a TPlanExecInfo corresponding to the plan with root fragment 'planRoot'.
   */
  private TPlanExecInfo createPlanExecInfo(PlanFragment planRoot, Planner planner,
      TQueryCtx queryCtx, TQueryExecRequest queryExecRequest) {
    TPlanExecInfo result = new TPlanExecInfo();
    ArrayList<PlanFragment> fragments = planRoot.getNodesPreOrder();

    // map from fragment to its index in TPlanExecInfo.fragments; needed for
    // TPlanExecInfo.dest_fragment_idx
    List<ScanNode> scanNodes = Lists.newArrayList();
    Map<PlanFragment, Integer> fragmentIdx = Maps.newHashMap();
    for (int idx = 0; idx < fragments.size(); ++idx) {
      PlanFragment fragment = fragments.get(idx);
      Preconditions.checkNotNull(fragment.getPlanRoot());
      fragment.getPlanRoot().collect(Predicates.instanceOf(ScanNode.class), scanNodes);
      fragmentIdx.put(fragment, idx);
    }

    // set fragment destinations
    for (int i = 1; i < fragments.size(); ++i) {
      PlanFragment dest = fragments.get(i).getDestFragment();
      Integer idx = fragmentIdx.get(dest);
      Preconditions.checkState(idx != null);
      result.addToDest_fragment_idx(idx.intValue());
    }

    // Set scan ranges/locations for scan nodes.
    LOG.debug("get scan range locations");
    Set<TTableName> tablesMissingStats = Sets.newTreeSet();
    Set<TTableName> tablesWithCorruptStats = Sets.newTreeSet();
    for (ScanNode scanNode: scanNodes) {
      result.putToPer_node_scan_ranges(
          scanNode.getId().asInt(), scanNode.getScanRangeLocations());
      if (scanNode.isTableMissingStats()) {
        tablesMissingStats.add(scanNode.getTupleDesc().getTableName().toThrift());
      }
      if (scanNode.hasCorruptTableStats()) {
        tablesWithCorruptStats.add(scanNode.getTupleDesc().getTableName().toThrift());
      }
    }

    for (TTableName tableName: tablesMissingStats) {
      queryCtx.addToTables_missing_stats(tableName);
    }
    for (TTableName tableName: tablesWithCorruptStats) {
      queryCtx.addToTables_with_corrupt_stats(tableName);
    }

    // The fragment at this point has all state set, serialize it to thrift.
    for (PlanFragment fragment: fragments) {
      TPlanFragment thriftFragment = fragment.toThrift();
      result.addToFragments(thriftFragment);
    }

    return result;
  }

  /**
   * Create a populated TQueryExecRequest, corresponding to the supplied planner,
   * for multi-threaded execution.
   */
  private TQueryExecRequest mtCreateExecRequest(
      Planner planner, StringBuilder explainString)
      throws ImpalaException {
    TQueryCtx queryCtx = planner.getQueryCtx();
    Preconditions.checkState(queryCtx.request.query_options.mt_dop > 0);
    // for now, always disable spilling in the backend
    // TODO-MT: re-enable spilling
    queryCtx.setDisable_spilling(true);
    TQueryExecRequest result = new TQueryExecRequest();

    LOG.debug("create mt plan");
    List<PlanFragment> planRoots = planner.createParallelPlans();

    // create EXPLAIN output
    result.setQuery_ctx(queryCtx);  // needed by getExplainString()
    explainString.append(
        planner.getExplainString(Lists.newArrayList(planRoots.get(0)), result));
    result.setQuery_plan(explainString.toString());

    // create per-plan exec info;
    // also assemble list of names of tables with missing or corrupt stats for
    // assembling a warning message
    for (PlanFragment planRoot: planRoots) {
      result.addToMt_plan_exec_info(
          createPlanExecInfo(planRoot, planner, queryCtx, result));
    }

    // assign fragment ids
    int idx = 0;
    for (TPlanExecInfo planExecInfo: result.mt_plan_exec_info) {
      for (TPlanFragment fragment: planExecInfo.fragments) fragment.setIdx(idx++);
    }

    // TODO-MT: implement
    // Compute resource requirements after scan range locations because the cost
    // estimates of scan nodes rely on them.
    //try {
      //planner.computeResourceReqs(fragments, true, queryExecRequest);
    //} catch (Exception e) {
      //// Turn exceptions into a warning to allow the query to execute.
      //LOG.error("Failed to compute resource requirements for query\n" +
          //queryCtx.request.getStmt(), e);
    //}

    return result;
  }

  /**
   * Create a populated TQueryExecRequest corresponding to the supplied TQueryCtx.
   * TODO-MT: remove this function and rename mtCreateExecRequest() to
   * createExecRequest()
   */
  private TQueryExecRequest createExecRequest(
      Planner planner, StringBuilder explainString)
      throws ImpalaException {
    LOG.debug("create plan");
    ArrayList<PlanFragment> fragments = planner.createPlan();

    List<ScanNode> scanNodes = Lists.newArrayList();
    // map from fragment to its index in queryExecRequest.fragments; needed for
    // queryExecRequest.dest_fragment_idx
    Map<PlanFragment, Integer> fragmentIdx = Maps.newHashMap();

    for (int idx = 0; idx < fragments.size(); ++idx) {
      PlanFragment fragment = fragments.get(idx);
      Preconditions.checkNotNull(fragment.getPlanRoot());
      fragment.getPlanRoot().collect(Predicates.instanceOf(ScanNode.class), scanNodes);
      fragmentIdx.put(fragment, idx);
    }

    TQueryExecRequest result = new TQueryExecRequest();
    // set fragment destinations
    for (int i = 1; i < fragments.size(); ++i) {
      PlanFragment dest = fragments.get(i).getDestFragment();
      Integer idx = fragmentIdx.get(dest);
      Preconditions.checkState(idx != null);
      result.addToDest_fragment_idx(idx.intValue());
    }

    // Set scan ranges/locations for scan nodes.
    // Also assemble list of tables names missing stats for assembling a warning message.
    LOG.debug("get scan range locations");
    Set<TTableName> tablesMissingStats = Sets.newTreeSet();
    // Assemble a similar list for corrupt stats
    Set<TTableName> tablesWithCorruptStats = Sets.newTreeSet();
    for (ScanNode scanNode: scanNodes) {
      result.putToPer_node_scan_ranges(
          scanNode.getId().asInt(), scanNode.getScanRangeLocations());
      if (scanNode.isTableMissingStats()) {
        tablesMissingStats.add(scanNode.getTupleDesc().getTableName().toThrift());
      }
      if (scanNode.hasCorruptTableStats()) {
        tablesWithCorruptStats.add(scanNode.getTupleDesc().getTableName().toThrift());
      }
    }

    TQueryCtx queryCtx = planner.getQueryCtx();
    for (TTableName tableName: tablesMissingStats) {
      queryCtx.addToTables_missing_stats(tableName);
    }
    for (TTableName tableName: tablesWithCorruptStats) {
      queryCtx.addToTables_with_corrupt_stats(tableName);
    }

    // Optionally disable spilling in the backend. Allow spilling if there are plan hints
    // or if all tables have stats.
    AnalysisContext.AnalysisResult analysisResult = planner.getAnalysisResult();
    if (queryCtx.request.query_options.isDisable_unsafe_spills()
        && !tablesMissingStats.isEmpty()
        && !analysisResult.getAnalyzer().hasPlanHints()) {
      queryCtx.setDisable_spilling(true);
    }

    // Compute resource requirements after scan range locations because the cost
    // estimates of scan nodes rely on them.
    try {
      planner.computeResourceReqs(fragments, true, result);
    } catch (Exception e) {
      // Turn exceptions into a warning to allow the query to execute.
      LOG.error("Failed to compute resource requirements for query\n" +
          queryCtx.request.getStmt(), e);
    }

    // The fragment at this point has all state set, assign sequential ids
    // and serialize to thrift.
    for (int i = 0; i < fragments.size(); ++i) {
      PlanFragment fragment = fragments.get(i);
      TPlanFragment thriftFragment = fragment.toThrift();
      thriftFragment.setIdx(i);
      result.addToFragments(thriftFragment);
    }

    result.setQuery_ctx(queryCtx);  // needed by getExplainString()
    explainString.append(planner.getExplainString(fragments, result));
    result.setQuery_plan(explainString.toString());
    return result;
  }

  /**
   * Create a populated TExecRequest corresponding to the supplied TQueryCtx.
   */
  public TExecRequest createExecRequest(TQueryCtx queryCtx, StringBuilder explainString)
      throws ImpalaException {
    // Analyze the statement
    AnalysisContext.AnalysisResult analysisResult = analyzeStmt(queryCtx);
    EventSequence timeline = analysisResult.getAnalyzer().getTimeline();
    timeline.markEvent("Analysis finished");
    Preconditions.checkNotNull(analysisResult.getStmt());
    TExecRequest result = new TExecRequest();
    result.setQuery_options(queryCtx.request.getQuery_options());
    result.setAccess_events(analysisResult.getAccessEvents());
    result.analysis_warnings = analysisResult.getAnalyzer().getWarnings();

    if (analysisResult.isCatalogOp()) {
      result.stmt_type = TStmtType.DDL;
      createCatalogOpRequest(analysisResult, result);
      TLineageGraph thriftLineageGraph = analysisResult.getThriftLineageGraph();
      if (thriftLineageGraph != null && thriftLineageGraph.isSetQuery_text()) {
        result.catalog_op_request.setLineage_graph(thriftLineageGraph);
      }
      // All DDL operations except for CTAS are done with analysis at this point.
      if (!analysisResult.isCreateTableAsSelectStmt()) return result;
    } else if (analysisResult.isLoadDataStmt()) {
      result.stmt_type = TStmtType.LOAD;
      result.setResult_set_metadata(new TResultSetMetadata(Arrays.asList(
          new TColumn("summary", Type.STRING.toThrift()))));
      result.setLoad_data_request(analysisResult.getLoadDataStmt().toThrift());
      return result;
    } else if (analysisResult.isSetStmt()) {
      result.stmt_type = TStmtType.SET;
      result.setResult_set_metadata(new TResultSetMetadata(Arrays.asList(
          new TColumn("option", Type.STRING.toThrift()),
          new TColumn("value", Type.STRING.toThrift()))));
      result.setSet_query_option_request(analysisResult.getSetStmt().toThrift());
      return result;
    }

    // create TQueryExecRequest
    Preconditions.checkState(analysisResult.isQueryStmt() || analysisResult.isDmlStmt()
        || analysisResult.isCreateTableAsSelectStmt() || analysisResult.isUpdateStmt()
        || analysisResult.isDeleteStmt());

    Planner planner = new Planner(analysisResult, queryCtx);
    TQueryExecRequest queryExecRequest;
    if (analysisResult.isQueryStmt() && queryCtx.request.query_options.mt_dop > 0) {
      queryExecRequest = mtCreateExecRequest(planner, explainString);
    } else {
      queryExecRequest = createExecRequest(planner, explainString);
    }
    queryExecRequest.setDesc_tbl(
        planner.getAnalysisResult().getAnalyzer().getDescTbl().toThrift());
    queryExecRequest.setQuery_ctx(queryCtx);
    queryExecRequest.setHost_list(analysisResult.getAnalyzer().getHostIndex().getList());

    TLineageGraph thriftLineageGraph = analysisResult.getThriftLineageGraph();
    if (thriftLineageGraph != null && thriftLineageGraph.isSetQuery_text()) {
      queryExecRequest.setLineage_graph(thriftLineageGraph);
    }

    if (analysisResult.isExplainStmt()) {
      // Return the EXPLAIN request
      createExplainRequest(explainString.toString(), result);
      return result;
    }

    result.setQuery_exec_request(queryExecRequest);
    if (analysisResult.isQueryStmt()) {
      // fill in the metadata
      LOG.debug("create result set metadata");
      result.stmt_type = TStmtType.QUERY;
      result.query_exec_request.stmt_type = result.stmt_type;
      TResultSetMetadata metadata = new TResultSetMetadata();
      QueryStmt queryStmt = analysisResult.getQueryStmt();
      int colCnt = queryStmt.getColLabels().size();
      for (int i = 0; i < colCnt; ++i) {
        TColumn colDesc = new TColumn();
        colDesc.columnName = queryStmt.getColLabels().get(i);
        colDesc.columnType = queryStmt.getResultExprs().get(i).getType().toThrift();
        metadata.addToColumns(colDesc);
      }
      result.setResult_set_metadata(metadata);
    } else if (analysisResult.isInsertStmt() ||
        analysisResult.isCreateTableAsSelectStmt()) {
      // For CTAS the overall TExecRequest statement type is DDL, but the
      // query_exec_request should be DML
      result.stmt_type =
          analysisResult.isCreateTableAsSelectStmt() ? TStmtType.DDL : TStmtType.DML;
      result.query_exec_request.stmt_type = TStmtType.DML;

      // create finalization params of insert stmt
      InsertStmt insertStmt = analysisResult.getInsertStmt();
      if (insertStmt.getTargetTable() instanceof HdfsTable) {
        TFinalizeParams finalizeParams = new TFinalizeParams();
        finalizeParams.setIs_overwrite(insertStmt.isOverwrite());
        finalizeParams.setTable_name(insertStmt.getTargetTableName().getTbl());
        finalizeParams.setTable_id(insertStmt.getTargetTable().getId().asInt());
        String db = insertStmt.getTargetTableName().getDb();
        finalizeParams.setTable_db(db == null ? queryCtx.session.database : db);
        HdfsTable hdfsTable = (HdfsTable) insertStmt.getTargetTable();
        finalizeParams.setHdfs_base_dir(hdfsTable.getHdfsBaseDir());
        finalizeParams.setStaging_dir(
            hdfsTable.getHdfsBaseDir() + "/_impala_insert_staging");
        queryExecRequest.setFinalize_params(finalizeParams);
      }
    } else {
      Preconditions.checkState(analysisResult.isUpdateStmt() || analysisResult.isDeleteStmt());
      result.stmt_type = TStmtType.DML;
      result.query_exec_request.stmt_type = TStmtType.DML;
    }

    validateTableIds(analysisResult.getAnalyzer(), result);

    timeline.markEvent("Planning finished");
    result.setTimeline(analysisResult.getAnalyzer().getTimeline().toThrift());
    return result;
  }

  /**
   * Check that we don't have any duplicate table IDs (see IMPALA-1702).
   * To be removed when IMPALA-1702 is resolved.
   */
  private void validateTableIds(Analyzer analyzer, TExecRequest result)
      throws InternalException {
    Map<TableId, Table> tableIds = Maps.newHashMap();
    Collection<TupleDescriptor> tupleDescs = analyzer.getDescTbl().getTupleDescs();
    for (TupleDescriptor desc: tupleDescs) {
      // Skip if tuple descriptor did not come from materializing scan.
      if (!desc.isMaterialized()) continue;
      Table table = desc.getTable();
      if (table == null) continue;
      Table otherTable = tableIds.get(table.getId());
      if (otherTable == table) continue; // Same table referenced twice
      if (otherTable == null) {
        tableIds.put(table.getId(), table);
        continue;
      }
      LOG.error("Found duplicate table ID! id=" + table.getId() + "\ntable1=\n"
          + table.toTCatalogObject() + "\ntable2=\n" + otherTable.toTCatalogObject()
          + "\nexec_request=\n" + result);
      throw new InternalException("Query encountered invalid metadata, likely due to " +
          "IMPALA-1702. Please try rerunning the query.");
    }
  }

  /**
   * Attaches the explain result to the TExecRequest.
   */
  private void createExplainRequest(String explainString, TExecRequest result) {
    // update the metadata - one string column
    TColumn colDesc = new TColumn("Explain String", Type.STRING.toThrift());
    TResultSetMetadata metadata = new TResultSetMetadata(Lists.newArrayList(colDesc));
    result.setResult_set_metadata(metadata);

    // create the explain result set - split the explain string into one line per row
    String[] explainStringArray = explainString.toString().split("\n");
    TExplainResult explainResult = new TExplainResult();
    explainResult.results = Lists.newArrayList();
    for (int i = 0; i < explainStringArray.length; ++i) {
      TColumnValue col = new TColumnValue();
      col.setString_val(explainStringArray[i]);
      TResultRow row = new TResultRow(Lists.newArrayList(col));
      explainResult.results.add(row);
    }
    result.setExplain_result(explainResult);
    result.stmt_type = TStmtType.EXPLAIN;
  }

  /**
   * Executes a HiveServer2 metadata operation and returns a TResultSet
   */
  public TResultSet execHiveServer2MetadataOp(TMetadataOpRequest request)
      throws ImpalaException {
    User user = request.isSetSession() ?
        new User(TSessionStateUtil.getEffectiveUser(request.session)) :
        ImpalaInternalAdminUser.getInstance();
    switch (request.opcode) {
      case GET_TYPE_INFO: return MetadataOp.getTypeInfo();
      case GET_SCHEMAS:
      {
        TGetSchemasReq req = request.getGet_schemas_req();
        return MetadataOp.getSchemas(this, req.getCatalogName(),
            req.getSchemaName(), user);
      }
      case GET_TABLES:
      {
        TGetTablesReq req = request.getGet_tables_req();
        return MetadataOp.getTables(this, req.getCatalogName(),
            req.getSchemaName(), req.getTableName(), req.getTableTypes(), user);
      }
      case GET_COLUMNS:
      {
        TGetColumnsReq req = request.getGet_columns_req();
        return MetadataOp.getColumns(this, req.getCatalogName(),
            req.getSchemaName(), req.getTableName(), req.getColumnName(), user);
      }
      case GET_CATALOGS: return MetadataOp.getCatalogs();
      case GET_TABLE_TYPES: return MetadataOp.getTableTypes();
      case GET_FUNCTIONS:
      {
        TGetFunctionsReq req = request.getGet_functions_req();
        return MetadataOp.getFunctions(this, req.getCatalogName(),
            req.getSchemaName(), req.getFunctionName(), user);
      }
      default:
        throw new NotImplementedException(request.opcode + " has not been implemented.");
    }
  }

  /**
   * Returns all files info of a table or partition.
   */
  public TResultSet getTableFiles(TShowFilesParams request)
      throws ImpalaException{
    Table table = impaladCatalog_.getTable(request.getTable_name().getDb_name(),
        request.getTable_name().getTable_name());
    if (table instanceof HdfsTable) {
      return ((HdfsTable) table).getFiles(request.getPartition_spec());
    } else {
      throw new InternalException("SHOW FILES only supports Hdfs table. " +
          "Unsupported table class: " + table.getClass());
    }
  }
}
