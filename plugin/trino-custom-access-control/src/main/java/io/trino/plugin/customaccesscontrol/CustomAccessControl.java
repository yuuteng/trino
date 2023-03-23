/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.customaccesscontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.trino.plugin.base.security.CatalogAccessControlRule;
import io.trino.plugin.base.security.TableAccessControlRule;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaRoutineName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.function.FunctionKind;
import io.trino.spi.security.Identity;
import io.trino.spi.security.Privilege;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemAccessControlFactory;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.security.ViewExpression;
import io.trino.spi.type.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.plugin.base.security.CatalogAccessControlRule.AccessMode.ALL;
import static io.trino.plugin.base.security.CatalogAccessControlRule.AccessMode.NONE;
import static io.trino.plugin.base.security.CatalogAccessControlRule.AccessMode.OWNER;
import static io.trino.plugin.base.security.CatalogAccessControlRule.AccessMode.READ_ONLY;
import static io.trino.plugin.base.security.TableAccessControlRule.TablePrivilege.DELETE;
import static io.trino.plugin.base.security.TableAccessControlRule.TablePrivilege.GRANT_SELECT;
import static io.trino.plugin.base.security.TableAccessControlRule.TablePrivilege.INSERT;
import static io.trino.plugin.base.security.TableAccessControlRule.TablePrivilege.OWNERSHIP;
import static io.trino.plugin.base.security.TableAccessControlRule.TablePrivilege.SELECT;
import static io.trino.plugin.base.security.TableAccessControlRule.TablePrivilege.UPDATE;
import static io.trino.spi.security.AccessDeniedException.denyAddColumn;
import static io.trino.spi.security.AccessDeniedException.denyAlterColumn;
import static io.trino.spi.security.AccessDeniedException.denyCatalogAccess;
import static io.trino.spi.security.AccessDeniedException.denyCommentColumn;
import static io.trino.spi.security.AccessDeniedException.denyCommentTable;
import static io.trino.spi.security.AccessDeniedException.denyCommentView;
import static io.trino.spi.security.AccessDeniedException.denyCreateCatalog;
import static io.trino.spi.security.AccessDeniedException.denyCreateMaterializedView;
import static io.trino.spi.security.AccessDeniedException.denyCreateRole;
import static io.trino.spi.security.AccessDeniedException.denyCreateSchema;
import static io.trino.spi.security.AccessDeniedException.denyCreateTable;
import static io.trino.spi.security.AccessDeniedException.denyCreateView;
import static io.trino.spi.security.AccessDeniedException.denyCreateViewWithSelect;
import static io.trino.spi.security.AccessDeniedException.denyDeleteTable;
import static io.trino.spi.security.AccessDeniedException.denyDenySchemaPrivilege;
import static io.trino.spi.security.AccessDeniedException.denyDenyTablePrivilege;
import static io.trino.spi.security.AccessDeniedException.denyDropCatalog;
import static io.trino.spi.security.AccessDeniedException.denyDropColumn;
import static io.trino.spi.security.AccessDeniedException.denyDropMaterializedView;
import static io.trino.spi.security.AccessDeniedException.denyDropRole;
import static io.trino.spi.security.AccessDeniedException.denyDropSchema;
import static io.trino.spi.security.AccessDeniedException.denyDropTable;
import static io.trino.spi.security.AccessDeniedException.denyDropView;
import static io.trino.spi.security.AccessDeniedException.denyExecuteFunction;
import static io.trino.spi.security.AccessDeniedException.denyGrantRoles;
import static io.trino.spi.security.AccessDeniedException.denyGrantSchemaPrivilege;
import static io.trino.spi.security.AccessDeniedException.denyGrantTablePrivilege;
import static io.trino.spi.security.AccessDeniedException.denyInsertTable;
import static io.trino.spi.security.AccessDeniedException.denyRefreshMaterializedView;
import static io.trino.spi.security.AccessDeniedException.denyRenameColumn;
import static io.trino.spi.security.AccessDeniedException.denyRenameMaterializedView;
import static io.trino.spi.security.AccessDeniedException.denyRenameSchema;
import static io.trino.spi.security.AccessDeniedException.denyRenameTable;
import static io.trino.spi.security.AccessDeniedException.denyRenameView;
import static io.trino.spi.security.AccessDeniedException.denyRevokeRoles;
import static io.trino.spi.security.AccessDeniedException.denyRevokeSchemaPrivilege;
import static io.trino.spi.security.AccessDeniedException.denyRevokeTablePrivilege;
import static io.trino.spi.security.AccessDeniedException.denySelectColumns;
import static io.trino.spi.security.AccessDeniedException.denySetMaterializedViewProperties;
import static io.trino.spi.security.AccessDeniedException.denySetSchemaAuthorization;
import static io.trino.spi.security.AccessDeniedException.denySetTableAuthorization;
import static io.trino.spi.security.AccessDeniedException.denySetTableProperties;
import static io.trino.spi.security.AccessDeniedException.denySetViewAuthorization;
import static io.trino.spi.security.AccessDeniedException.denyShowColumns;
import static io.trino.spi.security.AccessDeniedException.denyShowCreateSchema;
import static io.trino.spi.security.AccessDeniedException.denyShowCreateTable;
import static io.trino.spi.security.AccessDeniedException.denyShowSchemas;
import static io.trino.spi.security.AccessDeniedException.denyShowTables;
import static io.trino.spi.security.AccessDeniedException.denyTruncateTable;
import static io.trino.spi.security.AccessDeniedException.denyUpdateTableColumns;
import static java.lang.String.format;
import static java.util.Collections.emptySet;

