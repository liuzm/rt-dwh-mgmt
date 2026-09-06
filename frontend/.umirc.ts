import { defineConfig } from '@umijs/max';

export default defineConfig({
  antd: {
    configProvider: {
      theme: {
        token: {
          colorBgBase: '#ffffff',
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorBgLayout: '#f4f6f8',
          colorTextBase: '#344054',
          colorTextSecondary: '#667085',
          colorBorder: '#dfe4ea',
          colorBorderSecondary: '#edf0f3',
          colorFillAlter: '#f8fafc',
          borderRadius: 6,
          borderRadiusLG: 10,
          controlHeight: 32,
          controlHeightSM: 26,
          fontSize: 13,
          fontSizeSM: 12,
        },
        components: {
          Table: {
            headerBg: '#f8fafc',
            headerColor: '#344054',
            rowHoverBg: '#f5faff',
            borderColor: '#edf0f3',
          },
          Input: {
            activeBg: '#ffffff',
            hoverBg: '#ffffff',
          },
          Select: {
            selectorBg: '#ffffff',
            optionActiveBg: '#e6f7ff',
            optionSelectedBg: '#e6f7ff',
          },
          Button: {
            defaultBg: '#ffffff',
            defaultColor: '#333333',
          },
        },
      },
    },
  },
  access: {},
  model: {},
  initialState: {},
  // app.tsx already unwraps the backend's ApiResponse.data globally.
  // Keep useRequest from applying its default result => result.data a
  // second time, which otherwise turns arrays and business objects into
  // undefined across list, dashboard, settings, quality and report pages.
  request: {
    dataField: '',
  },
  layout: {
    layout: 'side',
    title: '实时数仓管理平台',
    locale: true,
    navTheme: 'dark',
    token: {
      sider: {
        colorBgMenuItemSelected: '#1890ff',
        colorBgMenuItemHover: 'rgba(255,255,255,0.08)',
      },
      header: {
        heightLayoutHeader: 48,
        colorBgMenuItemSelected: '#1890ff',
        colorBgMenuItemHover: 'rgba(255,255,255,0.08)',
      },
    },
  },
  locale: {
    default: 'zh-CN',
    baseSeparator: '-',
    antd: true,
  },
  hash: true,
  jsMinifier: 'terser',
  chainWebpack(config) {
    // Monaco 0.56 class static blocks lose imported bindings when this toolchain
    // concatenates modules (actions_Action2 / localize2 at runtime).
    config.optimization.concatenateModules(false);
  },
  plugins: [],
  metas: [
    { name: 'color-scheme', content: 'only light' },
  ],
  proxy: {
    '/api/v1': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      pathRewrite: { '^/api/v1': '' },
    },
  },
  routes: [
    {
      path: '/user',
      layout: false,
      routes: [
        { path: '/user/login', component: './User/Login' },
      ],
    },
    // === 总览 ===
    {
      path: '/dashboard',
      name: '工作台',
      icon: 'DashboardOutlined',
      component: './Dashboard',
    },
    {
      path: '/foundation',
      name: '资产检索与治理',
      icon: 'SafetyCertificateOutlined',
      component: './Foundation',
      access: 'canViewFoundation',
    },
    // === 同步任务 ===
    {
      path: '/sync-task',
      name: '数据开发',
      icon: 'ThunderboltOutlined',
      routes: [
        { path: '/sync-task/list', name: '开发任务', icon: 'UnorderedListOutlined', component: './SyncTask/List', access: 'canViewTask' },
        { path: '/sync-task/workflow', name: '调度与运行', icon: 'ApartmentOutlined', component: './Workflow', access: 'canViewTask' },
        { path: '/sync-task/create', name: '创建任务', icon: 'PlusOutlined', component: './SyncTask/Create', hideInMenu: true, access: 'canCreateTask' },
        { path: '/sync-task/detail/:id', name: '任务详情', icon: 'ProfileOutlined', component: './SyncTask/Detail', hideInMenu: true, access: 'canViewTask' },
        { path: '/sync-task/datasource', name: '数据源连接', icon: 'ApiOutlined', component: './Datasource', access: 'canViewDatasource' },
      ],
    },
    // === 数仓管理 ===
    {
      path: '/dwh',
      name: '数据资产',
      icon: 'DatabaseOutlined',
      routes: [
        { path: '/dwh/tables', name: '资产目录', icon: 'TableOutlined', component: './DwhTable/List', access: 'canViewDwh' },
        { path: '/dwh/tables/:id', name: '表详情', icon: 'ProfileOutlined', component: './DwhTable/Detail', hideInMenu: true, access: 'canViewDwh' },
        { path: '/dwh/assets/:assetId', name: '资产详情', component: './DwhTable/Detail', hideInMenu: true, access: 'canViewDwh' },
        { path: '/dwh/lineage', name: '数据血缘', icon: 'ApartmentOutlined', component: './Lineage', access: 'canViewLineage' },
        { path: '/dwh/maintenance', name: '存储维护', icon: 'ToolOutlined', component: './Maintenance', access: 'canManageDwh' },
      ],
    },
    // === 数据质量 ===
    {
      path: '/quality',
      name: '质量工作台',
      icon: 'CheckCircleOutlined',
      component: './Quality',
      access: 'canViewQuality',
    },
    // === 查询与报表 ===
    {
      path: '/query',
      name: '数据消费',
      icon: 'SearchOutlined',
      routes: [
        { path: '/query/adhoc', name: 'SQL 查询', icon: 'CodeOutlined', component: './AdhocQuery', access: 'canQuery' },
        { path: '/query/report', name: '报表看板', icon: 'BarChartOutlined', component: './Report', access: 'canViewReport' },
        { path: '/query/data-service', name: '数据 API', icon: 'ApiOutlined', component: './DataService', access: 'canViewDataService' },
      ],
    },
    // === 告警与系统 ===
    {
      path: '/system',
      name: '平台管理',
      icon: 'AlertOutlined',
      routes: [
        { path: '/system/alert', name: '运行告警', icon: 'BellOutlined', component: './Alert', access: 'canViewAlert' },
        { path: '/system/settings', name: '环境配置', icon: 'SettingOutlined', component: './Settings', access: 'canViewSettings' },
        { path: '/system/users', name: '用户与权限', icon: 'TeamOutlined', component: './UserAdmin', access: 'canManageUser' },
        { path: '/system/audit', name: '操作审计', icon: 'AuditOutlined', component: './Audit', access: 'canViewAudit' },
      ],
    },
  ],
  theme: {
    'primary-color': '#1890ff',
  },
});
