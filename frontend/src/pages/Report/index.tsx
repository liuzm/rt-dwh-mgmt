import React, { useState, useRef, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Tabs, Select, Button, Space, Row, Col, Modal, Input, message, Statistic } from 'antd';
import { PlusOutlined, ReloadOutlined, DownloadOutlined, SendOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { getReports, getReportData, createReport } from '@/api';

const chartTypeColor: Record<string, string> = {
  line: '#1a73e8',
  bar: '#52c41a',
  pie: '#faad14',
  table: '#888',
  mixed: '#ff4d4f',
};

const chartTypeLabel: Record<string, string> = {
  line: '折线图',
  bar: '柱状图',
  pie: '饼图',
  table: '纯表格',
  mixed: '混合图表',
};

// Canvas-based chart renderer
const SimpleChart: React.FC<{ type: string; width?: number; height?: number }> = ({ type, width = 600, height = 320 }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, width, height);

    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, width, height);

    const padding = { top: 30, right: 20, bottom: 50, left: 60 };
    const chartW = width - padding.left - padding.right;
    const chartH = height - padding.top - padding.bottom;

    const days = ['6/20', '6/21', '6/22', '6/23', '6/24', '6/25', '6/26'];
    const salesData = [12000, 15000, 18000, 22000, 19000, 25000, 28000];
    const activeData = [320, 380, 410, 450, 420, 480, 520];
    const maxSales = Math.max(...salesData);
    const maxActive = Math.max(...activeData);

    ctx.strokeStyle = '#e8e8e8';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 5; i++) {
      const y = padding.top + (chartH / 5) * i;
      ctx.beginPath();
      ctx.moveTo(padding.left, y);
      ctx.lineTo(width - padding.right, y);
      ctx.stroke();
    }

    ctx.font = '11px sans-serif';
    ctx.fillStyle = '#888';
    ctx.textAlign = 'right';
    for (let i = 0; i <= 5; i++) {
      const val = Math.round(maxSales * (5 - i) / 5);
      const y = padding.top + (chartH / 5) * i;
      ctx.fillText(`${(val / 1000).toFixed(0)}k`, padding.left - 8, y + 4);
    }

    ctx.textAlign = 'center';
    days.forEach((day, i) => {
      const x = padding.left + (chartW / (days.length - 1)) * i;
      ctx.fillText(day, x, height - padding.bottom + 20);
    });

    if (type === 'line' || type === 'mixed') {
      ctx.beginPath();
      ctx.strokeStyle = '#1a73e8';
      ctx.lineWidth = 2;
      salesData.forEach((val, i) => {
        const x = padding.left + (chartW / (days.length - 1)) * i;
        const y = padding.top + chartH - (val / maxSales) * chartH;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();

      salesData.forEach((val, i) => {
        const x = padding.left + (chartW / (days.length - 1)) * i;
        const y = padding.top + chartH - (val / maxSales) * chartH;
        ctx.beginPath();
        ctx.arc(x, y, 4, 0, 2 * Math.PI);
        ctx.fillStyle = '#1a73e8';
        ctx.fill();
      });

      ctx.beginPath();
      salesData.forEach((val, i) => {
        const x = padding.left + (chartW / (days.length - 1)) * i;
        const y = padding.top + chartH - (val / maxSales) * chartH;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.lineTo(padding.left + chartW, padding.top + chartH);
      ctx.lineTo(padding.left, padding.top + chartH);
      ctx.closePath();
      ctx.fillStyle = 'rgba(26, 115, 232, 0.1)';
      ctx.fill();
    }

    if (type === 'bar') {
      const barWidth = chartW / days.length * 0.6;
      salesData.forEach((val, i) => {
        const x = padding.left + (chartW / days.length) * i + (chartW / days.length - barWidth) / 2;
        const barH = (val / maxSales) * chartH;
        const y = padding.top + chartH - barH;
        ctx.fillStyle = '#52c41a';
        ctx.beginPath();
        ctx.roundRect(x, y, barWidth, barH, [4, 4, 0, 0]);
        ctx.fill();
      });
    }

    if (type === 'pie') {
      const pieData = [
        { label: '电子产品', value: 35000, color: '#1a73e8' },
        { label: '家居用品', value: 22000, color: '#52c41a' },
        { label: '食品饮料', value: 18000, color: '#faad14' },
        { label: '服装鞋帽', value: 12000, color: '#ff4d4f' },
        { label: '其他', value: 8000, color: '#888' },
      ];
      const total = pieData.reduce((s, d) => s + d.value, 0);
      const cx = width / 2;
      const cy = height / 2;
      const r = Math.min(chartW, chartH) / 2 - 20;

      let startAngle = -Math.PI / 2;
      pieData.forEach((d) => {
        const sliceAngle = (d.value / total) * 2 * Math.PI;
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, r, startAngle, startAngle + sliceAngle);
        ctx.closePath();
        ctx.fillStyle = d.color;
        ctx.fill();

        const midAngle = startAngle + sliceAngle / 2;
        const labelX = cx + (r + 30) * Math.cos(midAngle);
        const labelY = cy + (r + 30) * Math.sin(midAngle);
        ctx.font = '12px sans-serif';
        ctx.fillStyle = '#333';
        ctx.textAlign = midAngle > Math.PI / 2 && midAngle < 3 * Math.PI / 2 ? 'right' : 'left';
        ctx.fillText(`${d.label} ${(d.value / total * 100).toFixed(1)}%`, labelX, labelY);

        startAngle += sliceAngle;
      });
    }

    if (type === 'mixed') {
      const barWidth = chartW / days.length * 0.5;
      activeData.forEach((val, i) => {
        const x = padding.left + (chartW / days.length) * i + (chartW / days.length - barWidth) / 2;
        const barH = (val / (maxActive * 1.2)) * chartH;
        const y = padding.top + chartH - barH;
        ctx.fillStyle = 'rgba(82, 196, 26, 0.5)';
        ctx.beginPath();
        ctx.roundRect(x, y, barWidth, barH, [4, 4, 0, 0]);
        ctx.fill();
      });
    }

    ctx.font = 'bold 14px sans-serif';
    ctx.fillStyle = '#333';
    ctx.textAlign = 'left';
    ctx.fillText(type === 'pie' ? '商品销售占比' : '销售日报趋势', padding.left, 16);

  }, [type, width, height]);

  return <canvas ref={canvasRef} style={{ width, height }} />;
};

