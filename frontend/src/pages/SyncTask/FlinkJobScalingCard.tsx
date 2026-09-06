import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ApartmentOutlined,
  ReloadOutlined,
  SlidersOutlined,
} from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import { getSyncTaskScaling, scaleSyncTask } from '@/api';
import './flink-job-scaling.less';

type ScaleFormValues = {
  targetParallelism: number;
  reason: string;
};

type Props = {
  taskId: number;
};

type AcceptedScale = {
  acceptedAt: string;
  targetParallelism: number;
};

const providerLabels: Record<string, string> = {
  none: 'Flink REST',
  standalone: 'Standalone 集群',
  external: '外部资源提供方',
  'kubernetes-native': 'Native Kubernetes',
  'flink-kubernetes-operator': 'Flink Kubernetes Operator',
};

const flinkStateColors: Record<string, string> = {
  RUNNING: 'success',
  RESTARTING: 'processing',
  RECONCILING: 'processing',
  INITIALIZING: 'processing',
  CREATED: 'default',
  FAILING: 'warning',
  FAILED: 'error',
  CANCELLING: 'warning',
  CANCELED: 'default',
  FINISHED: 'success',
  SUSPENDED: 'warning',
};

const formatTime = (value?: string) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '—';

const vertexKey = (vertex: API.FlinkJobScalingVertex, index?: number) =>
  vertex.vertexId || vertex.id || `${vertex.name}-${index ?? 0}`;

