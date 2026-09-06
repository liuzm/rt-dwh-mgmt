import React, { useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Descriptions, Drawer, Empty, Input, Select, Space, Spin, Tag } from 'antd';
import { FullscreenOutlined, ReloadOutlined, SearchOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { getLineageGraph } from '@/api';

type CanvasNode = API.LineageNode & { x: number; y: number };

const nodeColors: Record<string, { background: string; border: string; text: string }> = {
  datasource: { background: '#e6f4ff', border: '#1677ff', text: '#0958d9' },
  source_table: { background: '#f0f5ff', border: '#597ef7', text: '#1d39c4' },
  task: { background: '#e6fffb', border: '#13c2c2', text: '#006d75' },
  ods: { background: '#f6ffed', border: '#52c41a', text: '#237804' },
  dwd: { background: '#fffbe6', border: '#faad14', text: '#ad6800' },
  dws: { background: '#fff2e8', border: '#fa541c', text: '#ad2102' },
  ads: { background: '#fff0f6', border: '#eb2f96', text: '#9e1068' },
  other: { background: '#fafafa', border: '#8c8c8c', text: '#434343' },
};

const nodeColorKey = (node: API.LineageNode) => node.type === 'table' ? (node.layer || 'other') : node.type;

const Lineage: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [layer, setLayer] = useState<string>();
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<CanvasNode>();
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });

  const { data, loading, refresh } = useRequest(
    () => getLineageGraph({ layer, keyword: keyword || undefined }),
    { refreshDeps: [layer, keyword] },
  );
  const graph = data as API.LineageGraph | undefined;

  const nodes = useMemo<CanvasNode[]>(() => {
    const order = ['datasource', 'source_table', 'task', 'ods', 'dwd', 'dws', 'ads', 'other'];
    const counters: Record<string, number> = {};
    return (graph?.nodes || []).map((node) => {
      const column = node.type === 'table' ? (node.layer || 'other') : node.type;
      const row = counters[column] || 0;
      counters[column] = row + 1;
      return { ...node, x: 40 + Math.max(0, order.indexOf(column)) * 230, y: 70 + row * 100 };
    });
  }, [graph]);

  const edges = graph?.edges || [];
  const nodeMap = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const context = canvas.getContext('2d');
    if (!context) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = canvas.offsetWidth * ratio;
    canvas.height = canvas.offsetHeight * ratio;
    context.setTransform(ratio * scale, 0, 0, ratio * scale, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.save();
    context.translate(offset.x, offset.y);

    edges.forEach((edge) => {
      const source = nodeMap.get(edge.source);
      const target = nodeMap.get(edge.target);
      if (!source || !target) return;
      const startX = source.x + 174;
      const startY = source.y + 26;
      const endX = target.x;
      const endY = target.y + 26;
      const control = Math.max(40, (endX - startX) / 2);
      context.beginPath();
      context.moveTo(startX, startY);
      context.bezierCurveTo(startX + control, startY, endX - control, endY, endX, endY);
      context.strokeStyle = '#91a3b0';
      context.lineWidth = 1.5;
      context.stroke();
      context.beginPath();
      context.moveTo(endX, endY);
      context.lineTo(endX - 9, endY - 5);
      context.lineTo(endX - 9, endY + 5);
      context.closePath();
      context.fillStyle = '#91a3b0';
      context.fill();
      if (edge.label) {
        context.font = '11px sans-serif';
        context.fillStyle = '#667085';
        context.textAlign = 'center';
        context.fillText(edge.label, (startX + endX) / 2, (startY + endY) / 2 - 7);
      }
    });

    nodes.forEach((node) => {
      const colors = nodeColors[nodeColorKey(node)] || nodeColors.other;
      context.beginPath();
      context.roundRect(node.x, node.y, 174, 52, 8);
      context.fillStyle = colors.background;
      context.fill();
      context.strokeStyle = selected?.id === node.id ? '#262626' : colors.border;
      context.lineWidth = selected?.id === node.id ? 2.5 : 1.5;
      context.stroke();
      context.fillStyle = colors.text;
      context.font = '600 13px sans-serif';
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.fillText(node.name, node.x + 87, node.y + 20, 158);
      context.fillStyle = '#8c8c8c';
      context.font = '11px sans-serif';
      context.fillText(node.type === 'table' ? (node.layer || 'TABLE').toUpperCase() : node.type.toUpperCase(),
        node.x + 87, node.y + 38, 158);
    });
    context.restore();
  }, [edges, nodeMap, nodes, offset, scale, selected]);

  const related = (direction: 'upstream' | 'downstream') => {
    if (!selected) return [];
    return edges
      .filter((edge) => direction === 'upstream' ? edge.target === selected.id : edge.source === selected.id)
      .map((edge) => nodeMap.get(direction === 'upstream' ? edge.source : edge.target))
      .filter(Boolean) as CanvasNode[];
  };

  const resetView = () => { setScale(1); setOffset({ x: 0, y: 0 }); setSelected(undefined); };

  return (
    <PageContainer title="数据血缘" subTitle="基于数据源、任务映射和 SQL 解析生成的真实表级血缘">
      <Card>
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search value={keywordInput} onChange={(event) => setKeywordInput(event.target.value)}
            onSearch={setKeyword} allowClear placeholder="搜索表、任务或数据源" style={{ width: 260 }}
            enterButton={<SearchOutlined />} />
          <Select value={layer} placeholder="筛选分层" allowClear onChange={setLayer} style={{ width: 140 }}
            options={['ods', 'dwd', 'dws', 'ads'].map((value) => ({ label: value.toUpperCase(), value }))} />
          <Button icon={<ZoomInOutlined />} onClick={() => setScale((value) => Math.min(2.5, value + 0.2))}>放大</Button>
          <Button icon={<ZoomOutOutlined />} onClick={() => setScale((value) => Math.max(0.4, value - 0.2))}>缩小</Button>
          <Button icon={<ReloadOutlined />} onClick={() => { resetView(); refresh(); }}>刷新</Button>
          <Button icon={<FullscreenOutlined />} onClick={() => canvasRef.current?.requestFullscreen?.()}>全屏</Button>
          <Tag color="blue">{nodes.length} 节点</Tag><Tag>{edges.length} 关系</Tag>
        </Space>

        <Spin spinning={loading}>
          <div style={{ position: 'relative' }}>
            <canvas ref={canvasRef}
              onClick={(event) => {
                const bounds = event.currentTarget.getBoundingClientRect();
                const x = (event.clientX - bounds.left) / scale - offset.x;
                const y = (event.clientY - bounds.top) / scale - offset.y;
                setSelected(nodes.find((node) => x >= node.x && x <= node.x + 174 && y >= node.y && y <= node.y + 52));
              }}
              onMouseDown={(event) => { setDragging(true); setDragStart({ x: event.clientX - offset.x, y: event.clientY - offset.y }); }}
              onMouseMove={(event) => dragging && setOffset({ x: event.clientX - dragStart.x, y: event.clientY - dragStart.y })}
              onMouseUp={() => setDragging(false)} onMouseLeave={() => setDragging(false)}
              style={{ width: '100%', height: 620, background: '#fafbfc', border: '1px solid #e8e8e8',
                borderRadius: 8, cursor: dragging ? 'grabbing' : 'grab' }} />
            {!loading && nodes.length === 0 && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
              <Empty description="暂无真实血缘数据，请先创建同步任务或同步数仓元数据" />
            </div>}
          </div>
        </Spin>
      </Card>

      <Drawer title={selected ? `血缘详情：${selected.name}` : '血缘详情'} open={!!selected}
        onClose={() => setSelected(undefined)} width={460}>
        {selected && <>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="名称">{selected.qualifiedName || selected.name}</Descriptions.Item>
            <Descriptions.Item label="类型">{selected.type}</Descriptions.Item>
            <Descriptions.Item label="分层">{selected.layer?.toUpperCase() || '—'}</Descriptions.Item>
            <Descriptions.Item label="状态">{selected.status || '—'}</Descriptions.Item>
          </Descriptions>
          <Card size="small" title={`上游（${related('upstream').length}）`} style={{ marginTop: 16 }}>
            <Space wrap>{related('upstream').map((node) => <Tag key={node.id}>{node.name}</Tag>)}</Space>
          </Card>
          <Card size="small" title={`下游（${related('downstream').length}）`} style={{ marginTop: 12 }}>
            <Space wrap>{related('downstream').map((node) => <Tag key={node.id}>{node.name}</Tag>)}</Space>
          </Card>
          {selected.metadata && Object.keys(selected.metadata).length > 0 &&
            <Card size="small" title="元数据" style={{ marginTop: 12 }}><pre style={{ whiteSpace: 'pre-wrap' }}>
              {JSON.stringify(selected.metadata, null, 2)}
            </pre></Card>}
        </>}
      </Drawer>
    </PageContainer>
  );
};

export default Lineage;