public class CustomAccessControl
        implements SystemAccessControl
{
    public static final String NAME = "custom-access-control";
    private static final CustomAccessControl INSTANCE = new CustomAccessControl();
    private static final String OPA_SERVER_URL = "http://192.168.31.137:8000/check_access";
    private static final Logger log = Logger.get(CustomAccessControl.class);

    public static class Factory
            implements SystemAccessControlFactory
    {
        @Override
        public String getName()
        {
            return NAME;
        }

        @Override
        public SystemAccessControl create(Map<String, String> config)
        {
            return INSTANCE;
        }
    }

    @Override
    public void checkCanExecuteQuery(SystemSecurityContext context)
    {
        //        TODO: Let it go
//        if (!canAccessQuery(context.getIdentity(), QueryAccessRule.AccessMode.EXECUTE)) {
//            denyViewQuery();
//        }
    }

    @Override
    public void checkCanViewQueryOwnedBy(SystemSecurityContext context, Identity queryOwner)
    {
        //        TODO: Let it go
//        Identity user = context.getIdentity();
//        if (!user.equals(queryOwner) && !canAccessQuery(queryOwner, QueryAccessRule.AccessMode.VIEW)) {
//            denyViewQuery();
//        }
    }

    @Override
    public void checkCanKillQueryOwnedBy(SystemSecurityContext context, Identity queryOwner)
    {
        //        TODO: Let it go
//        Identity user = context.getIdentity();
//        if (user.equals(queryOwner) || !canAccessQuery(queryOwner, QueryAccessRule.AccessMode.KILL)) {
//            denyKillQuery();
//        }
    }

    @Override
    public void checkCanReadSystemInformation(SystemSecurityContext context)
    {
        //        TODO: Let it go
//        if (!canSystemInformation(context.getIdentity(), SystemInformationRule.AccessMode.READ)) {
//            denyReadSystemInformationAccess();
//        }
    }

    @Override
    public void checkCanWriteSystemInformation(SystemSecurityContext context)
    {
//        TODO: Let it go
//        if (!canSystemInformation(context.getIdentity(), SystemInformationRule.AccessMode.WRITE)) {
//            denyWriteSystemInformationAccess();
//        }
    }

    @Override
    public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
    {
//        TODO: Let it go
//        if (!canSystemProperty(context.getIdentity(), propertyName)) {
//            denySetSystemSessionProperty(propertyName);
//        }
    }

    @Override
    public void checkCanAccessCatalog(SystemSecurityContext context, String catalogName)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName, READ_ONLY)) {
            denyCatalogAccess(catalogName);
        }
    }

    @Override
    public void checkCanCreateCatalog(SystemSecurityContext context, String catalogName)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName, OWNER)) {
            denyCreateCatalog(catalogName);
        }
    }

    @Override
    public void checkCanDropCatalog(SystemSecurityContext context, String catalogName)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName, OWNER)) {
            denyDropCatalog(catalogName);
        }
    }

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        ImmutableSet.Builder<String> filteredCatalogs = ImmutableSet.builder();
        for (String catalog : catalogs) {
            if (canAccessCatalog(context.getIdentity(), catalog, NONE)) {
                filteredCatalogs.add(catalog);
            }
        }
        return filteredCatalogs.build();
    }

    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schema, Map<String, Object> properties)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)) {
            denyCreateSchema(schema.toString());
        }
    }

    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)) {
            denyDropSchema(schema.toString());
        }
    }

    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName schema, String newSchemaName)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)
                || !canAccessSchema(context.getIdentity(), schema.getCatalogName(), newSchemaName, ALL)) {
            denyRenameSchema(schema.toString(), newSchemaName);
        }
    }

    @Override
    public void checkCanSetSchemaAuthorization(SystemSecurityContext context, CatalogSchemaName schema, TrinoPrincipal principal)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)) {
            denySetSchemaAuthorization(schema.toString(), principal);
        }
    }

    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName)
    {
        if (!canAccessCatalog(context.getIdentity(), catalogName, READ_ONLY)) {
            denyShowSchemas();
        }
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        return schemaNames.stream()
                .filter(schemaName -> canAccessSchema(context.getIdentity(), catalogName, schemaName, READ_ONLY))
                .collect(toImmutableSet());
    }

    @Override
    public void checkCanShowCreateSchema(SystemSecurityContext context, CatalogSchemaName schemaName)
    {
        if (!canAccessSchema(context.getIdentity(), schemaName.getCatalogName(), schemaName.getSchemaName(), ALL)) {
            denyShowCreateSchema(schemaName.toString());
        }
    }

    @Override
    public void checkCanShowCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyShowCreateTable(table.toString());
        }
    }

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Object> properties)
    {
        // check if user will be an owner of the table after creation
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyCreateTable(table.toString());
        }
    }

    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyDropTable(table.toString());
        }
    }

    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName table, CatalogSchemaTableName newTable)
    {
        // check if user is an owner current table and will be an owner of the renamed table
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP) || !canAccessTable(context.getIdentity(), newTable, OWNERSHIP)) {
            denyRenameTable(table.toString(), newTable.toString());
        }
    }

    @Override
    public void checkCanSetTableProperties(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Optional<Object>> properties)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denySetTableProperties(table.toString());
        }
    }

    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyCommentTable(table.toString());
        }
    }

    @Override
    public void checkCanSetViewComment(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        if (!canAccessTable(context.getIdentity(), view, OWNERSHIP)) {
            denyCommentView(view.toString());
        }
    }

    @Override
    public void checkCanSetColumnComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyCommentColumn(table.toString());
        }
    }

    @Override
    public void checkCanShowTables(SystemSecurityContext context, CatalogSchemaName schema)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), READ_ONLY)) {
            denyShowTables(schema.toString());
        }
    }

    @Override
    public Set<SchemaTableName> filterTables(SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
//        TODO: !!!!
        return emptySet();
//        return tableNames.stream()
//                .filter(tableName -> isSchemaOwner(context, new CatalogSchemaName(catalogName, tableName.getSchemaName())) ||
//                        checkAnyTablePermission(context, new CatalogSchemaTableName(catalogName, tableName)))
//                .collect(toImmutableSet());
    }

    @Override
    public void checkCanShowColumns(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, SELECT)) {
            denyShowColumns(table.toString());
        }
    }

    @Override
    public Set<String> filterColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
