import React from 'react';

/** Stable route fallback: keeps the application frame from collapsing while a page chunk loads. */
const RouteLoading: React.FC = () => (
  <div className="rtdwh-route-loading" role="status" aria-label="页面加载中">
    <div className="rtdwh-route-loading-header">
      <span className="rtdwh-loading-line is-title" />
      <span className="rtdwh-loading-line is-subtitle" />
    </div>
    <div className="rtdwh-route-loading-body">
      <div className="rtdwh-route-loading-toolbar">
        <span className="rtdwh-loading-line is-control" />
        <span className="rtdwh-loading-line is-control is-short" />
      </div>
      <div className="rtdwh-route-loading-card">
        <span className="rtdwh-loading-line is-card-title" />
        <span className="rtdwh-loading-line" />
        <span className="rtdwh-loading-line" />
        <span className="rtdwh-loading-line is-medium" />
      </div>
    </div>
  </div>
);

export default RouteLoading;
