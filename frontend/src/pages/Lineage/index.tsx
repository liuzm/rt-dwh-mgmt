import React, { useState, useRef, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Select, Button, Space, Tag, Tooltip, Drawer, Descriptions, message } from 'antd';
import { ZoomInOutlined, ZoomOutOutlined, ReloadOutlined, FullscreenOutlined } from '@ant-design/icons';
import { getSyncTasks, getDwhTables } from '@/api';
import { useRequest } from '@umijs/max';

/* ─── DAG Canvas ─── */
interface DAGNode {
  id: string;
  label: string;
  type: 'source' | 'task' | 'table';
  layer?: string;
  x: number;
  y: number;
}

interface DAGEdge {
  from: string;
  to: string;
  label?: string;
}

const layerColors: Record<string, { bg: string; border: string; text: string }> = {
  source: { bg: '#e3f2fd', border: '#1565c0', text: '#1565c0' },
  ods: { bg: '#e8f5e9', border: '#2e7d32', text: '#2e7d32' },
  dwd: { bg: '#fff8e1', border: '#f57f17', text: '#f57f17' },
  dws: { bg: '#fce4ec', border: '#c62828', text: '#c62828' },
  ads: { bg: '#f3e5f5', border: '#6a1b9a', text: '#6a1b9a' },
  task: { bg: '#e0f2f1', border: '#00695c', text: '#00695c' },
};

