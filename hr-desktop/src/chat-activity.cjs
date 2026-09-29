// Read only the official page's already-rendered state. No requests, websocket
// interception, cookie access, navigation, or message content cross this boundary.
function sampleChatActivity() {
  const result=[];
  const stamp=value=>{
    const n=Number(value);
    return Number.isFinite(n)?(n>1e12?n/1000:n):0;
  };
  for(const element of document.querySelectorAll('.geek-item')){
    const vm=element.__vue__||element.parentElement?.__vue__;
    const item=vm?.$props?.source;
    if(!item)continue;
    result.push({friend:String(item.friendId||''),job:String(item.encryptJobId||''),
      mid:String(item.lastMsgId||''),at:stamp(item.lastTS||item.updateTime),
      incoming:item.lastIsSelf===false});
    if(result.length>=200)break;
  }
  // A currently open conversation can receive a message without an unread badge.
  for(const element of [...document.querySelectorAll('.message-item')].slice(-30)){
    const props=element.__vue__?.$props, message=props?.message;
    if(!message)continue;
    result.push({friend:String(props.conversation?.friendId||''),job:'',
      mid:String(message.mid||''),at:stamp(message.time),
      incoming:message.isSelf===false&&String(message.fromId)===String(props.conversation?.friendId)});
  }
  return result;
}

class ChatActivityTracker {
  constructor(){this.reset();}
  reset(){this.seen=new Map();}
  update(rows,now=Date.now()){
    const changes=new Map();
    for(const row of Array.isArray(rows)?rows.slice(0,230):[]){
      if(!/^[1-9]\d{0,19}$/.test(row.friend)||!/^[1-9]\d{0,29}$/.test(row.mid)
        ||typeof row.at!=='number'||row.at<=0||row.at>now/1000+60)continue;
      const previous=this.seen.get(row.friend);
      // Virtualized rows, read receipts, list reordering and our own sends are
      // not new incoming messages. Keep the high-water mark across scrolling.
      if(previous&&BigInt(row.mid)<=BigInt(previous))continue;
      this.seen.delete(row.friend);this.seen.set(row.friend,row.mid);
      if(this.seen.size>2000)this.seen.delete(this.seen.keys().next().value);
      if(row.incoming!==true||row.at<now/1000-180)continue;
      changes.set(row.friend,{friend:row.friend,job:typeof row.job==='string'?row.job.slice(0,180):'',
        mid:row.mid,at:row.at});
    }
    return [...changes.values()];
  }
}
module.exports={sampleChatActivity,ChatActivityTracker};
