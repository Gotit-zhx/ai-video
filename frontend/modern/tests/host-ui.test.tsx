import { describe, expect, it, vi } from 'vitest';
import { FinanceApplication } from '../src/features/finance/FinanceApplication';
import { createFinanceHostFixture, renderFinance } from './fixtures';

describe('host integration in finance workspace',()=>{
  it('returns through the host with current selection and displays the one-time authorization result',()=>{
    const fixture=createFinanceHostFixture();
    const getLegacyUrl=vi.fn(()=>'/app?financeUi=legacy&shopId=shop-a#finance-overview');
    const consumeAuthorizationNotice=vi.fn(()=>({status:'error' as const,message:'未取得可用广告账户'}));
    const host={...fixture.host,getLegacyUrl,consumeAuthorizationNotice};
    const view=renderFinance(<FinanceApplication />,{host,filters:{shopId:'shop-a',start:'2026-10-01',end:'2026-10-31'}});
    expect(view.getByRole('link',{name:'返回旧版'})).toHaveAttribute('href','/app?financeUi=legacy&shopId=shop-a#finance-overview');
    expect(getLegacyUrl).toHaveBeenCalledWith(expect.objectContaining({shopId:'shop-a',start:'2026-10-01',end:'2026-10-31'}));
    expect(view.getByText('未取得可用广告账户')).toBeVisible();
    expect(consumeAuthorizationNotice).toHaveBeenCalledOnce();
  });
});