//        TODO: Can't override
        return emptySet();
    }

    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyAddColumn(table.toString());
        }
    }

    @Override
    public void checkCanAlterColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyAlterColumn(table.toString());
        }
    }

    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyDropColumn(table.toString());
        }
    }

    @Override
    public void checkCanSetTableAuthorization(SystemSecurityContext context, CatalogSchemaTableName table, TrinoPrincipal principal)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denySetTableAuthorization(table.toString(), principal);
        }
    }

    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyRenameColumn(table.toString());
        }
    }

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        if (!canAccessTable(context.getIdentity(), table, SELECT)) {
            denySelectColumns(table.toString(), columns);
        }
    }

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, INSERT)) {
            denyInsertTable(table.toString());
        }
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, DELETE)) {
            denyDeleteTable(table.toString());
        }
    }

    @Override
    public void checkCanTruncateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        if (!canAccessTable(context.getIdentity(), table, DELETE)) {
            denyTruncateTable(table.toString());
        }
    }

    @Override
    public void checkCanUpdateTableColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> updatedColumnNames)
    {
        if (!canAccessTable(context.getIdentity(), table, UPDATE)) {
            denyUpdateTableColumns(table.toString(), updatedColumnNames);
        }
    }

    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        // check if user will be an owner of the view after creation
        if (!canAccessTable(context.getIdentity(), view, OWNERSHIP)) {
            denyCreateView(view.toString());
        }
    }

    @Override
    public void checkCanRenameView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView)
    {
        // check if user owns the existing view, and if they will be an owner of the view after the rename
        if (!canAccessTable(context.getIdentity(), view, OWNERSHIP) || !canAccessTable(context.getIdentity(), newView, OWNERSHIP)) {
            denyRenameView(view.toString(), newView.toString());
        }
    }

    @Override
    public void checkCanSetViewAuthorization(SystemSecurityContext context, CatalogSchemaTableName view, TrinoPrincipal principal)
    {
        if (!canAccessTable(context.getIdentity(), view, OWNERSHIP)) {
            denySetViewAuthorization(view.toString(), principal);
        }
    }

    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        if (!canAccessTable(context.getIdentity(), view, OWNERSHIP)) {
            denyDropView(view.toString());
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyCreateViewWithSelect(table.toString(), context.getIdentity());
        }
    }

    @Override
    public void checkCanCreateMaterializedView(SystemSecurityContext context, CatalogSchemaTableName materializedView, Map<String, Object> properties)
    {
        // check if user will be an owner of the view after creation
        if (!canAccessTable(context.getIdentity(), materializedView, OWNERSHIP)) {
            denyCreateMaterializedView(materializedView.toString());
        }
    }

    @Override
    public void checkCanRefreshMaterializedView(SystemSecurityContext context, CatalogSchemaTableName materializedView)
    {
        if (!canAccessTable(context.getIdentity(), materializedView, UPDATE)) {
            denyRefreshMaterializedView(materializedView.toString());
        }
    }

    @Override
    public void checkCanSetMaterializedViewProperties(SystemSecurityContext context, CatalogSchemaTableName materializedView, Map<String, Optional<Object>> properties)
    {
        if (!canAccessTable(context.getIdentity(), materializedView, OWNERSHIP)) {
            denySetMaterializedViewProperties(materializedView.toString());
        }
    }

    @Override
    public void checkCanDropMaterializedView(SystemSecurityContext context, CatalogSchemaTableName materializedView)
    {
        if (!canAccessTable(context.getIdentity(), materializedView, OWNERSHIP)) {
            denyDropMaterializedView(materializedView.toString());
        }
    }

    @Override
    public void checkCanRenameMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView)
    {
        // check if user owns the existing materialized view, and if they will be an owner of the materialized view after the rename
        if (!canAccessTable(context.getIdentity(), view, OWNERSHIP) || !canAccessTable(context.getIdentity(), newView, OWNERSHIP)) {
            denyRenameMaterializedView(view.toString(), newView.toString());
        }
    }

    @Override
    public void checkCanGrantExecuteFunctionPrivilege(SystemSecurityContext context, String functionName, TrinoPrincipal grantee, boolean grantOption)
    {
//        TODO: Can't override
    }

    @Override
    public void checkCanGrantExecuteFunctionPrivilege(SystemSecurityContext context, FunctionKind functionKind, CatalogSchemaRoutineName functionName, TrinoPrincipal grantee, boolean grantOption)
    {
//        TODO: Can't override
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName)
    {
//        TODO: Can't override
    }

    @Override
    public void checkCanGrantSchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee, boolean grantOption)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)) {
            denyGrantSchemaPrivilege(privilege.name(), schema.getSchemaName());
        }
    }

    @Override
    public void checkCanDenySchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)) {
            denyDenySchemaPrivilege(privilege.name(), schema.getSchemaName());
        }
    }

    @Override
    public void checkCanRevokeSchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal revokee, boolean grantOption)
    {
        if (!canAccessSchema(context.getIdentity(), schema.getCatalogName(), schema.getSchemaName(), ALL)) {
            denyRevokeSchemaPrivilege(privilege.name(), schema.getSchemaName());
        }
    }

    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee, boolean grantOption)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyGrantTablePrivilege(privilege.name(), table.toString());
        }
    }

    @Override
    public void checkCanDenyTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyDenyTablePrivilege(privilege.name(), table.toString());
        }
    }

    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal revokee, boolean grantOption)
    {
        if (!canAccessTable(context.getIdentity(), table, OWNERSHIP)) {
            denyRevokeTablePrivilege(privilege.name(), table.toString());
        }
    }

    @Override
    public void checkCanShowRoles(SystemSecurityContext context)
    {
        // allow, no roles are supported so show will always be empty
    }

    @Override
    public void checkCanCreateRole(SystemSecurityContext context, String role, Optional<TrinoPrincipal> grantor)
    {
        denyCreateRole(role);
    }

    @Override
    public void checkCanDropRole(SystemSecurityContext context, String role)
    {
        denyDropRole(role);
    }

    @Override
    public void checkCanGrantRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor)
    {
        denyGrantRoles(roles, grantees);
    }

    @Override
    public void checkCanRevokeRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor)
    {
        denyRevokeRoles(roles, grantees);
    }

    @Override
    public void checkCanShowRoleAuthorizationDescriptors(SystemSecurityContext context)
    {
        // allow, no roles are supported so show will always be empty
    }

    @Override
    public void checkCanShowCurrentRoles(SystemSecurityContext context)
    {
        // allow, no roles are supported so show will always be empty
    }

    @Override
    public void checkCanShowRoleGrants(SystemSecurityContext context)
    {
        // allow, no roles are supported so show will always be empty
    }

    @Override
    public void checkCanExecuteProcedure(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName procedure)
    {
    }

    @Override
    public void checkCanExecuteFunction(SystemSecurityContext systemSecurityContext, String functionName)
    {
        denyExecuteFunction(functionName);
    }

    @Override
    public void checkCanExecuteFunction(SystemSecurityContext systemSecurityContext, FunctionKind functionKind, CatalogSchemaRoutineName functionName)
    {
        denyExecuteFunction(functionName.toString());
    }

    @Override
    public void checkCanExecuteTableProcedure(SystemSecurityContext systemSecurityContext, CatalogSchemaTableName table, String procedure)
    {
    }

    @Override
    public List<ViewExpression> getRowFilters(SystemSecurityContext context, CatalogSchemaTableName tableName)
    {
//        TODO: Check or not ?
        return List.of();
    }

    @Override
    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName tableName, String columnName, Type type)
    {
//        TODO: Check or not ?
        List<ViewExpression> masks = getColumnMasks(context, tableName, columnName, type);
        if (masks.size() > 1) {
            throw new UnsupportedOperationException("Multiple masks on a single column are no longer supported");
        }

        return masks.stream().findFirst();
    }

    private boolean canAccessCatalog(Identity identity, String catalogName, CatalogAccessControlRule.AccessMode accessMode)
    {
        Map<String, Object> body = new HashMap<>();
        body.put("service", "adac");
        body.put("resource", "catalog");
        body.put("action", accessMode);
        body.put("id", catalogName);
        body.put("name", catalogName);
        body.put("path", catalogName);
        body.put("timestamp", System.currentTimeMillis());
        body.put("sub", identity.getUser());
        body.put("dataplant-id", "JWT->DataplantId");
        body.put("attributes", "{}");
        body.put("with-conditions", true);
        return isOpaAllowed(body);
    }

    private boolean canAccessSchema(Identity identity, String catalogName, String schemaName, CatalogAccessControlRule.AccessMode accessMode)
    {
        Map<String, Object> body = new HashMap<>();
        body.put("service", "adac");
        body.put("resource", "schema");
        body.put("action", accessMode);
        body.put("id", format("%s.%s", catalogName, schemaName));
        body.put("name", schemaName);
        body.put("path", catalogName);
        body.put("timestamp", System.currentTimeMillis());
        body.put("sub", identity.getUser());
        body.put("dataplant-id", "JWT->DataplantId");
        body.put("attributes", "{}");
        body.put("with-conditions", true);
        return isOpaAllowed(body);
    }

    private boolean canAccessTable(Identity identity, CatalogSchemaTableName table, TableAccessControlRule.TablePrivilege requiredPrivilege)
    {
        CatalogAccessControlRule.AccessMode requiredCatalogAccess = requiredPrivilege == SELECT || requiredPrivilege == GRANT_SELECT ? READ_ONLY : ALL;
        Map<String, Object> body = new HashMap<>();
        body.put("service", "adac");
        body.put("resource", "table");
        body.put("action", requiredCatalogAccess);
        body.put("id", format("%s.%s.%s", table.getCatalogName(), table.getSchemaTableName().getSchemaName(), table.getSchemaTableName().getTableName()));
        body.put("name", table.getSchemaTableName().getTableName());
        body.put("path", format("%s.%s", table.getCatalogName(), table.getSchemaTableName().getSchemaName()));
        body.put("timestamp", System.currentTimeMillis());
        body.put("sub", identity.getUser());
        body.put("dataplant-id", "JWT->DataplantId");
        body.put("attributes", "{}");
        body.put("with-conditions", true);
        return isOpaAllowed(body);
    }