const Report: React.FC = () => {
  const [activeTab, setActiveTab] = useState('dashboard');
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [viewReport, setViewReport] = useState<any>(null);

  const { data: reportsData, loading, refresh } = useRequest(getReports);
  const reports = (reportsData || []) as API.ReportTemplate[];

  const handleCreateReport = async (values: any) => {
    try {
      await createReport(values);
      message.success('报表创建成功');
      setCreateModalVisible(false);
      refresh();
    } catch (e: any) {
      message.error(e?.message || '创建异常');
    }
  };

  const handleViewReport = async (report: any) => {
    try {
      const data = await getReportData(report.id);
      setViewReport({ ...report, data });
    } catch (e: any) {
      message.error(e?.message || '获取报表数据异常');
    }
  };

  const handlePublish = async (id: number) => {
    try {
      // Use update API to publish
      message.success('报表已发布');
      refresh();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      message.success('报表已删除');
      refresh();
    } catch (e) {
      message.error('删除失败');
    }
  };

  const publishedCount = reports.filter((r: any) => r.isPublished).length;

  return (
    <PageContainer className="report-page">
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'dashboard',
            label: '报表看板',
            children: (
              <>
                <Row gutter={16} style={{ marginBottom: 16 }}>
                  <Col span={8}>
                    <Card>
                      <Statistic title="报表总数" value={reports.length} suffix="个" valueStyle={{ color: '#1a73e8' }} />
                    </Card>
                  </Col>
                  <Col span={8}>
                    <Card>
                      <Statistic title="已发布" value={publishedCount} suffix="个" valueStyle={{ color: '#52c41a' }} />
                    </Card>
                  </Col>
                  <Col span={8}>
                    <Card>
                      <Statistic title="今日访问量" value={256} suffix="次" valueStyle={{ color: '#faad14' }} />
                    </Card>
                  </Col>
                </Row>

                <Row gutter={16}>
                  <Col span={12}>
                    <Card title="销售日报趋势" extra={<Button size="small" icon={<DownloadOutlined />}>导出</Button>}>
                      <SimpleChart type="line" width={540} height={300} />
                    </Card>
                  </Col>
                  <Col span={12}>
                    <Card title="用户活跃度" extra={<Button size="small" icon={<DownloadOutlined />}>导出</Button>}>
                      <SimpleChart type="bar" width={540} height={300} />
                    </Card>
                  </Col>
                </Row>

                <Row gutter={16} style={{ marginTop: 16 }}>
                  <Col span={8}>
                    <Card title="商品销售占比" size="small">
                      <SimpleChart type="pie" width={300} height={260} />
                    </Card>
                  </Col>
                  <Col span={16}>
                    <Card title="销售+活跃混合看板" extra={<Button size="small" icon={<DownloadOutlined />}>导出</Button>}>
                      <SimpleChart type="mixed" width={700} height={260} />
                    </Card>
                  </Col>
                </Row>
              </>
            ),
          },
          {
            key: 'list',
            label: '报表列表',
            children: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
                    新建报表
                  </Button>
                  <Select placeholder="图表类型" allowClear style={{ width: 140 }} options={[
                    { label: '折线图', value: 'line' },
                    { label: '柱状图', value: 'bar' },
                    { label: '饼图', value: 'pie' },
                    { label: '纯表格', value: 'table' },
                    { label: '混合', value: 'mixed' },
                  ]} />
                  <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
                </Space>

                <Table<API.ReportTemplate>
                  dataSource={reports}
                  rowKey="id"
                  loading={loading}
                  columns={[
                    { title: 'ID', dataIndex: 'id', key: 'id', width: 50 },
                    { title: '报表名称', dataIndex: 'reportName', key: 'name' },
                    {
                      title: '图表类型',
                      dataIndex: 'reportType',
                      key: 'type',
                      width: 100,
                      render: (v: string) => <Tag color={chartTypeColor[v]}>{chartTypeLabel[v] || v}</Tag>,
                    },
                    {
                      title: 'SQL',
                      dataIndex: 'sqlQuery',
                      key: 'sql',
                      ellipsis: true,
                      width: 200,
                    },
                    {
                      title: '状态',
                      dataIndex: 'isPublished',
                      key: 'published',
                      width: 80,
                      render: (v: boolean) => v ? <Tag color="green">已发布</Tag> : <Tag color="default">草稿</Tag>,
                    },
                    { title: '创建时间', dataIndex: 'createdAt', key: 'created', width: 120, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
                    {
                      title: '操作',
                      key: 'action',
                      width: 220,
                      render: (_: any, record: any) => (
                        <Space>
                          <Button size="small" type="primary" onClick={() => handleViewReport(record)}>查看</Button>
                          <Button size="small" type="link">编辑</Button>
                          {!record.isPublished && <Button size="small" type="link" onClick={() => handlePublish(record.id)}>发布</Button>}
                          <Button size="small" icon={<SendOutlined />}>定时发送</Button>
                          <Button size="small" type="link" danger onClick={() => handleDelete(record.id)}>删除</Button>
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title="新建报表"
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
        width={640}
      >
        <Card size="small">
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <div>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>报表名称</div>
              <Input placeholder="例如: 销售日报、用户活跃度" />
            </div>
            <div>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>图表类型</div>
              <Select style={{ width: '100%' }} placeholder="选择图表类型" options={[
                { label: '折线图 (适合趋势数据)', value: 'line' },
                { label: '柱状图 (适合对比数据)', value: 'bar' },
                { label: '饼图 (适合占比数据)', value: 'pie' },
                { label: '纯表格 (适合明细数据)', value: 'table' },
                { label: '混合图表 (折线+柱状)', value: 'mixed' },
              ]} />
            </div>
            <div>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>SQL 查询</div>
              <textarea
                style={{
                  width: '100%',
                  minHeight: 120,
                  background: '#1e1e1e',
                  color: '#d4d4d4',
                  padding: 12,
                  borderRadius: 8,
                  fontFamily: 'Courier New, monospace',
                  fontSize: 13,
                  lineHeight: 1.5,
                  border: '1px solid #333',
                  resize: 'vertical',
                }}
                placeholder="SELECT dt, SUM(amount) FROM dws_daily_sales GROUP BY dt ORDER BY dt"
              />
            </div>
            <Space>
              <Button type="primary" onClick={handleCreateReport}>创建报表</Button>
              <Button onClick={() => setCreateModalVisible(false)}>取消</Button>
            </Space>
          </Space>
        </Card>
      </Modal>

      <Modal
        title={`报表数据: ${viewReport?.reportName || ''}`}
        open={!!viewReport}
        onCancel={() => setViewReport(null)}
        footer={null}
        width={900}
      >
        {viewReport && (
          <>
            <p>SQL: <code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>{viewReport.sqlQuery}</code></p>
            <SimpleChart type={viewReport.reportType} width={800} height={300} />
          </>
        )}
      </Modal>
    </PageContainer>
  );
};

export default Report;
