/* One runtime process, foreground priority, and no retries of completed work. */
class OperationQueue {
  constructor(){this.tasks=[];this.current=null;this.keys=new Map();this.draining=false;this.foregroundRuns=0;}
  get pending(){return this.tasks.length;}
  get active(){return this.draining||this.tasks.length>0;}
  add(run,{key='',background=false,signal}={}){
    if(signal?.aborted)return Promise.reject(this.cancelled());
    let task=key&&this.keys.get(key);
    if(!task){
      if(this.tasks.length>=32)return Promise.reject(Error('等待操作较多，请等待当前读取完成'));
      task={run,key,background,waiters:new Set(),started:false};
      this.tasks.push(task);if(key)this.keys.set(key,task);
    }else if(!background)task.background=false;
    const promise=new Promise((resolve,reject)=>{
      const waiter={resolve,reject,signal};
      waiter.abort=()=>{
        task.waiters.delete(waiter);reject(this.cancelled());
        if(!task.started&&!task.waiters.size)this.remove(task);
      };
      task.waiters.add(waiter);
      signal?.addEventListener('abort',waiter.abort,{once:true});
    });
    void this.drain();
    return promise;
  }
  cancelled(){return Object.assign(Error('操作已取消，未开始的请求不会执行'),{code:'OPERATION_CANCELLED'});}
  remove(task){
    const index=this.tasks.indexOf(task);if(index!==-1)this.tasks.splice(index,1);
    if(task.key&&this.keys.get(task.key)===task)this.keys.delete(task.key);
  }
  settle(task,error,value){
    for(const waiter of task.waiters){
      waiter.signal?.removeEventListener('abort',waiter.abort);
      if(error)waiter.reject(error);else waiter.resolve(value);
    }
    task.waiters.clear();
  }
  cancelPending(){
    for(const task of [...this.tasks]){this.remove(task);this.settle(task,this.cancelled());}
  }
  async drain(){
    if(this.draining)return;
    this.draining=true;
    try{
      while(this.tasks.length){
        // A burst of manual reads may go first, but cannot starve resume receipt.
        const wantBackground=this.foregroundRuns>=4&&this.tasks.some(task=>task.background);
        let index=this.tasks.findIndex(task=>task.background===wantBackground);
        if(index<0)index=0;
        const [task]=this.tasks.splice(index,1);
        task.started=true;this.current=task;
        this.foregroundRuns=task.background?0:this.foregroundRuns+1;
        try{this.settle(task,null,await task.run(()=>task.waiters.size>0));}
        catch(error){this.settle(task,error);}
        finally{this.remove(task);this.current=null;}
      }
    }finally{this.draining=false;}
  }
}
module.exports={OperationQueue};
