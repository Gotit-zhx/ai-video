import { useRef, useState } from "react";
import type { FinanceWriteMethod, Row } from "../../../shared/contracts";
import { text } from "../../../shared/rows";
import { useFinance } from "../context";
import { useOwnerFence } from "../presentation";
export const shopName = (shop: Row) =>
  text(shop.display_name) || text(shop.name) || text(shop.id) || "—";
export const enabled = (value: unknown) => value === true || value === 1;
/** One explicit command per click; never automatically replay an uncertain write. */
export function useShopCommand() {
  const { api, session, active, t } = useFinance();
  const fence = useOwnerFence();
  const permission = useRef(false);
  permission.current = session.canManage && active;
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [notice, setNotice] = useState("");
  async function run(
    path: string,
    method: FinanceWriteMethod,
    body?: unknown,
    after?: (value: unknown) => void | Promise<void>,
  ) {
    if (busy.current || !permission.current || !fence.isCurrent()) return;
    busy.current = true;
    setPending(true);
    setError(undefined);
    setNotice("");
    try {
      const value = await api.send(path, method, body);
      if (!fence.isCurrent() || !permission.current) return;
      await after?.(value);
      if (fence.isCurrent())
        setNotice(typeof value === "string" ? value : t("操作已提交"));
    } catch (e) {
      if (fence.isCurrent()) setError(e);
    } finally {
      busy.current = false;
      if (fence.isCurrent()) setPending(false);
    }
  }
  return { run, pending, error, notice, setError, setNotice };
}
