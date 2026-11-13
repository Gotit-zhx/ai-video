import type { CSSProperties, ReactNode } from 'react';
import { Card, Table, Tag, Tooltip, Typography } from 'antd';
import type { CardProps, TableProps, TagProps } from 'antd';
import { CircleHelp } from 'lucide-react';

export type BusinessTableProps<T extends object> = TableProps<T>;

export function BusinessTable<T extends object>(props: BusinessTableProps<T>) {
  return <Table<T> size="medium" {...props} scroll={{ x: 'max-content', ...props.scroll }} />;
}

export interface PanelProps extends Omit<CardProps, 'title'> {
  title?: ReactNode;
  extra?: ReactNode;
}

export function Panel({ children, className, ...props }: PanelProps) {
  return (
    <Card {...props} className={['finance-panel', className].filter(Boolean).join(' ')}>
      {children}
    </Card>
  );
}

export interface MetricCardProps {
  label: ReactNode;
  value: ReactNode;
  detail?: ReactNode;
  tone?: 'default' | 'positive' | 'warning' | 'negative';
}

export function MetricCard({ label, value, detail, tone = 'default' }: MetricCardProps) {
  return (
    <Card className={`finance-metric finance-metric-${tone}`} size="small">
      <Typography.Text type="secondary">{label}</Typography.Text>
      <Typography.Title level={3}>{value}</Typography.Title>
      {detail ? <div className="finance-metric-detail">{detail}</div> : null}
    </Card>
  );
}

export interface InfoTipProps {
  title: ReactNode;
  size?: number;
  style?: CSSProperties;
}

export function InfoTip({ title, size = 16, style }: InfoTipProps) {
  const accessibleTitle = typeof title === 'string' ? title : 'More information';
  return (
    <Tooltip title={title} trigger={['hover', 'focus', 'click']}>
      <button aria-label={accessibleTitle} className="finance-info-tip" style={style} type="button">
        <CircleHelp aria-hidden="true" size={size} />
      </button>
    </Tooltip>
  );
}

const statusColors = {
  default: 'default',
  success: 'success',
  processing: 'processing',
  warning: 'warning',
  error: 'error'
} satisfies Record<string, TagProps['color']>;

export interface StatusTagProps extends Omit<TagProps, 'color'> {
  status?: keyof typeof statusColors;
  color?: TagProps['color'];
}

export function StatusTag({ status = 'default', color, ...props }: StatusTagProps) {
  return <Tag {...props} color={color ?? statusColors[status]} />;
}
