const {test}=require('node:test');
const assert=require('node:assert/strict');
const {describeFunctions,nativeAuthMetadata}=require('./native-auth-metadata.cjs');

test('known auth functions expose only their names and arity, without calls or getters',()=>{
  let calls=0;
  const object={
    getAuthCookieHeader:function(){calls++;return 'private-cookie-value'},
    createClient:function(options){calls++;return options},
    getClientHeaders:'private-header-value',
    unrelatedSecret:'must-not-be-retained',
  };
  Object.defineProperty(object,'getZpToken',{get(){calls++;throw Error('no getters')}});
  assert.deepEqual(describeFunctions(object),[
    {name:'getAuthCookieHeader',argumentCount:0},{name:'createClient',argumentCount:1},
  ]);
  assert.equal(calls,0);
  assert.deepEqual(describeFunctions(new Proxy({},{
    getOwnPropertyDescriptor(){throw Error('no proxy traps')},
  })),[]);
});

test('main and global auth exports are not lost to blanket credential-name filtering',()=>{
  const fn=()=>{throw Error('do not invoke')};
  const report=nativeAuthMetadata({exports:{getAuthCookieHeader:fn}},{getAuthCookieHeader:fn},{
    'C:\\temp\\resources\\app.asar\\node_modules\\@bzl\\electron-node-fetch\\dist\\index.js':
      {exports:{createClient:fn,enfetch:fn}},
    '/private/token-module.js':{exports:{getAuthCookieHeader:()=>null}},
  });
  assert.equal(report.providers.length,3);
  assert.equal(report.providers[0].functions[0].name,'getAuthCookieHeader');
  assert.equal(report.providers[1].functions[0].name,'getAuthCookieHeader');
  assert.equal(report.providers[2].scope,'original-native-http-module');
  assert.equal(report.rawValuesRetained,false);
  assert.equal(report.originalMethodsInvoked,0);
});
