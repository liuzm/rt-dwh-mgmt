/**
 * access — @umijs/max 权限定义
 * 后端返回 role 为逗号分隔字符串，如 "ADMIN" 或 "ADMIN,DEVELOPER"
 */
export default function access(initialState: { currentUser?: API.CurrentUser } | undefined) {
  const { currentUser } = initialState ?? {};
  const roleStr = currentUser?.role ?? '';
  const roles = roleStr ? roleStr.split(',') : [];
  const permissions = new Set(currentUser?.permissions || []);
  const has = (permission: string) => !!currentUser && permissions.has(permission);

  return {
    canAdmin: !!currentUser && roles.includes('ADMIN'),
    canDeveloper: !!currentUser && (roles.includes('ADMIN') || roles.includes('DEVELOPER')),
    canOperator: !!currentUser && (roles.includes('ADMIN') || roles.includes('VISITOR')),
    canVisitor: !!currentUser && roles.length > 0,
    canViewFoundation: has('foundation:view'),
    canViewTask: has('task:view'),
    canCreateTask: has('task:create'),
    canManageTask: has('task:manage'),
    canViewDatasource: has('datasource:view'),
    canManageDatasource: has('datasource:manage'),
    canViewDwh: has('dwh:view'),
    canManageDwh: has('dwh:manage'),
    canQuery: has('query:adhoc'),
    canViewReport: has('report:view'),
    canManageReport: has('report:create'),
    canViewQuality: has('quality:view'),
    canManageQuality: has('quality:manage'),
    canViewLineage: has('lineage:view'),
    canViewAlert: has('alert:view'),
    canManageAlert: has('alert:manage'),
    canViewSettings: has('settings:view'),
    canManageSettings: has('settings:manage'),
    canViewAudit: has('audit:view'),
    canManageUser: has('user:manage') || roles.includes('ADMIN'),
    canViewDataService: has('data-service:view'),
    canManageDataService: has('data-service:manage'),
    loggedIn: !!currentUser,
  };
}