const Lineage: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [scale, setScale] = useState(1);
  const [selectedNode, setSelectedNode] = useState<DAGNode | null>(null);
  const [layerFilter, setLayerFilter] = useState<string | undefined>();
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });

  const { data: tasksData } = useRequest(getSyncTasks);
  const { data: tablesData } = useRequest(getDwhTables);

  const tasks = (tasksData || []) as API.SyncTask[];
  const tables = (tablesData || []) as API.DwhTableMeta[];

  // Build DAG from real data
  const nodes: DAGNode[] = [];
  const edges: DAGEdge[] = [];

  // Source tables (first column)
  const sourceX = 60;
  let sourceIdx = 0;
  tables.forEach((t: any) => {
    const db = t.database || t.paimonDb || '';
    const tbl = t.tableName || t.paimonTable || '';
    if (!db || !tbl) return;
    const id = `src_${db}_${tbl}`;
    const y = 80 + sourceIdx * 140;
    nodes.push({ id, label: `${db}·${tbl}`, type: 'source', x: sourceX, y });
    sourceIdx++;
  });

  // CDC Tasks (second column)
  const taskX = 320;
  let taskIdx = 0;
  tasks.forEach((t: any) => {
    const name = t.name || t.taskName || `Task ${t.id}`;
    const id = `task_${t.id}`;
    const y = 80 + taskIdx * 140;
    nodes.push({ id, label: name, type: 'task', x: taskX, y });
    // Connect first source to first task, etc.
    if (sourceIdx > 0 && taskIdx < sourceIdx) {
      edges.push({ from: `src_${tables[taskIdx]?.database || tables[taskIdx]?.paimonDb || ''}_${tables[taskIdx]?.tableName || tables[taskIdx]?.paimonTable || ''}`, to: id, label: 'CDC' });
    }
    taskIdx++;
  });

  // Target tables by layer (columns 3+)
  const layerOrder = ['ods', 'dwd', 'dws', 'ads'];
  const layerStartX = 580;
  layerOrder.forEach((layer, colIdx) => {
    const x = layerStartX + colIdx * 260;
    const layerTables = tables.filter((t: any) => t.layer === layer);
    layerTables.forEach((t: any, rowIdx: number) => {
      const db = t.database || t.paimonDb || '';
      const tbl = t.tableName || t.paimonTable || '';
      if (!db || !tbl) return;
      const id = `tbl_${layer}_${tbl}`;
      const y = 80 + rowIdx * 140;
      nodes.push({ id, label: `${db}.${tbl}`, type: 'table', layer, x, y });

      // Connect to previous layer or tasks
      if (colIdx === 0) {
        // ODS connects to tasks
        if (taskIdx > 0) {
          edges.push({ from: nodes.find((n) => n.type === 'task')?.id || '', to: id });
        }
      } else {
        // Connect to previous layer tables
        const prevLayer = layerOrder[colIdx - 1];
        const prevTables = tables.filter((pt: any) => pt.layer === prevLayer);
        if (prevTables.length > 0) {
          const prevId = `tbl_${prevLayer}_${prevTables[0].tableName || prevTables[0].paimonTable || ''}`;
          edges.push({ from: prevId, to: id });
        }
      }
    });
  });

  // Fallback: if no real data, show demo nodes
  const hasRealData = nodes.length > 0;
  const displayNodes = hasRealData ? nodes : ([
    { id: 'src_mysql_orders', label: 'MySQL · orders', type: 'source', x: 60, y: 80 },
    { id: 'src_mysql_users', label: 'MySQL · users', type: 'source', x: 60, y: 220 },
    { id: 'src_mysql_products', label: 'MySQL · products', type: 'source', x: 60, y: 360 },
    { id: 'task_cdc_orders', label: 'CDC: orders', type: 'task', x: 320, y: 120 },
    { id: 'task_cdc_users', label: 'CDC: users', type: 'task', x: 320, y: 280 },
    { id: 'task_cdc_products', label: 'CDC: products', type: 'task', x: 320, y: 400 },
    { id: 'ods_orders', label: 'ods_orders', type: 'table', layer: 'ods', x: 580, y: 80 },
    { id: 'ods_users', label: 'ods_users', type: 'table', layer: 'ods', x: 580, y: 220 },
    { id: 'ods_products', label: 'ods_products', type: 'table', layer: 'ods', x: 580, y: 360 },
    { id: 'task_etl_dwd', label: 'ETL: DWD', type: 'task', x: 840, y: 220 },
    { id: 'dwd_order_detail', label: 'dwd_order_detail', type: 'table', layer: 'dwd', x: 1100, y: 160 },
    { id: 'dwd_user_profile', label: 'dwd_user_profile', type: 'table', layer: 'dwd', x: 1100, y: 300 },
    { id: 'task_dws_agg', label: 'ETL: DWS', type: 'task', x: 1360, y: 230 },
    { id: 'dws_daily_sales', label: 'dws_daily_sales', type: 'table', layer: 'dws', x: 1620, y: 150 },
    { id: 'dws_user_active', label: 'dws_user_active', type: 'table', layer: 'dws', x: 1620, y: 310 },
  ] as DAGNode[]);

  const displayEdges = hasRealData ? edges : ([
    { from: 'src_mysql_orders', to: 'task_cdc_orders', label: 'CDC' },
    { from: 'src_mysql_users', to: 'task_cdc_users', label: 'CDC' },
    { from: 'src_mysql_products', to: 'task_cdc_products', label: 'CDC' },
    { from: 'task_cdc_orders', to: 'ods_orders' },
    { from: 'task_cdc_users', to: 'ods_users' },
    { from: 'task_cdc_products', to: 'ods_products' },
    { from: 'ods_orders', to: 'task_etl_dwd' },
    { from: 'ods_users', to: 'task_etl_dwd' },
    { from: 'ods_products', to: 'task_etl_dwd' },
    { from: 'task_etl_dwd', to: 'dwd_order_detail' },
    { from: 'task_etl_dwd', to: 'dwd_user_profile' },
    { from: 'dwd_order_detail', to: 'task_dws_agg' },
    { from: 'dwd_user_profile', to: 'task_dws_agg' },
    { from: 'task_dws_agg', to: 'dws_daily_sales' },
    { from: 'task_dws_agg', to: 'dws_user_active' },
    { from: 'dws_daily_sales', to: 'ads_sales_report' },
    { from: 'dws_user_active', to: 'ads_user_retention' },
  ] as DAGEdge[]);

  const filteredNodes = layerFilter
    ? displayNodes.filter((n) => n.layer === layerFilter || n.type === 'source' || n.type === 'task')
    : displayNodes;

  const filteredEdges = displayEdges.filter(
    (e) => filteredNodes.find((n) => n.id === e.from) && filteredNodes.find((n) => n.id === e.to),
  );

  const getNodeColorKey = (node: DAGNode) => {
    if (node.type === 'source') return 'source';
    if (node.type === 'task') return 'task';
    return node.layer || 'ods';
  };

  // Canvas drawing
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    canvas.width = canvas.offsetWidth * dpr;
    canvas.height = canvas.offsetHeight * dpr;
    ctx.scale(dpr * scale, dpr * scale);
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    ctx.save();
    ctx.translate(offset.x, offset.y);

    // Draw edges
    filteredEdges.forEach((edge) => {
      const fromNode = filteredNodes.find((n) => n.id === edge.from);
      const toNode = filteredNodes.find((n) => n.id === edge.to);
      if (!fromNode || !toNode) return;

      ctx.beginPath();
      ctx.moveTo(fromNode.x + 80, fromNode.y + 20);
      ctx.lineTo(toNode.x, toNode.y + 20);
      ctx.strokeStyle = '#aaa';
      ctx.lineWidth = 1.5;
      ctx.stroke();

      const angle = Math.atan2(toNode.y + 20 - (fromNode.y + 20), toNode.x - (fromNode.x + 80));
      const arrowLen = 10;
      const tipX = toNode.x;
      const tipY = toNode.y + 20;
      ctx.beginPath();
      ctx.moveTo(tipX, tipY);
      ctx.lineTo(tipX - arrowLen * Math.cos(angle - 0.3), tipY - arrowLen * Math.sin(angle - 0.3));
      ctx.lineTo(tipX - arrowLen * Math.cos(angle + 0.3), tipY - arrowLen * Math.sin(angle + 0.3));
      ctx.closePath();
      ctx.fillStyle = '#aaa';
      ctx.fill();

      if (edge.label) {
        const midX = (fromNode.x + 80 + toNode.x) / 2;
        const midY = (fromNode.y + 20 + toNode.y + 20) / 2;
        ctx.font = '11px sans-serif';
        ctx.fillStyle = '#888';
        ctx.textAlign = 'center';
        ctx.fillText(edge.label, midX, midY - 6);
      }
    });

    // Draw nodes
    filteredNodes.forEach((node) => {
      const colors = layerColors[getNodeColorKey(node)];
      const w = 160;
      const h = 40;

      ctx.shadowColor = 'rgba(0,0,0,0.08)';
      ctx.shadowBlur = 6;
      ctx.shadowOffsetY = 2;

      ctx.beginPath();
      ctx.roundRect(node.x, node.y, w, h, 8);
      ctx.fillStyle = colors.bg;
      ctx.fill();
      ctx.strokeStyle = colors.border;
      ctx.lineWidth = 2;
      ctx.stroke();

      ctx.shadowColor = 'transparent';

      ctx.font = 'bold 13px sans-serif';
      ctx.fillStyle = colors.text;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(node.label, node.x + w / 2, node.y + h / 2, w - 10);
    });

    ctx.restore();
  }, [filteredNodes, filteredEdges, scale, offset]);

  const handleCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const clickX = (e.clientX - rect.left) / scale - offset.x;
    const clickY = (e.clientY - rect.top) / scale - offset.y;

    const clickedNode = filteredNodes.find(
      (n) => clickX >= n.x && clickX <= n.x + 160 && clickY >= n.y && clickY <= n.y + 40,
    );
    setSelectedNode(clickedNode || null);
  };

  const handleMouseDown = (e: React.MouseEvent) => {
    setDragging(true);
    setDragStart({ x: e.clientX - offset.x, y: e.clientY - offset.y });
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!dragging) return;
    setOffset({ x: e.clientX - dragStart.x, y: e.clientY - dragStart.y });
  };

  const handleMouseUp = () => setDragging(false);

  const upstreamNodes = selectedNode
    ? filteredEdges.filter((e) => e.to === selectedNode.id).map((e) => filteredNodes.find((n) => n.id === e.from)).filter(Boolean)
    : [];

  const downstreamNodes = selectedNode
    ? filteredEdges.filter((e) => e.from === selectedNode.id).map((e) => filteredNodes.find((n) => n.id === e.to)).filter(Boolean)
    : [];

  return (
    <PageContainer>
      <Card>
        <Space style={{ marginBottom: 16 }}>
          <Select
            placeholder="筛选分层"
            allowClear
            onChange={setLayerFilter}
            style={{ width: 140 }}
            options={[
              { label: 'ODS 原始层', value: 'ods' },
              { label: 'DWD 明细层', value: 'dwd' },
              { label: 'DWS 汇总层', value: 'dws' },
              { label: 'ADS 应用层', value: 'ads' },
            ]}
          />
          <Button icon={<ZoomInOutlined />} onClick={() => setScale((s) => Math.min(s + 0.2, 3))}>
            放大
          </Button>
          <Button icon={<ZoomOutOutlined />} onClick={() => setScale((s) => Math.max(s - 0.2, 0.3))}>
            缩小
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => { setScale(1); setOffset({ x: 0, y: 0 }); }}>
            重置视图
          </Button>
          <Button
            icon={<FullscreenOutlined />}
            onClick={() => {
              const canvas = canvasRef.current;
              if (canvas) canvas.requestFullscreen?.();
            }}
          >
            全屏
          </Button>
          <span style={{ color: '#888', fontSize: 12 }}>
            拖拽平移 · 点击节点查看血缘详情 · 当前缩放: {scale.toFixed(1)}x · {displayNodes.length} 个节点
          </span>
        </Space>

        <canvas
          ref={canvasRef}
          onClick={handleCanvasClick}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUp}
          onMouseLeave={() => setDragging(false)}
          style={{
            width: '100%',
            height: 580,
            background: '#fafafa',
            borderRadius: 8,
            border: '1px solid #e8e8e8',
            cursor: dragging ? 'grabbing' : 'grab',
          }}
        />

        <div style={{ marginTop: 12, display: 'flex', gap: 16 }}>
          {Object.entries(layerColors).map(([key, colors]) => (
            <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <div style={{ width: 16, height: 16, borderRadius: 4, background: colors.bg, border: `2px solid ${colors.border}` }} />
              <span style={{ fontSize: 12, color: colors.text }}>
                {key === 'source' ? '源库' : key === 'task' ? '同步任务' : key.toUpperCase()}
              </span>
            </div>
          ))}
        </div>
      </Card>

      <Drawer
        title={selectedNode ? `血缘详情: ${selectedNode.label}` : ''}
        open={!!selectedNode}
        onClose={() => setSelectedNode(null)}
        width={400}
      >
        {selectedNode && (
          <>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="节点 ID">{selectedNode.id}</Descriptions.Item>
              <Descriptions.Item label="类型">
                {selectedNode.type === 'source' ? '源数据库' : selectedNode.type === 'task' ? '同步任务' : '数仓表'}
              </Descriptions.Item>
              {selectedNode.layer && (
                <Descriptions.Item label="分层">
                  <Tag color={{ ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red' }[selectedNode.layer]}>
                    {selectedNode.layer.toUpperCase()}
                  </Tag>
                </Descriptions.Item>
              )}
            </Descriptions>

            <div style={{ marginTop: 16 }}>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>上游依赖 ({upstreamNodes.length})</div>
              {upstreamNodes.length === 0 ? (
                <div style={{ color: '#888' }}>无上游依赖（根节点）</div>
              ) : (
                upstreamNodes.map((n: DAGNode | undefined) => n && (
                  <Tag key={n.id} color={layerColors[getNodeColorKey(n)].border} style={{ marginBottom: 4 }}>
                    {n.label}
                  </Tag>
                ))
              )}
            </div>

            <div style={{ marginTop: 16 }}>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>下游产出 ({downstreamNodes.length})</div>
              {downstreamNodes.length === 0 ? (
                <div style={{ color: '#888' }}>无下游产出（叶子节点）</div>
              ) : (
                downstreamNodes.map((n: DAGNode | undefined) => n && (
                  <Tag key={n.id} color={layerColors[getNodeColorKey(n)].border} style={{ marginBottom: 4 }}>
                    {n.label}
                  </Tag>
                ))
              )}
            </div>
          </>
        )}
      </Drawer>
    </PageContainer>
  );
};

export default Lineage;
