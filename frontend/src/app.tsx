import { history, RunTimeLayoutConfig } from '@umijs/max';
import { getCurrentUser } from '@/api';
import type { RequestConfig } from '@umijs/max';
import { message } from 'antd';
import React from 'react';
import {
  DatabaseOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import './global.less';

/** 白名单路由 —— 不需要登录即可访问 */
const LOGIN_PATH = '/user/login';
const whiteList = [LOGIN_PATH];

/** 父路由默认重定向映射 */
const parentRedirects: Record<string, string> = {
  '/': '/dashboard',
  '/sync-task': '/sync-task/list',
  '/dwh': '/dwh/tables',
  '/query': '/query/adhoc',
  '/system': '/system/alert',
};

/**
 * onRouteChange — 路由切换时统一拦截登录态 + 处理父路由默认重定向
 * 注意：不使用 route config 的 redirect，因为 Umi 对有 children 的父路由加 redirect
 *       会生成 <Navigate element>，与子路由同时匹配时触发无限 setState 循环
 */
export function onRouteChange({ location }: { location: { pathname: string } }) {
  const { pathname } = location;
  const token = localStorage.getItem('token');

  // 未登录 → 跳登录页
  if (!token && !whiteList.includes(pathname)) {
    history.push(LOGIN_PATH);
    return;
  }
  // 已登录但访问登录页 → 跳首页
  if (token && pathname === LOGIN_PATH) {
    history.replace('/dashboard');
    return;
  }
  // 父路由默认重定向（替代 route config 中的 redirect）
  if (parentRedirects[pathname]) {
    history.replace(parentRedirects[pathname]);
    return;
  }
}

/**
 * getInitialState — @umijs/max 约定的初始状态函数
 * 注意：这是普通 async 函数，不是 React Hook，不能用 useState/useEffect
 */
export async function getInitialState() {
  const token = localStorage.getItem('token');
  if (!token) {
    return { currentUser: undefined };
  }
  try {
    const currentUser = await getCurrentUser();
    return { currentUser };
  } catch {
    localStorage.removeItem('token');
    return { currentUser: undefined };
  }
}

/**
 * layout — ProLayout 运行时配置
 */
export const layout: RunTimeLayoutConfig = ({ initialState }) => {
  return {
    title: '实时数仓平台',
    logo: <DatabaseOutlined style={{ color: '#69b1ff', fontSize: 22 }} />,
    navTheme: 'realDark',
    colorPrimary: '#1890ff',
    layout: 'side',
    // Keep the navigation visible on first load. Users can still collapse it
    // with the standard ProLayout toggle.
    defaultCollapsed: false,
    siderWidth: 192,
    menu: {
      defaultOpenAll: true,
      // 产品界面统一使用中文导航，不跟随浏览器语言切换，避免中英文混排。
      locale: false,
    },
    contentWidth: 'Fluid',
    contentStyle: {
      minHeight: 'calc(100vh - 48px)',
      padding: 0,
      overflowAnchor: 'none',
    },
    fixedHeader: true,
    fixSiderbar: true,
    currentUser: initialState?.currentUser,
    avatarProps: {
      title: initialState?.currentUser?.username || 'User',
      size: 'small',
      menuProps: {
        items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
        onClick: ({ key }: { key: string }) => {
          if (key === 'logout') {
            localStorage.removeItem('token');
            message.success('已退出登录');
            history.push('/user/login');
          }
        },
      },
    },
    menuFooterRender: () => (
      <div style={{ textAlign: 'center', padding: '8px 0', color: '#999', fontSize: 12 }}>
        RT-DWH v1.0
      </div>
    ),
    menuItemRender: (item, dom) => {
      if (!item.path) return dom;
      const path = item.path;
      return (
        <a
          href={path}
          onClick={(event) => {
            event.preventDefault();
            history.push(path);
          }}
        >
          {item.icon && (
            <span style={{ marginRight: 8, display: 'inline-flex', alignItems: 'center' }}>
              {item.icon}
            </span>
          )}
          <span>{item.name}</span>
        </a>
      );
    },
    unAccessible: <div style={{ padding: 24, textAlign: 'center' }}>无权限访问</div>,
  };
};

/**
 * request — 全局请求拦截器（自动附带 JWT Token，401 跳登录）
 */
export const request: RequestConfig = {
  requestInterceptors: [
    (config: any) => {
      const token = localStorage.getItem('token');
      if (token) {
        config.headers = {
          ...config.headers,
          Authorization: `Bearer ${token}`,
        };
      }
      return config;
    },
  ],
  responseInterceptors: [
    (response: any) => {
      if (response?.status === 401) {
        localStorage.removeItem('token');
        history.push('/user/login');
        return response;
      }
      // 全局解包 ApiResponse：code === 0 时直接返回 .data，让 useRequest 拿到业务数据
      const body = response?.data;
      if (body && typeof body === 'object' && 'code' in body && 'data' in body) {
        if (body.code === 0) {
          response.data = body.data;
        } else {
          // 业务错误：抛出异常，走 errorHandler
          const error: any = new Error(body.message || '请求失败');
          error.response = response;
          error.data = body;
          throw error;
        }
      }
      return response;
    },
  ],
  errorConfig: {
    errorThrower: () => {},
    errorHandler: (error: any) => {
      if (error?.response?.status === 401) {
        localStorage.removeItem('token');
        history.push('/user/login');
        return;
      }
      message.error(error?.message || '请求异常');
    },
  },
};
