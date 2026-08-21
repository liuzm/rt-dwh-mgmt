import React, { useState } from 'react';
import { history, useModel } from '@umijs/max';
import {
  ArrowRightOutlined,
  CheckCircleFilled,
  CloudServerOutlined,
  DatabaseOutlined,
  LockOutlined,
  SafetyCertificateOutlined,
  ThunderboltFilled,
  UserOutlined,
} from '@ant-design/icons';
import { Button, Form, Input, message } from 'antd';
import { login } from '@/api';

const Login: React.FC = () => {
  const [submitting, setSubmitting] = useState(false);
  const { setInitialState } = useModel('@@initialState');

  const handleSubmit = async (values: { username: string; password: string }) => {
    setSubmitting(true);
    try {
      const loginResult = await login(values);
      localStorage.setItem('token', loginResult.token);
      message.success('登录成功');
      await setInitialState((s) => ({
        ...s,
        currentUser: loginResult,
      }));
      history.push('/dashboard');
    } catch (e: any) {
      message.error(e?.message || '登录异常，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="rtdwh-login-page">
      <div className="rtdwh-login-shell">
        <section className="rtdwh-login-showcase">
          <div className="rtdwh-login-showcase-grid" />

          <div className="rtdwh-login-logo">
            <span className="rtdwh-login-logo-mark"><DatabaseOutlined /></span>
            <span>
              <strong>RT-DWH</strong>
              <small>REALTIME DATA PLATFORM</small>
            </span>
          </div>

          <div className="rtdwh-login-showcase-content">
            <div className="rtdwh-login-kicker">
              <span /> REALTIME DATA WAREHOUSE
            </div>
            <h1>让数据持续流动<br />让洞察实时发生</h1>
            <p>统一管理数据接入、流式计算、湖仓存储和任务运维，构建稳定可观测的实时数据链路。</p>

            <div className="rtdwh-login-pipeline">
              <div className="rtdwh-login-pipeline-node">
                <DatabaseOutlined />
                <span><b>数据源</b><small>MySQL · PostgreSQL</small></span>
              </div>
              <ArrowRightOutlined className="rtdwh-login-pipeline-arrow" />
              <div className="rtdwh-login-pipeline-node is-primary">
                <ThunderboltFilled />
                <span><b>实时计算</b><small>Apache Flink</small></span>
              </div>
              <ArrowRightOutlined className="rtdwh-login-pipeline-arrow" />
              <div className="rtdwh-login-pipeline-node">
                <CloudServerOutlined />
                <span><b>湖仓存储</b><small>Apache Paimon</small></span>
              </div>
            </div>
          </div>

          <div className="rtdwh-login-showcase-footer">
            <span><CheckCircleFilled /> Flink 2.x</span>
            <span><CheckCircleFilled /> Paimon Lakehouse</span>
            <span><CheckCircleFilled /> 秒级监控</span>
          </div>
        </section>

        <section className="rtdwh-login-panel">
          <div className="rtdwh-login-mobile-logo">
            <span className="rtdwh-login-logo-mark"><DatabaseOutlined /></span>
            <strong>RT-DWH</strong>
          </div>

          <div className="rtdwh-login-form-wrap">
            <div className="rtdwh-login-brand">
              <div className="rtdwh-login-eyebrow">CONTROL CENTER</div>
              <h2>欢迎登录</h2>
              <p>登录实时数仓管理平台，继续管理您的数据链路。</p>
            </div>

            <Form
              className="rtdwh-login-form"
              layout="vertical"
              requiredMark={false}
              onFinish={handleSubmit}
            >
              <Form.Item
                label="用户名"
                name="username"
                rules={[{ required: true, message: '请输入用户名' }]}
              >
                <Input
                  size="large"
                  prefix={<UserOutlined />}
                  placeholder="请输入用户名"
                  autoComplete="username"
                />
              </Form.Item>

              <Form.Item
                label="密码"
                name="password"
                rules={[{ required: true, message: '请输入密码' }]}
              >
                <Input.Password
                  size="large"
                  prefix={<LockOutlined />}
                  placeholder="请输入密码"
                  autoComplete="current-password"
                />
              </Form.Item>

              <Button
                className="rtdwh-login-submit"
                type="primary"
                htmlType="submit"
                size="large"
                loading={submitting}
                block
              >
                登录平台 <ArrowRightOutlined />
              </Button>
            </Form>

            <div className="rtdwh-login-hint">
              <SafetyCertificateOutlined />
              <span>首次部署请通过 <code>INIT_ADMIN_PASSWORD</code> 初始化管理员账号</span>
            </div>
          </div>

          <div className="rtdwh-login-copyright">RT-DWH Management Platform · v1.0</div>
        </section>
      </div>
    </div>
  );
};

export default Login;
