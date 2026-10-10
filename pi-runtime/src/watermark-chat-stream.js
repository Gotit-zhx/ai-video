// Read through the provider's completion marker; never accept a truncated JSON prefix.
export async function readWatermarkChatStream(response) {
  const decoder = new TextDecoder();
  let pending = '', reply = '', completed = false, finishReason = null;
  function line(value) {
    if (!value.startsWith('data:')) return;
    const data = value.slice(5).trim();
    if (!data) return;
    if (data === '[DONE]') { completed = true; return; }
    const event = JSON.parse(data);
    if (event.error) throw new Error('视觉模型流返回错误，不能使用部分结果');
    const choice = event.choices?.[0];
    if (choice?.finish_reason) finishReason = choice.finish_reason;
    if (typeof choice?.delta?.content === 'string') reply += choice.delta.content;
  }
  for await (const chunk of response.body) {
    pending += decoder.decode(chunk, {stream:true});
    let end;
    while ((end = pending.indexOf('\n')) >= 0) {
      line(pending.slice(0, end).replace(/\r$/, ''));
      pending = pending.slice(end + 1);
      if (completed) break;
    }
    if (completed) break;
  }
  if (!completed) {
    pending += decoder.decode();
    if (pending.trim()) line(pending.replace(/\r$/, ''));
  }
  if (!completed) throw new Error('视觉模型连接在完成标记之前关闭，保留任务等待核查');
  if (finishReason !== 'stop') throw new Error('视觉模型未正常完成：' + (finishReason || '缺少 finish_reason'));
  if (!reply.trim()) throw new Error('视觉模型未返回判定内容');
  return reply.trim();
}
