/** Jackson LocalDateTime may be serialized as components or an ISO string. */
export const formatBackendDateTime = (value?: string | number[] | null) => {
  if (!value) return '—';
  const date = Array.isArray(value)
    ? new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0, value[5] || 0)
    : new Date(value);
  return Number.isNaN(date.getTime()) ? '时间未记录' : date.toLocaleString('zh-CN', { hour12: false });
};