////        TODO: Can pass this check
//    private boolean canAccessQuery(Identity identity, QueryAccessRule.AccessMode accessMode)
//    {
//        Map<String, Object> body = Map.of(
//                "type", "QueryAccess",
//                "user", identity.getUser(),
//                "catalog", identity.getEnabledRoles(),
//                "schema", identity.getGroups(),
//                "access_mode", accessMode);
//        return isOpaAllowed(body);
//    }

////        TODO: Can pass this check
//    private boolean canSystemInformation(Identity identity, SystemInformationRule.AccessMode requiredAccess)
//    {
//        Map<String, Object> body = Map.of(
//                "type", "SystemInformation",
//                "user", identity.getUser(),
//                "access_mode", requiredAccess);
//        return isOpaAllowed(body);
//    }

////        TODO: Can pass this check
//    private boolean canSystemProperty(Identity identity, String propertyName)
//    {
//        Map<String, Object> body = Map.of(
//                "service", "adac",
//                "resource", "system",
//                "action", "read",
//                "id", "??",
//                "path", "",
//                "timestamp", "",
//                "name", propertyName,
//                "user", identity.getUser(),
//                "catalog", identity.getEnabledRoles(),
//                "schema", identity.getGroups());
//        return isOpaAllowed(body);
//    }

    private boolean isOpaAllowed(Map<String, Object> body)
    {
        try {
            URL url = new URL(OPA_SERVER_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Prepare input for OPA server
            String jsonInput = new ObjectMapper().writeValueAsString(body);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] inputBytes = jsonInput.getBytes(StandardCharsets.UTF_8);
                os.write(inputBytes, 0, inputBytes.length);
            }

            // Send POST request to OPA server and parse response to get the access decision
            int responseCode = connection.getResponseCode();
            log.info("CustomAccessControl.isOpaAllowed: responseCode" + responseCode);
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String responseJson = reader.readLine();
                    Map<String, Object> response = new ObjectMapper().readValue(responseJson, Map.class);
                    return Boolean.parseBoolean(response.getOrDefault("result", "false").toString());
                }
            }
            else {
                throw new RuntimeException("Failed to communicate with OPA server, HTTP error code: " + responseCode);
            }
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to communicate with OPA server", e);
        }
    }
}
