(() => {
  const form = document.getElementById("commerce-plan-form");
  const result = document.getElementById("commerce-plan-result");
  if (!form || !result) return;
  const numeric = ["sourceProducts", "designsPerProduct", "imagesPerDesign", "shopCount",
    "dailyListingsPerShop", "dailyBudgetPerShop", "observationDays", "targetRoi"];
  function line(tag, value, parent = result) {
    const element = document.createElement(tag);
    element.textContent = value;
    parent.append(element);
    return element;
  }
  let revision = 0;
  form.addEventListener("input", () => {
    revision++;
    result.textContent = "配置已变更，请重新预览；旧结果已清除。";
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const currentRevision = ++revision;
    const data = Object.fromEntries(new FormData(form));
    numeric.forEach(key => { data[key] = Number(data[key]); });
    ["rightsConfirmed", "supplyConfirmed", "productionFilesConfirmed"].forEach(key => {
      data[key] = form.elements.namedItem(key).checked;
    });
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    result.textContent = "正在计算规划，不会执行外部操作…";
    try {
      const response = await api("/api/v1/commerce-plans/preview", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data)
      });
      if (currentRevision !== revision) return;
      const p = response.data;
      result.replaceChildren();
      line("h3", "规划预览 · 尚未创建任务");
      line("p", `设计／营销版本 ${p.designVersions} 个 · 素材 ${p.materialImages} 张 · 刊登草稿 ${p.listingDrafts} 个 · 理论最少 ${p.minimumPublishingDays} 天`);
      const money = amount => new Intl.NumberFormat("zh-CN", { style: "currency", currency: p.currency }).format(amount);
      line("p", `全店每日预算上限 ${money(p.dailyBudgetAllShops)}；${data.observationDays} 天总上限 ${money(p.observationBudgetCeiling)}。不含生成、生产、样品及物流费用。`);
      line("p", p.stages.join(" → "));
      line("h4", "理论分配（店铺序号仅为占位）");
      p.schedule.slice(0, 14).forEach(day => line("p", `第 ${day.day} 天：${day.listingsPerShop.map((count, i) => `店${i + 1} ${count}款`).join(" / ")}`));
      if (p.schedule.length > 14) line("p", `共 ${p.schedule.length} 天，此处仅展示前14天。`);
      line("h4", "执行前待解决");
      const blockers = line("ul", "");
      p.blockers.forEach(text => line("li", text, blockers));
      line("h4", "规划边界");
      const warnings = line("ul", "");
      p.warnings.forEach(text => line("li", text, warnings));
    } catch (error) {
      if (currentRevision === revision) result.textContent = error.message || "规划失败，请重试";
    } finally {
      button.disabled = false;
    }
  });
})();
