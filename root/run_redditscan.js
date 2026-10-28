#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const crypto = require('crypto');
const url = require('url');

const SCRIPT_PATH = '/root/Lumora-Plugins/scripts/redditscan.js';
const OUTPUT_DIR = '/root/providers';

const script = fs.readFileSync(SCRIPT_PATH, 'utf8');

const candidates = [];
const host = {
  log: function(msg) { console.log('[HOST]', msg); },
  reportProgress: function(msg) { console.log('[PROGRESS]', msg); },
  reportCandidate: function(cand) { console.log('[CANDIDATE]', JSON.stringify(cand)); candidates.push(cand); },
  base64Encode: function(s) { return Buffer.from(s).toString('base64'); },
  base64Decode: function(s) { return Buffer.from(s, 'base64').toString('utf8'); },
  base64Slice: function(b64, start, end) {
    const buf = Buffer.from(b64, 'base64');
    if (end === null || end === undefined) return buf.slice(start).toString('base64');
    return buf.slice(start, end).toString('base64');
  },
  base64Concat: function(a,b){ if(!a)a=''; if(!b)b=''; const buf1=Buffer.from(a,'base64'); const buf2=Buffer.from(b,'base64'); return Buffer.concat([buf1,buf2]).toString('base64'); },
  hmacSha512: function(keyB64,msgB64){ const key=Buffer.from(keyB64,'base64'); const msg=Buffer.from(msgB64,'base64'); const h=crypto.createHmac('sha512',key); h.update(msg); return h.digest('base64'); },
  md5Bytes: function(inputB64){ const input=Buffer.from(inputB64,'base64'); const m=crypto.createHash('md5'); m.update(input); return m.digest('base64'); },
  aesCbcDecrypt: function(ctB64,keyB64,ivB64){ try{ const ct=Buffer.from(ctB64,'base64'); const key=Buffer.from(keyB64,'base64'); const iv=Buffer.from(ivB64,'base64'); const d=crypto.createDecipheriv('aes-256-cbc',key,iv); d.setAutoPadding(true); let dec=d.update(ct); dec=Buffer.concat([dec,d.final()]); return dec.toString('utf8'); }catch(e){return null;} }
};

try {
  const syncRequest = require('sync-request');
  host.httpGet = function(urlStr, headers, timeoutMs){ try{ const res=syncRequest('GET',urlStr,{headers:headers||{},timeout:timeoutMs||60000}); const body=res.getBody('utf8'); if(body.length>8*1024*1024)return {status:0,body:''}; return {status:res.statusCode,body:body}; }catch(e){return {status:0,body:''}; } };
  host.httpPost = function(urlStr, body, headers, timeoutMs){ try{ const res=syncRequest('POST',urlStr,{headers:headers||{},body:body||'',timeout:timeoutMs||60000}); const bodyStr=res.getBody('utf8'); if(bodyStr.length>8*1024*1024)return {status:0,body:''}; return {status:res.statusCode,body:bodyStr}; }catch(e){return {status:0,body:''}; } };
  host.httpGetAll = function(reqs){ const results=[]; for(let i=0;i<reqs.length;i++){ const r=reqs[i]; try{ const res=syncRequest('GET',r.url,{headers:r.headers||{},timeout:r.timeoutMs||60000}); const bodyStr=res.getBody('utf8'); if(bodyStr.length>8*1024*1024){ results.push({status:0,body:''}); }else{ results.push({status:res.statusCode,body:bodyStr}); } }catch(e){ results.push({status:0,body:''}); } } return results; };
} catch(e) {
  console.log('no sync-request');
}

const vm = require('vm');
const context = { PLUGIN:null, console:console, host:host, Date:Date, Math:Math, JSON:JSON, RegExp:RegExp, Array:Array, Object:Object, String:String, Number:Number, Boolean:Boolean, Error:Error, parseInt:parseInt, parseFloat:parseFloat, isNaN:isNaN, setTimeout:setTimeout, clearTimeout:clearTimeout };
vm.createContext(context);
try {
  vm.runInContext(script, context);
  console.log('RUNNING...');
  const result = context.discover(context.host);
  console.log('RESULT=' + result);
  console.log('CANDIDATES_COUNT=' + candidates.length);
  if(candidates.length>0){ fs.mkdirSync(OUTPUT_DIR,{recursive:true}); const ts=new Date().toISOString().replace(/[:.]/g,'-'); const out=path.join(OUTPUT_DIR,'providers_'+ts+'.json'); fs.writeFileSync(out,JSON.stringify(candidates,null,2)); console.log('SAVED='+out); }
} catch(e) { console.error('ERR',e.message); console.error(e.stack); }