const FlinkJobScalingCard: React.FC<Props> = ({ taskId }) => {
  const access = useAccess();
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [acceptedScale, setAcceptedScale] = useState<AcceptedScale>();
  const [form] = Form.useForm<ScaleFormValues>();
  const targetParallelism = Form.useWatch('targetParallelism', form);
  const request = useRequest(() => getSyncTaskScaling(taskId), {
    refreshDeps: [taskId],
    pollingInterval: 10000,
    pollingWhenHidden: false,
  });
  const info = request.data as API.FlinkJobScalingInfo | undefined;
  const vertexParallelisms = (info?.vertices || []).map((vertex) => vertex.currentParallelism);
  const currentMin = vertexParallelisms.length ? Math.min(...vertexParallelisms) : info?.currentParallelism;
  const currentMax = vertexParallelisms.length ? Math.max(...vertexParallelisms) : info?.currentParallelism;
  const currentParallelism = info?.currentParallelism ?? info?.configuredParallelism ?? 0;
  const currentDisplay = currentMin != null && currentMax != null && currentMin !== currentMax
    ? `${currentMin} - ${currentMax}`
    : info?.currentParallelism ?? currentMin ?? '—';
  const minTarget = Math.max(1, info?.minTargetParallelism ?? 1);
  const maxTarget = Math.max(minTarget, info?.maxTargetParallelism ?? minTarget);
  const direction = targetParallelism == null
    ? 'same'
    : currentMin != null && currentMax != null && currentMin !== currentMax
      ? targetParallelism < currentMin ? 'down' : targetParallelism > currentMax ? 'up' : 'mixed'
      : targetParallelism === currentParallelism ? 'same' : targetParallelism > currentParallelism ? 'up' : 'down';
  const providerLabel = providerLabels[info?.provider || 'none'] || info?.provider || '未知';
  const canScale = Boolean(info?.supported && info?.jobId && info?.flinkState === 'RUNNING');

  useEffect(() => setAcceptedScale(undefined), [taskId]);

  const requestedRange = useMemo(() => {
    if (info?.requestedLowerBound == null && info?.requestedUpperBound == null) return '未设置';
    if (info.requestedLowerBound === info.requestedUpperBound) return String(info.requestedLowerBound ?? '—');
    return `${info.requestedLowerBound ?? '—'} - ${info.requestedUpperBound ?? '—'}`;
  }, [info?.requestedLowerBound, info?.requestedUpperBound]);

  const openModal = () => {
    form.setFieldsValue({
      targetParallelism: currentParallelism || minTarget,
      reason: '',
    });
    setModalOpen(true);
  };

  const handleSubmit = async (values: ScaleFormValues) => {
    if (!info?.jobId) return;
    setSubmitting(true);
    try {
      const result = await scaleSyncTask(taskId, {
        targetParallelism: values.targetParallelism,
        expectedJobId: info.jobId,
        expectedConfiguredParallelism: info.configuredParallelism,
        reason: values.reason.trim(),
      });
      setAcceptedScale({
        acceptedAt: result.acceptedAt || new Date().toISOString(),
        targetParallelism: values.targetParallelism,
      });
      setModalOpen(false);
      message.success(
        ('message' in result && result.message)
        || `并行度调整已受理，目标并行度 ${values.targetParallelism}`,
      );
      await request.refresh();
    } catch (error: any) {
      message.error(error?.message || '并行度调整提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  const capacity = info?.capacity;
  // Slot-sharing-group details are not exposed by this endpoint. Comparing
  // against the largest current Vertex gives a safe minimum only when scaling
  // above every Vertex; do not present a precise-looking sum of all operators.
  const minimumAdditionalSlots = targetParallelism == null || currentMax == null
    ? 0
    : Math.max(0, targetParallelism - currentMax);
  const capacityMayBeInsufficient = Boolean(
    capacity
    && minimumAdditionalSlots > capacity.slotsAvailable
    && !info?.autoExpansionSupported,
  );
  const acceptedAt = acceptedScale?.acceptedAt || info?.acceptedAt;
  const acceptedScaleConverged = Boolean(
    acceptedScale
    && (
      info?.vertices?.length
        ? info.vertices.every((vertex) => vertex.currentParallelism === acceptedScale.targetParallelism)
        : info?.currentParallelism === acceptedScale.targetParallelism
    ),
  );

  return (
    <>
      <Card
        className="flink-job-scaling-card"
        title={<Space><ApartmentOutlined />作业并行度调整</Space>}
        extra={(
          <Space>
            {info && (
              <Tag color={info.supported ? 'processing' : 'default'}>
                {info.supported ? '支持动态调整' : '当前不可调整'}
              </Tag>
            )}
            <Tooltip title="重新读取作业资源需求">
              <Button icon={<ReloadOutlined />} loading={request.loading} onClick={request.refresh}>刷新</Button>
            </Tooltip>
            {access.canManageTask ? (
              <Tooltip title={canScale ? '调整运行中作业的并行度' : info?.reason || '当前作业状态不支持动态调整'}>
                <span>
                  <Button
                    type="primary"
                    icon={<SlidersOutlined />}
                    disabled={!canScale}
                    onClick={openModal}
                  >
                    调整并行度
                  </Button>
                </span>
              </Tooltip>
            ) : <Tag>只读</Tag>}
          </Space>
        )}
        loading={request.loading && !info}
      >
        {request.error && !info ? (
          <Alert
            showIcon
            type="error"
            message="作业扩缩能力读取失败"
            description={request.error.message || '暂时无法读取 Flink 作业资源需求'}
            action={<Button size="small" onClick={request.refresh}>重试</Button>}
          />
        ) : info ? (
          <>
            {!info.supported && (
              <Alert
                className="flink-job-scaling-alert"
                showIcon
                type="warning"
                message="当前作业不支持在线调整并行度"
                description={info.reason || '需要运行中的单一 Flink Job，并启用 Adaptive Scheduler。'}
              />
            )}

            {info.supported && info.reason && (
              <Alert
                className="flink-job-scaling-alert"
                showIcon
                type="info"
                message="作业支持在线并行度调整"
                description={info.reason}
              />
            )}

            {acceptedAt && (
              <Alert
                className="flink-job-scaling-alert"
                showIcon
                type={acceptedScaleConverged ? 'success' : 'info'}
                message={acceptedScaleConverged ? '并行度调整已生效' : '并行度调整请求已受理'}
                description={acceptedScaleConverged
                  ? `目标并行度 ${acceptedScale?.targetParallelism} 已在全部算子生效。`
                  : `受理时间 ${formatTime(acceptedAt)}${acceptedScale ? `，目标并行度 ${acceptedScale.targetParallelism}` : ''}，正在等待 Flink 调度结果。`}
              />
            )}

            <Row gutter={[12, 12]}>
              <Col xs={12} lg={6}>
                <div className="flink-job-scaling-metric">
                  <Statistic title="配置并行度" value={info.configuredParallelism} />
                </div>
              </Col>
              <Col xs={12} lg={6}>
                <div className="flink-job-scaling-metric is-current">
                  <Statistic title="当前并行度" value={currentDisplay} />
                </div>
              </Col>
              <Col xs={12} lg={6}>
                <div className="flink-job-scaling-metric">
                  <Statistic title="已请求范围" value={requestedRange} />
                </div>
              </Col>
              <Col xs={12} lg={6}>
                <div className="flink-job-scaling-metric">
                  <Statistic title="允许目标范围" value={`${minTarget} - ${maxTarget}`} />
                </div>
              </Col>
            </Row>

            <Descriptions className="flink-job-scaling-summary" size="small" column={{ xs: 1, md: 2, xl: 4 }}>
              <Descriptions.Item label="Flink 状态">
                <Tag color={flinkStateColors[info.flinkState] || 'default'}>{info.flinkState || 'UNKNOWN'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="资源提供方">{providerLabel}</Descriptions.Item>
              <Descriptions.Item label="TaskManager">{capacity?.currentTaskManagers ?? '—'} 个</Descriptions.Item>
              <Descriptions.Item label="Slot 容量">
                {capacity ? `${capacity.slotsAvailable} 可用 / ${capacity.slotsTotal} 总计` : '—'}
              </Descriptions.Item>
            </Descriptions>

            {info.vertices?.length > 0 && (
              <Table<API.FlinkJobScalingVertex>
                className="flink-job-scaling-table"
                size="small"
                pagination={false}
                rowKey={vertexKey}
                dataSource={info.vertices}
                columns={[
                  {
                    title: '算子 / Vertex',
                    dataIndex: 'name',
                    ellipsis: true,
                    render: (value, record) => (
                      <div className="flink-job-vertex-name">
                        <Typography.Text strong ellipsis={{ tooltip: value }}>{value || '未命名算子'}</Typography.Text>
                        <Typography.Text type="secondary" code>{record.vertexId || record.id || '—'}</Typography.Text>
                      </div>
                    ),
                  },
                  { title: '当前并行度', dataIndex: 'currentParallelism', width: 110, align: 'right' },
                  {
                    title: '资源需求范围',
                    key: 'bounds',
                    width: 145,
                    align: 'right',
                    render: (_, record) => {
                      const lower = record.lowerBound ?? record.requestedLowerBound;
                      const upper = record.upperBound ?? record.requestedUpperBound;
                      return lower == null && upper == null ? '未设置' : `${lower ?? '—'} - ${upper ?? '—'}`;
                    },
                  },
                ]}
              />
            )}

            <div className="flink-job-scaling-footer">
              <Typography.Text type="secondary">
                Job ID：<Typography.Text code copyable>{info.jobId || '—'}</Typography.Text>
                {' · '}观测时间 {formatTime(info.observedAt)}
              </Typography.Text>
            </div>
          </>
        ) : null}
      </Card>

      <Modal
        title="调整 Flink 作业并行度"
        open={modalOpen}
        width={620}
        okText={direction === 'down' ? '确认缩小并行度' : direction === 'up' ? '确认扩大并行度' : direction === 'mixed' ? '确认统一并行度' : '提交调整'}
        cancelText="取消"
        confirmLoading={submitting}
        okButtonProps={{ danger: direction === 'down' || direction === 'mixed', disabled: direction === 'same' }}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form<ScaleFormValues> form={form} layout="vertical" onFinish={handleSubmit} preserve={false}>
          <Alert
            className="flink-job-scaling-modal-alert"
            showIcon
            type={direction === 'down' || direction === 'mixed' ? 'warning' : 'info'}
            message={direction === 'down'
              ? '缩小并行度会触发作业重新调度'
              : direction === 'mixed'
                ? '各算子当前并行度不同，本次将统一目标值'
                : '通过 Adaptive Scheduler 更新作业资源需求'}
            description={`当前并行度 ${currentDisplay}，允许调整范围 ${minTarget} - ${maxTarget}。运行中作业可能出现短暂吞吐波动。`}
          />

          <Form.Item
            name="targetParallelism"
            label="目标并行度"
            rules={[
              { required: true, message: '请输入目标并行度' },
              {
                validator: (_, value) => info?.currentParallelism != null && value === info.currentParallelism
                  ? Promise.reject(new Error('目标并行度必须与当前并行度不同'))
                  : Promise.resolve(),
              },
            ]}
          >
            <InputNumber min={minTarget} max={maxTarget} precision={0} style={{ width: '100%' }} />
          </Form.Item>

          {capacityMayBeInsufficient && (
            <Alert
              className="flink-job-scaling-capacity-warning"
              showIcon
              type="warning"
              message="当前空闲 Slot 可能不足"
              description={`按各 Vertex 当前最大并行度估算，本次至少还需 ${minimumAdditionalSlots} 个 Slot；集群当前仅有 ${capacity?.slotsAvailable ?? 0} 个可用 Slot且未开启自动扩容。Flink 可能持续等待所需资源。`}
            />
          )}

          <Form.Item
            name="reason"
            label="调整原因"
            rules={[
              { required: true, whitespace: true, message: '请填写调整原因' },
              { max: 200, message: '调整原因不能超过 200 个字符' },
            ]}
          >
            <Input.TextArea rows={3} showCount maxLength={200} placeholder="例如：上游流量增长，需要提高实时处理吞吐" />
          </Form.Item>

          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="当前并行度">{currentDisplay}</Descriptions.Item>
            <Descriptions.Item label="目标并行度">
              <Typography.Text strong type={direction === 'down' ? 'danger' : undefined}>
                {targetParallelism ?? '—'}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="可用 Slot">{capacity?.slotsAvailable ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="自动扩容">
              <Tag color={info?.autoExpansionSupported ? 'success' : 'default'}>
                {info?.autoExpansionSupported ? '可用' : '未启用'}
              </Tag>
            </Descriptions.Item>
          </Descriptions>
        </Form>
      </Modal>
    </>
  );
};

export default FlinkJobScalingCard;
