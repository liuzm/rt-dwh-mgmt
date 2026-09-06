import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Form, Input, message, Modal, Popconfirm, Select, Space, Table, Tabs, Tag } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import {
  createAdminRole, createAdminUser, deleteAdminRole, getAdminPermissions, getAdminRoles,
  getAdminUsers, resetAdminUserPassword, toggleAdminUserStatus, updateAdminRole, updateAdminUser,
} from '@/api';

const BUILTIN_ROLES = ['ADMIN', 'DEVELOPER', 'VISITOR'];

const UserAdmin: React.FC = () => {
  const usersRequest = useRequest(getAdminUsers);
  const rolesRequest = useRequest(getAdminRoles);
  const permissionsRequest = useRequest(getAdminPermissions);
  const [userModal, setUserModal] = useState<{ open: boolean; user?: API.AdminUser }>({ open: false });
  const [roleModal, setRoleModal] = useState<{ open: boolean; role?: API.AdminRole }>({ open: false });
  const [passwordUser, setPasswordUser] = useState<API.AdminUser>();
  const [userForm] = Form.useForm();
  const [roleForm] = Form.useForm();
  const [passwordForm] = Form.useForm();
  const users = (usersRequest.data || []) as API.AdminUser[];
  const roles = (rolesRequest.data || []) as API.AdminRole[];
  const permissions = (permissionsRequest.data || []) as API.AdminPermission[];

  const refresh = () => { usersRequest.refresh(); rolesRequest.refresh(); permissionsRequest.refresh(); };
  const editUser = (user?: API.AdminUser) => {
    setUserModal({ open: true, user });
    userForm.resetFields();
    if (user) userForm.setFieldsValue({ ...user, roleIds: user.roles.map((role) => role.id) });
  };
  const editRole = (role?: API.AdminRole) => {
    setRoleModal({ open: true, role });
    roleForm.resetFields();
    if (role) roleForm.setFieldsValue({ ...role, permissionIds: role.permissions.map((permission) => permission.id) });
  };

  return <PageContainer title="用户与权限" subTitle="管理平台账号、自定义角色、接口权限和湖仓数据范围">
    <Card extra={<Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>}>
      <Tabs items={[
        { key: 'users', label: `用户（${users.length}）`, children: <>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => editUser()} style={{ marginBottom: 16 }}>新建用户</Button>
          <Table<API.AdminUser> rowKey="id" loading={usersRequest.loading} dataSource={users} columns={[
            { title: '账号', dataIndex: 'username', width: 150, render: (value, record) => <div><b>{value}</b><div style={{ color: '#8c8c8c' }}>{record.realName || '—'}</div></div> },
            { title: '联系方式', key: 'contact', render: (_, record) => <div>{record.email || '—'}<div style={{ color: '#8c8c8c' }}>{record.phone || ''}</div></div> },
            { title: '角色', dataIndex: 'roles', render: (value: API.AdminRoleSummary[]) => <Space wrap>{value.map((role) => <Tag key={role.id} color={role.roleCode === 'ADMIN' ? 'red' : 'blue'}>{role.roleName}</Tag>)}</Space> },
            { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag color={value === 'active' ? 'success' : 'default'}>{value === 'active' ? '正常' : '已停用'}</Tag> },
            { title: '操作', width: 260, render: (_, record) => <Space>
              <Button size="small" onClick={() => editUser(record)}>编辑</Button>
              <Button size="small" onClick={() => { setPasswordUser(record); passwordForm.resetFields(); }}>重置密码</Button>
              <Popconfirm title={`确认${record.status === 'active' ? '停用' : '启用'}该用户？`} onConfirm={async () => { await toggleAdminUserStatus(record.id); message.success('状态已更新'); usersRequest.refresh(); }}>
                <Button size="small" danger={record.status === 'active'}>{record.status === 'active' ? '停用' : '启用'}</Button>
              </Popconfirm>
            </Space> },
          ]} />
        </> },
        { key: 'roles', label: `角色（${roles.length}）`, children: <>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => editRole()} style={{ marginBottom: 16 }}>新建角色</Button>
          <Table<API.AdminRole> rowKey="id" loading={rolesRequest.loading} dataSource={roles} columns={[
            { title: '角色', key: 'role', width: 220, render: (_, role) => <div><b>{role.roleName}</b><div style={{ color: '#8c8c8c' }}>{role.roleCode}</div></div> },
            { title: '说明', dataIndex: 'description', ellipsis: true },
            { title: '权限数', dataIndex: 'permissions', width: 100, render: (value: API.AdminPermission[]) => value.length },
            { title: '数据范围', dataIndex: 'dataScopes', render: (value: API.AdminRole['dataScopes']) => value?.length
              ? <Space wrap>{value.slice(0, 3).map((scope, index) => <Tag key={scope.id || index} color="geekblue">{scope.catalogPattern}.{scope.databasePattern}.{scope.tablePattern}</Tag>)}{value.length > 3 && <Tag>+{value.length - 3}</Tag>}</Space>
              : <Tag color="warning">未授权</Tag> },
            { title: '操作', width: 180, render: (_, role) => BUILTIN_ROLES.includes(role.roleCode) ? <Tag>内置角色</Tag> : <Space>
              <Button size="small" onClick={() => editRole(role)}>编辑</Button>
              <Popconfirm title="确认删除该角色？" onConfirm={async () => { await deleteAdminRole(role.id); message.success('角色已删除'); rolesRequest.refresh(); }}><Button size="small" danger>删除</Button></Popconfirm>
            </Space> },
          ]} />
        </> },
      ]} />
    </Card>

    <Modal title={userModal.user ? '编辑用户' : '新建用户'} open={userModal.open} onCancel={() => setUserModal({ open: false })} onOk={() => userForm.submit()} destroyOnClose>
      <Form form={userForm} layout="vertical" onFinish={async (values) => {
        if (userModal.user) await updateAdminUser(userModal.user.id, values); else await createAdminUser(values);
        message.success(userModal.user ? '用户已更新' : '用户已创建'); setUserModal({ open: false }); usersRequest.refresh();
      }}>
        {!userModal.user && <><Form.Item name="username" label="用户名" rules={[{ required: true }, { min: 3 }]}><Input /></Form.Item><Form.Item name="password" label="初始密码" rules={[{ required: true }, { min: 8 }]}><Input.Password /></Form.Item></>}
        <Form.Item name="realName" label="姓名"><Input /></Form.Item>
        <Form.Item name="email" label="邮箱" rules={[{ type: 'email' }]}><Input /></Form.Item>
        <Form.Item name="phone" label="手机"><Input /></Form.Item>
        <Form.Item name="roleIds" label="角色" rules={[{ required: true }]}><Select mode="multiple" options={roles.map((role) => ({ value: role.id, label: `${role.roleName}（${role.roleCode}）` }))} /></Form.Item>
      </Form>
    </Modal>

    <Modal title="重置密码" open={!!passwordUser} onCancel={() => setPasswordUser(undefined)} onOk={() => passwordForm.submit()} destroyOnClose>
      <Form form={passwordForm} layout="vertical" onFinish={async ({ password }) => { if (!passwordUser) return; await resetAdminUserPassword(passwordUser.id, password); message.success('密码已重置'); setPasswordUser(undefined); }}>
        <Form.Item name="password" label={`新密码（${passwordUser?.username || ''}）`} rules={[{ required: true }, { min: 8 }]}><Input.Password /></Form.Item>
      </Form>
    </Modal>

    <Modal title={roleModal.role ? '编辑角色' : '新建角色'} open={roleModal.open} onCancel={() => setRoleModal({ open: false })} onOk={() => roleForm.submit()} width={720} destroyOnClose>
      <Form form={roleForm} layout="vertical" onFinish={async (values) => {
        if (roleModal.role) await updateAdminRole(roleModal.role.id, values); else await createAdminRole(values);
        message.success(roleModal.role ? '角色已更新' : '角色已创建'); setRoleModal({ open: false }); rolesRequest.refresh();
      }}>
        <Form.Item name="roleCode" label="角色编码" rules={[{ required: true }]}><Input disabled={!!roleModal.role} placeholder="例如 DATA_ANALYST" /></Form.Item>
        <Form.Item name="roleName" label="角色名称" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="description" label="说明"><Input /></Form.Item>
        <Form.Item name="permissionIds" label="接口权限"><Select mode="multiple" optionFilterProp="label" options={permissions.map((permission) => ({ value: permission.id, label: `${permission.permName}（${permission.permCode}）` }))} /></Form.Item>
        <Form.Item label="湖仓数据范围" extra="留空表示该角色不能查询任何表；支持 * 和 ? 通配符，查询和 Catalog 树都会强制执行。">
          <Form.List name="dataScopes">
            {(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>
              {fields.map((field) => <Space key={field.key} align="baseline" style={{ width: '100%' }}>
                <Form.Item {...field} name={[field.name, 'catalogPattern']} rules={[{ required: true, message: 'Catalog 必填' }]}><Input placeholder="Catalog，如 rtdwh_paimon" /></Form.Item>
                <Form.Item {...field} name={[field.name, 'databasePattern']} rules={[{ required: true, message: 'Database 必填' }]}><Input placeholder="Database，如 ods" /></Form.Item>
                <Form.Item {...field} name={[field.name, 'tablePattern']} rules={[{ required: true, message: 'Table 必填' }]}><Input placeholder="Table，如 ods_order_*" /></Form.Item>
                <Button danger type="text" icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
              </Space>)}
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ catalogPattern: 'rtdwh_paimon', databasePattern: '*', tablePattern: '*' })}>添加数据范围</Button>
            </Space>}
          </Form.List>
        </Form.Item>
      </Form>
    </Modal>
  </PageContainer>;
};

export default UserAdmin;
