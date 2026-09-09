#!/usr/bin/env python3
import json,urllib.request,urllib.parse,urllib.error
import os
base=os.environ.get('BASE_URL','http://localhost:8080')
def token(user):
 data=urllib.parse.urlencode(dict(grant_type='password',client_id='shop',username=user,password=user+'-local-only')).encode()
 return json.load(urllib.request.urlopen(urllib.request.Request('http://localhost:8180/realms/shop/protocol/openid-connect/token',data=data),timeout=10))['access_token']
def get(path,t):return urllib.request.urlopen(urllib.request.Request(base+path,headers={'Authorization':'Bearer '+t}),timeout=10)
a=token('alice');b=token('bob')
with urllib.request.urlopen(base,timeout=10) as r: html=r.read().decode();assert r.status==200 and 'Order fulfillment lab' in html
with get('/api/orders',a) as r:items=json.load(r)['items'];assert items
id=items[0]['orderId']
with get('/api/orders/'+id,a) as r: detail=json.load(r);version=detail['commandVersion']
try:get('/api/orders/'+id,b);raise AssertionError('Ownership bypass')
except urllib.error.HTTPError as e:assert e.code==404
req=urllib.request.Request(base+'/api/orders/'+id+'/events',headers={'Authorization':'Bearer '+a,'Last-Event-ID':str(version-1)})
with urllib.request.urlopen(req,timeout=10) as r:
 assert r.status==200 and 'text/event-stream' in r.headers['Content-Type']
 while True:
  line=r.readline().decode()
  if line.startswith('id:'):assert int(line[3:].strip())==version;break
print(json.dumps({'browser_assets_http':200,'real_keycloak_login':'passed','gateway_bff_aggregation':'passed','cross_customer_ownership':'404 as expected','gateway_sse_resume':'passed','version':version,'browser_visual_qa':'unavailable: no browser connected'},indent=2))
