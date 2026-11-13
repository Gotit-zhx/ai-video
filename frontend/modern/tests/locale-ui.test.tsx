import { describe, expect, it } from 'vitest';
import { BusinessTable } from '../src/shared/components';
import { createFinanceTranslator } from '../src/shared/i18n';
import { renderFinance } from './fixtures';

describe('financial interface locale', () => {
  it('uses the Chinese component locale in a Chinese workspace', () => {
    const view=renderFinance(<BusinessTable columns={[{title:'金额',dataIndex:'amount'}]} dataSource={[]} />);
    expect(view.getByText('暂无数据', {selector:'.ant-empty-description'})).toBeVisible();
  });
  it('translates primary finance navigation in every supported non-Chinese language', () => {
    for (const locale of ['en','id','th','vi','ms','fil']) {
      const translate=createFinanceTranslator(locale);
      for (const key of ['财务总览','实时大屏','结算中心','对账中心','订单与退款','费用与调整','成本管理','广告分析','财务店铺管理','返回旧版']) {
        expect(translate(key)).not.toBe(key);
      }
    }
  });
});
